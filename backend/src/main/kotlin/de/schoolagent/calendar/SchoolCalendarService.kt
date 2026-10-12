package de.schoolagent.calendar

import com.fasterxml.jackson.databind.JsonNode
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

    /** Imports Bavarian holidays/vacations from ferien-api.de. Idempotent. */
    fun importBavarian(year: Int): ImportResult {
        val raw = try {
            rest.getForObject("https://ferien-api.de/api/v1/holidays/BY/$year", String::class.java)
        } catch (e: Exception) {
            throw IllegalStateException("Could not reach ferien-api.de: ${e.message}", e)
        }
        val holidays: List<JsonNode> = com.fasterxml.jackson.databind.ObjectMapper().readTree(raw)?.toList() ?: emptyList()
        var imported = 0
        var skipped = 0
        for (h in holidays) {
            val name = prettify(h.path("name").asText())
            val start = LocalDate.parse(h.path("start").asText().take(10))
            val end = LocalDate.parse(h.path("end").asText().take(10))
            val type = classify(h.path("name").asText())
            val existing = repo.findByNameAndStartDateAndEndDate(name, start, end)
            if (existing != null) {
                skipped++
                continue
            }
            repo.save(
                SchoolCalendarEntry(
                    childId = null, // applies to all kids
                    name = name,
                    startDate = start,
                    endDate = end,
                    entryType = type,
                    createdAt = clock.instant(),
                ),
            )
            imported++
        }
        log.info("bavarian calendar import {}: {} imported, {} already present", year, imported, skipped)
        return ImportResult(imported, skipped)
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
