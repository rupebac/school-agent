package de.schoolagent.common

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Inclusive start, exclusive end — used to filter school dates from chat questions. */
data class DateWindow(
    val fromInclusive: LocalDate?,
    val toExclusive: LocalDate?,
) {
    fun contains(date: LocalDate): Boolean {
        if (fromInclusive != null && date.isBefore(fromInclusive)) return false
        if (toExclusive != null && !date.isBefore(toExclusive)) return false
        return true
    }

    fun contains(instant: java.time.Instant, zone: ZoneId): Boolean =
        contains(instant.atZone(zone).toLocalDate())

    fun overlaps(start: LocalDate, end: LocalDate): Boolean {
        val rangeEnd = toExclusive ?: LocalDate.MAX
        val rangeStart = fromInclusive ?: LocalDate.MIN
        return !start.isAfter(rangeEnd.minusDays(1)) && !end.isBefore(rangeStart)
    }

    fun describe(): String = buildString {
        append(fromInclusive?.toString() ?: "…")
        append(" → ")
        append(toExclusive?.minusDays(1)?.toString() ?: "…")
    }
}

/**
 * Pulls a date window out of a parent question ("after 1 January and before
 * 1st September", "in September", "entre enero y marzo").
 */
object DateWindowParser {

    private val months = mapOf(
        "january" to 1, "januar" to 1, "enero" to 1, "jan" to 1,
        "february" to 2, "februar" to 2, "febrero" to 2, "feb" to 2,
        "march" to 3, "marz" to 3, "marzo" to 3, "mar" to 3,
        "april" to 4, "abril" to 4, "apr" to 4, "abr" to 4,
        "may" to 5, "mai" to 5, "mayo" to 5,
        "june" to 6, "juni" to 6, "junio" to 6, "jun" to 6,
        "july" to 7, "juli" to 7, "julio" to 7, "jul" to 7,
        "august" to 8, "agosto" to 8, "aug" to 8, "ago" to 8,
        "september" to 9, "septiembre" to 9, "setiembre" to 9, "sep" to 9, "sept" to 9,
        "october" to 10, "oktober" to 10, "octubre" to 10, "oct" to 10, "okt" to 10,
        "november" to 11, "noviembre" to 11, "nov" to 11,
        "december" to 12, "dezember" to 12, "diciembre" to 12, "dec" to 12, "dez" to 12, "dic" to 12,
    )

    private val monthAlt = months.keys.sortedByDescending { it.length }.joinToString("|")
    private val namedDate = Regex(
        """(?<d>\d{1,2})(?:st|nd|rd|th)?(?:\s*(?:of|de|\.)\s*|\s*)(?<m>$monthAlt)(?:\s+(?<y>\d{4}))?|(?<m2>$monthAlt)\s+(?<d2>\d{1,2})(?:st|nd|rd|th)?(?:\s+(?<y2>\d{4}))?""",
        RegexOption.IGNORE_CASE,
    )
    private val dotted = Regex("""\b(\d{1,2})\.(\d{1,2})\.(?:(\d{2,4}))?\b""")
    private val iso = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
    private val afterKw = Regex("""\b(?:after|since|from|nach|ab|despues|después|desde)\b""", RegexOption.IGNORE_CASE)
    private val beforeKw = Regex("""\b(?:before|until|till|vor|bis|antes|hasta)\b""", RegexOption.IGNORE_CASE)
    private val betweenKw = Regex("""\b(?:between|zwischen|entre)\b""", RegexOption.IGNORE_CASE)
    private val andKw = Regex("""\b(?:and|und|y)\b""", RegexOption.IGNORE_CASE)
    private val inMonth = Regex("""\b(?:in|im|en)\s+($monthAlt)\b""", RegexOption.IGNORE_CASE)

    fun parse(text: String, today: LocalDate): DateWindow? {
        val raw = fold(text)
        parseRelative(raw, today)?.let { return it }

        val between = parseBetween(raw, today)
        if (between != null) return between

        val after = afterKw.find(raw)?.let { parseDate(raw.substring(it.range.last + 1), today) }
        val before = beforeKw.find(raw)?.let { parseDate(raw.substring(it.range.last + 1), today) }
        val monthOnly = inMonth.find(raw)?.let { months[it.groupValues[1].lowercase()] }

        if (after == null && before == null && monthOnly == null) return null

        if (monthOnly != null && after == null && before == null) {
            val start = LocalDate.of(today.year, monthOnly, 1)
            return DateWindow(start, start.plusMonths(1))
        }

        val toExclusive = before
        return DateWindow(after, toExclusive)
    }

    private fun parseRelative(raw: String, today: LocalDate): DateWindow? {
        val t = raw.lowercase()
        return when {
            Regex("""\bthis week\b|\besta semana\b|\bdiese woche\b""").containsMatchIn(t) -> {
                val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                DateWindow(start, start.plusWeeks(1))
            }
            Regex("""\bnext week\b|\bproxima semana\b|\bpróxima semana\b|\bnachste woche\b|\bnächste woche\b""").containsMatchIn(t) -> {
                val start = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                DateWindow(start, start.plusWeeks(1))
            }
            Regex("""\bthis month\b|\beste mes\b|\bdiesen monat\b""").containsMatchIn(t) -> {
                val start = today.withDayOfMonth(1)
                DateWindow(start, start.plusMonths(1))
            }
            else -> null
        }
    }

    private fun parseBetween(raw: String, today: LocalDate): DateWindow? {
        val startKw = betweenKw.find(raw) ?: return null
        val rest = raw.substring(startKw.range.last + 1)
        val split = andKw.find(rest) ?: return null
        val a = parseDate(rest.substring(0, split.range.first), today) ?: return null
        val b = parseDate(rest.substring(split.range.last + 1), today) ?: return null
        val (from, to) = if (a.isAfter(b)) b to a else a to b
        return DateWindow(from, to.plusDays(1))
    }

    private fun parseDate(chunk: String, today: LocalDate): LocalDate? {
        val s = fold(chunk)
        iso.find(s)?.let { m ->
            return runCatching {
                LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }.getOrNull()
        }
        dotted.find(s)?.let { m ->
            val d = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val y = m.groupValues[3].ifBlank { today.year.toString() }
            val year = y.toInt().let { if (it < 100) 2000 + it else it }
            return runCatching { LocalDate.of(year, month, d) }.getOrNull()
        }
        namedDate.find(s)?.let { m ->
            val monthName = m.groups["m"]?.value ?: m.groups["m2"]?.value ?: return@let
            val day = (m.groups["d"]?.value ?: m.groups["d2"]?.value)?.toIntOrNull() ?: return@let
            val year = (m.groups["y"]?.value ?: m.groups["y2"]?.value)?.toIntOrNull() ?: today.year
            val month = months[monthName.lowercase()] ?: return@let
            return runCatching { LocalDate.of(year, month, day) }.getOrNull()
        }
        return null
    }

    private fun fold(text: String): String =
        text.lowercase()
            .replace('ä', 'a').replace('ö', 'o').replace('ü', 'u').replace("ß", "ss")
            .replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u')
            .replace('ñ', 'n')
            .replace(Regex("\\s+"), " ")
            .trim()
}
