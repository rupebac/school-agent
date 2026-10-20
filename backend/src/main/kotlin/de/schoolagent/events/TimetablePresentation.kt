package de.schoolagent.events

import de.schoolagent.connectors.school.SchoolTimetableChange

/** Periods are school identifiers, not clock times. Keep unknown subject codes verbatim. */
object TimetablePresentation {
    fun title(change: SchoolTimetableChange): String = buildString {
        append("Period ").append(change.period)
        change.subject?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        append(if (change.cancelled) " · Cancelled" else " · Timetable change")
        change.room?.takeIf { it.isNotBlank() }?.let { append(" · Room ").append(it) }
    }.take(200)
}
