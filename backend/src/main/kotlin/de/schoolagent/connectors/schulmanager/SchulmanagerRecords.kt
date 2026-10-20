package de.schoolagent.connectors.schulmanager

import com.fasterxml.jackson.databind.JsonNode
import de.schoolagent.connectors.school.*
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.LocalTime

/** Parent/student read endpoints, also used by Schulmanager's own web client. */
internal class SchulmanagerRecords(private val call: (String, String, Any) -> JsonNode?) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
    private fun failed(kind: SchoolRecordKind, error: Exception): SchoolRecordFeed {
        log.warn("Schulmanager section {} failed ({})", kind, error.javaClass.simpleName)
        return SchoolRecordFeed.failed(kind)
    }
    fun read(studentId: String, from: LocalDate, to: LocalDate): List<SchoolRecordFeed> {
        val modules = try {
            (call("main", "get-active-modules", emptyMap<String, Any>())
                ?: throw SchoolConnectionException("Could not discover available Schulmanager modules")).also {
                require(it.isArray) { "Unexpected Schulmanager module list" }
            }
        } catch (e: Exception) { return SchoolRecordKind.entries.map { failed(it, e) } }
        val active = modules.map { it.path("name").asText() }.toSet()
        val feeds = mutableListOf<SchoolRecordFeed>()
        feeds += section(SchoolRecordKind.EXAM, "exams" in active) {
            val visible = call("exams", "exams-visible-for-students", emptyMap<String, Any>())
            if (visible?.isBoolean != true || !visible.asBoolean()) return@section SchoolRecordFeed.unavailable(SchoolRecordKind.EXAM, "The school has not made exams visible to parents")
            val data = call("exams", "get-exams", mapOf("student" to mapOf("id" to studentId.toLong()), "start" to from.toString(), "end" to to.toString()))
                ?: return@section SchoolRecordFeed.unavailable(SchoolRecordKind.EXAM, "Exam access is not available for this account")
            require(data.isArray) { "Unexpected Schulmanager exam list" }
            val records = data.map { row ->
                checkStudent(row, studentId)
                val date = LocalDate.parse(row.path("date").asText())
                require(date in from..to) { "Schulmanager exam lies outside the requested window" }
                val subject = row.path("subject").path("name").asText().ifBlank { row.path("subjectText").asText() }
                require(subject.isNotBlank()) { "Exam subject missing" }
                val title = row.path("type").path("name").asText("Exam") + " · " + subject
                SchoolRecord(id(row), SchoolRecordKind.EXAM, title, listOf(title, plain(row.path("comment").asText())).filter(String::isNotBlank).joinToString("\n"),
                    date, time(row.path("startClassHour").path("from")), time(row.path("endClassHour").path("until")),
                    url = "${SchulmanagerSession.BASE_URL}/#/modules/exams/view?studentId=$studentId")
            }
            SchoolRecordFeed(SchoolRecordKind.EXAM, SchoolFeedStatus.AVAILABLE, records, complete = true)
        }
        val incidentKinds = listOf(SchoolRecordKind.FORGOTTEN_HOMEWORK, SchoolRecordKind.FORGOTTEN_MATERIAL)
        if ("classbook" !in active) feeds += incidentKinds.map { unavailable(it) }
        else {
            try {
                val term = call("main", "get-current-term", emptyMap<String, Any>())
                if (term == null || !term.path("id").canConvertToLong()) feeds += incidentKinds.map { unavailable(it) }
                else {
                    val data = call("classbook", "get-entry-statistics", mapOf("student" to mapOf("id" to studentId.toLong()), "termId" to term.path("id").asLong()))
                    if (data == null) feeds += incidentKinds.map { SchoolRecordFeed.unavailable(it, "The school has not granted this account access to classbook reports") }
                    else {
                        require(data.isArray) { "Unexpected Schulmanager classbook report" }
                        val reports = data.flatMap { group ->
                            val name = group.path("name").asText()
                            val kind = when {
                                Regex("(?i)(?:hausaufgaben|\\bha\\b|homework).*(?:vergessen|fehl|forgot|missing|nicht erledigt)|(?:vergessen|fehl|forgot|missing|nicht erledigt).*?(?:hausaufgaben|\\bha\\b|homework)").containsMatchIn(name) -> SchoolRecordKind.FORGOTTEN_HOMEWORK
                                Regex("(?i)(?:material|arbeitsmittel).*(?:vergessen|fehl|forgot|missing|nicht vorhanden)|(?:vergessen|fehl|forgot|missing|nicht vorhanden).*?(?:material|arbeitsmittel)").containsMatchIn(name) -> SchoolRecordKind.FORGOTTEN_MATERIAL
                                else -> null
                            }
                            if (kind == null) {
                                require(!Regex("(?i)hausaufgaben|\\bha\\b|homework|material|arbeitsmittel").containsMatchIn(name)) { "Unrecognized homework/material report group" }
                                emptyList()
                            } else {
                                require(group.path("entries").isArray) { "Classbook report entries missing" }
                                group.path("entries").map { row ->
                                    checkStudent(row, studentId)
                                    val date = LocalDate.parse(row.path("date").asText().take(10))
                                    val subject = row.path("taughtLesson").path("subject").path("name").asText()
                                    SchoolRecord(id(row), kind, name, listOf(name, subject, plain(row.path("text").asText())).filter(String::isNotBlank).joinToString("\n"), date,
                                        url = "${SchulmanagerSession.BASE_URL}/#/modules/classbook/reports2/student/$studentId/entries")
                                }
                            }
                        }
                        feeds += incidentKinds.map { SchoolRecordFeed(it, SchoolFeedStatus.AVAILABLE, reports.filter { row -> row.kind == it }) }
                    }
                }
            } catch (e: Exception) { feeds += incidentKinds.map { failed(it, e) } }
        }
        feeds += section(SchoolRecordKind.PAYMENT, "invoicing" in active) {
            SchulmanagerPayments(call).read(studentId)
        }
        val learning = SchulmanagerLearningFeeds(call)
        feeds += section(SchoolRecordKind.HOMEWORK, "classbook" in active) { learning.homework(studentId) }
        feeds += section(SchoolRecordKind.TIMETABLE_CHANGE, "schedules" in active) { learning.timetable(studentId, from) }
        return feeds
    }

    private fun section(kind: SchoolRecordKind, enabled: Boolean, read: () -> SchoolRecordFeed): SchoolRecordFeed =
        if (!enabled) unavailable(kind) else try { read() } catch (e: Exception) { failed(kind, e) }
    private fun unavailable(kind: SchoolRecordKind) = SchoolRecordFeed.unavailable(kind, "This school has not enabled this section for the account")
    private fun checkStudent(row: JsonNode, studentId: String) {
        if (row.hasNonNull("studentId")) require(row.path("studentId").asText() == studentId) { "Record belongs to a different child" }
    }
    private fun id(row: JsonNode) = row.path("id").asText().takeIf { it.toLongOrNull() != null } ?: throw SchoolConnectionException("School record ID missing")
    private fun time(value: JsonNode): LocalTime? = if (value.isTextual && value.asText().isNotBlank()) LocalTime.parse(value.asText()) else null
    private fun plain(text: String) = Jsoup.parseBodyFragment(text).text()
}
