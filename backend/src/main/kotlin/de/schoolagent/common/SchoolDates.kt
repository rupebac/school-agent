package de.schoolagent.common

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Dates and times as they appear in Bavarian school letters and in model JSON. */
object SchoolDates {
    private val germanDate = DateTimeFormatter.ofPattern("d.M.yyyy")
    private val isoDate = DateTimeFormatter.ISO_LOCAL_DATE
    private val clockTime = Regex("""^(\d{1,2})[:.](\d{2})(?:\s*uhr)?$""", RegexOption.IGNORE_CASE)
    private val oclock = Regex("""^(\d{1,2})\s*uhr$""", RegexOption.IGNORE_CASE)

    fun date(value: String?): LocalDate? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", true) } ?: return null
        runCatching { LocalDate.parse(raw, germanDate) }.getOrNull()?.let { return it }
        runCatching { LocalDate.parse(raw, isoDate) }.getOrNull()?.let { return it }
        return null
    }

    fun time(value: String?): LocalTime? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", true) } ?: return null
        clockTime.matchEntire(raw)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h in 0..23 && min in 0..59) return LocalTime.of(h, min)
        }
        oclock.matchEntire(raw)?.let { m ->
            val h = m.groupValues[1].toInt()
            if (h in 0..23) return LocalTime.of(h, 0)
        }
        return null
    }
}
