package de.schoolagent.connectors.schulmanager

import com.fasterxml.jackson.databind.JsonNode
import de.schoolagent.common.DedupKeys
import de.schoolagent.connectors.school.*
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.LocalTime

/** Verified parent-view read calls from the provider's web client; no attendance/receipt writes. */
internal class SchulmanagerLearningFeeds(private val call: (String, String, Any) -> JsonNode?) {
    fun homework(studentId: String): SchoolRecordFeed {
        val data = call("classbook", "get-homework", mapOf("student" to mapOf("id" to studentId.toLong())))
            ?: return SchoolRecordFeed.unavailable(SchoolRecordKind.HOMEWORK, "Homework is not visible to this account")
        require(data.isArray && data.size() <= 2000) { "Unexpected homework response" }
        data.forEach { row ->
            require(!row.hasNonNull("studentId") || row.path("studentId").asText() == studentId)
            require(row.path("date").isTextual && row.path("subject").isTextual && row.path("homework").isTextual)
        }
        // The parent feed exposes day/subject groups rather than a guaranteed assignment ID.
        // Keep that exact granularity; edits update the same group, with no fabricated deadline.
        val records = data.groupBy { LocalDate.parse(it.path("date").asText()) to it.path("subject").asText() }
            .mapNotNull { (group, rows) ->
                val body = rows.map { Jsoup.parseBodyFragment(it.path("homework").asText()).text().trim() }
                    .filter { it.isNotBlank() }.distinct().sorted().joinToString("\n")
                if (body.isBlank()) null else SchoolHomework.observed(
                    id = DedupKeys.sha256("${group.first}|${group.second}"),
                    subject = group.second, text = body, recordedOn = group.first,
                    url = "${SchulmanagerSession.BASE_URL}/#/modules/classbook/homework/$studentId")
            }
        return SchoolRecordFeed(SchoolRecordKind.HOMEWORK, SchoolFeedStatus.AVAILABLE, records)
    }

    fun timetable(studentId: String, from: LocalDate): SchoolRecordFeed {
        val until = from.plusDays(13)
        val data = call("schedules", "get-actual-lessons", mapOf("student" to mapOf("id" to studentId.toLong()),
            "start" to from.toString(), "end" to until.toString()))
            ?: return SchoolRecordFeed.unavailable(SchoolRecordKind.TIMETABLE_CHANGE, "The personal timetable is not visible to this account")
        require(data.isArray && data.size() <= 2000) { "Unexpected timetable response" }
        val records = data.mapNotNull { row ->
            require(!row.hasNonNull("studentId") || row.path("studentId").asText() == studentId)
            val day = LocalDate.parse(row.path("date").asText())
            require(day in from..until)
            val period = row.path("classHour").path("id").asText()
            require(period.toLongOrNull() != null)
            val cancelled = row.path("isCancelled").asBoolean(false)
            val original = row.path("originalLessons")
            val actual = row.path("actualLesson")
            // Routine lessons never become cases or calendar appointments.
            val changed = cancelled || (original.isArray && !original.isEmpty) || row.path("isNew").asBoolean(false)
            val subject = actual.path("subject").path("name").asText().ifBlank {
                original.firstOrNull()?.path("subject")?.path("name")?.asText().orEmpty()
            }.takeIf { it.isNotBlank() }
            val room = actual.path("room").path("name").asText().takeIf { it.isNotBlank() }
            val originalIds = original.map { it.path("id").asText() }.filter { it.isNotBlank() }.sorted()
            require(originalIds.isNotEmpty() || actual.path("id").asText().isNotBlank())
            val identity = "$day|$period|${originalIds.ifEmpty { listOf(actual.path("id").asText()) }.joinToString(",")}"
            val title = if (cancelled) "Lesson cancelled" else if (changed) "Lesson changed" else "Lesson restored"
            val detail = listOfNotNull(title, subject, room?.let { "Room: $it" },
                actual.path("comment").asText().takeIf { it.isNotBlank() }?.let { Jsoup.parseBodyFragment(it).text() }).joinToString("\n")
            // Period number alone is not a clock time. Neither implies arrival/departure permission.
            // The normal class-hour grid can differ on shortened school days. Keep the date
            // until the provider's effective-time rules are verified.
            SchoolRecord(DedupKeys.sha256(identity), SchoolRecordKind.TIMETABLE_CHANGE, title, detail,
                date = day,
                url = "${SchulmanagerSession.BASE_URL}/#/modules/schedules/view/student/$studentId",
                timetable = SchoolTimetableChange(cancelled, subject, room, period, changed))
        }
        // Missing exceptions cannot prove reinstatement unless the normal lesson was observed.
        return SchoolRecordFeed(SchoolRecordKind.TIMETABLE_CHANGE, SchoolFeedStatus.AVAILABLE, records)
    }
}
