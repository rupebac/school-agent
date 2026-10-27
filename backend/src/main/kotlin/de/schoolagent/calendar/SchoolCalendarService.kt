package de.schoolagent.calendar

import de.schoolagent.domain.model.CalendarEntryType
import de.schoolagent.domain.model.SchoolCalendarEntry
import de.schoolagent.domain.repo.SchoolCalendarEntryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

/**
 * Bavarian school calendar intelligence: knows when there is no school
 * (vacations, holidays, bridge days) and provides school-day-aware date
 * calculations. NOT projected to Google Calendar — internal only.
 */
@Service
class SchoolCalendarService(
    private val repo: SchoolCalendarEntryRepository,
    private val rest: RestTemplate,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** True if the date is a school day (weekday, not in vacation/holiday). */
    fun isSchoolDay(date: LocalDate, childId: UUID? = null): Boolean {
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) return false
        val entries = repo.findByStartDateLessThanEqualAndEndDateGreaterThanEqual(date, date)
        return entries.none { it.childId == null || it.childId == childId }
    }

    /** Name of the vacation/holiday covering this date, if any. */
    fun holidayName(date: LocalDate, childId: UUID? = null): String? {
        val entries = repo.findByStartDateLessThanEqualAndEndDateGreaterThanEqual(date, date)
        return entries
            .filter { it.childId == null || it.childId == childId }
            .maxByOrNull { it.startDate }
            ?.name
    }

    /** Counts only school days between two dates (inclusive). */
    fun schoolDaysBetween(from: LocalDate, to: LocalDate, childId: UUID? = null): Int {
        if (to.isBefore(from)) return 0
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .count { isSchoolDay(it, childId) }
    }

    /** Entries overlapping a date range (for the calendar view). */
    fun entriesBetween(from: LocalDate, to: LocalDate): List<SchoolCalendarEntry> =
        repo.findByStartDateBetweenOrderByStartDate(from.minusDays(200), to)

    private var primaryRetryAfter = java.time.Instant.EPOCH

    /** Validate a complete year before changing data; an unavailable provider is never an empty success. */
    @Synchronized
    @org.springframework.transaction.annotation.Transactional
    fun importBavarian(year: Int): ImportResult {
        require(year in 2000..2100) { "Unsupported calendar year" }
        val holidays = loadYear(year)
        var imported = 0
        var skipped = 0
        for (h in holidays) {
            val existing = repo.findByChildIdIsNullAndNameAndStartDateAndEndDate(h.name, h.start, h.end)
            if (existing != null) { skipped++; continue }
            repo.save(SchoolCalendarEntry(childId = null, name = h.name, startDate = h.start,
                endDate = h.end, entryType = classify(h.name.lowercase()), createdAt = clock.instant()))
            imported++
        }
        log.info("bavarian calendar import {}: {} imported, {} already present", year, imported, skipped)
        return ImportResult(imported, skipped)
    }

    private data class Holiday(val name: String, val start: LocalDate, val end: LocalDate)
    private fun loadYear(year: Int): List<Holiday> {
        val providers = buildList {
            if (!clock.instant().isBefore(primaryRetryAfter)) add("primary" to "https://ferien-api.de/api/v1/holidays/BY/$year")
            add("fallback" to "https://schulferien-api.de/api/v2/$year/BY")
        }
        for ((provider, url) in providers) {
            try {
                val raw = rest.getForObject(url, String::class.java) ?: error("Empty holiday response")
                require(raw.length <= 256 * 1024) { "Holiday response too large" }
                val root = com.fasterxml.jackson.databind.ObjectMapper().readTree(raw)
                require(root.isArray && root.size() in 1..64) { "Invalid holiday list" }
                return root.map { h ->
                    require(h.path("name").isTextual && h.path("name").asText().isNotBlank() && h.path("name").asText().length <= 100)
                    require(h.path("stateCode").asText() == "BY" && h.path("year").asInt() == year)
                    val start = LocalDate.parse(h.path("start").asText().take(10))
                    val end = LocalDate.parse(h.path("end").asText().take(10))
                    require(start.year == year && !end.isBefore(start) && !end.isAfter(start.plusDays(90)))
                    Holiday(prettify(h.path("name").asText()), start, end)
                }.distinct()
            } catch (e: Exception) {
                if (provider == "primary" && e is org.springframework.web.client.HttpClientErrorException && e.statusCode.value() == 429)
                    primaryRetryAfter = clock.instant().plusSeconds(15 * 60)
                log.warn("School holiday provider failed provider={} year={} errorType={}", provider, year, e.javaClass.simpleName)
            }
        }
        throw HolidayImportUnavailable()
    }

    data class ImportResult(val imported: Int, val alreadyPresent: Int)

    private fun prettify(slug: String): String =
        slug.split(Regex("[-_]")).joinToString(" ") { word ->
            if (word.length > 3) word.replaceFirstChar { it.uppercase() }
            else when (word) {
                "der", "die", "das", "und", "im" -> word
                else -> word.replaceFirstChar { it.uppercase() }
            }
        }

    private fun classify(slug: String): CalendarEntryType = when {
        slug.contains("ferien") -> CalendarEntryType.VACATION
        else -> CalendarEntryType.HOLIDAY
    }
}

class HolidayImportUnavailable : RuntimeException("School holiday data is temporarily unavailable")
