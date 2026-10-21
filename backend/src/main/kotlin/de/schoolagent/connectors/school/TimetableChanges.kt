package de.schoolagent.connectors.school

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** Compare pupil-facing schedule facts, independently of teacher wording or room codes. */
object TimetableChanges {
    private val json = ObjectMapper()
    fun significant(current: String, previous: String?): Boolean {
        val now = json.readTree(current)
        val timetable = now.path("timetable")
        if (!timetable.isObject) return false
        val before = previous?.let { json.readTree(it) }?.takeIf { it.path("timetable").isObject }
        if (before != null) return signature(now) != signature(before)
        // An unclassified legacy substitution is not proof that the pupil's schedule changed.
        return timetable.path("cancelled").asBoolean() || timetable.path("scheduleChanged").asBoolean()
    }
    private fun signature(root: JsonNode): List<Any> {
        val t = root.path("timetable")
        fun text(n: JsonNode) = if (n.isTextual) n.asText().trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT) else ""
        return listOf(text(root.path("date")), text(root.path("time")), text(root.path("endTime")),
            text(t.path("period")), text(t.path("subject")), t.path("cancelled").asBoolean(),
            t.path("added").asBoolean(false))
    }
}
