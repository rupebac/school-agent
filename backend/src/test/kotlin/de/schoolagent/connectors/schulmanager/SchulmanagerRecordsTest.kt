package de.schoolagent.connectors.schulmanager

import com.fasterxml.jackson.databind.ObjectMapper
import de.schoolagent.connectors.school.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SchulmanagerRecordsTest {
    private val json = ObjectMapper()
    private val from = LocalDate.of(2026, 10, 1)
    private val to = LocalDate.of(2027, 8, 31)
    @Test fun `reads selected child exams with native IDs and authoritative date and class-hour times`() {
        val calls = mutableListOf<Triple<String, String, Any>>()
        val reader = SchulmanagerRecords { module, endpoint, params ->
            calls += Triple(module, endpoint, params)
            json.readTree(when (endpoint) {
                "get-active-modules" -> """[{"name":"exams"}]"""
                "exams-visible-for-students" -> "true"
                "get-exams" -> """[{"id":123,"date":"2026-11-20","subject":{"name":"Mathematik"},"type":{"name":"Schulaufgabe"},"startClassHour":{"from":"09:45:00"},"endClassHour":null}]"""
                "get-homework" -> "[]"
            else -> error("Unexpected request")
            })
        }
        val feeds = reader.read("76", from, to)
        val exam = feeds.single { it.kind == SchoolRecordKind.EXAM }
        assertThat(exam.complete).isTrue()
        assertThat(exam.records.single().id).isEqualTo("123")
        assertThat(exam.records.single().time.toString()).isEqualTo("09:45")
        assertThat(exam.records.single().endTime).isNull()
        assertThat(calls.single { it.second == "get-exams" }.third.toString()).contains("student={id=76}", "start=2026-10-01", "end=2027-08-31")
        assertThat(feeds.filter { it.kind != SchoolRecordKind.EXAM }).allMatch { it.status == SchoolFeedStatus.UNAVAILABLE }
    }
    @Test fun `disabled or denied modules never produce authoritative empty snapshots`() {
        val reader = SchulmanagerRecords { _, endpoint, _ -> when (endpoint) {
            "get-active-modules" -> json.readTree("""[{"name":"classbook"},{"name":"exams"}]""")
            "exams-visible-for-students" -> json.readTree("true")
            "get-exams", "get-entry-statistics" -> null
            "get-current-term" -> json.readTree("""{"id":7}""")
            "get-homework" -> null
            else -> error("Unexpected request")
        } }
        assertThat(reader.read("76", from, to)).allMatch { it.status == SchoolFeedStatus.UNAVAILABLE && !it.complete }
    }
    @Test fun `classbook reports and invoices are isolated from siblings and creation dates are not deadlines`() {
        val reader = SchulmanagerRecords { _, endpoint, _ -> json.readTree(when (endpoint) {
            "get-active-modules" -> """[{"name":"classbook"},{"name":"invoicing"}]"""
            "get-current-term" -> """{"id":7}"""
            "get-entry-statistics" -> """[{"name":"Hausaufgaben vergessen","entries":[{"id":20,"studentId":76,"date":"2026-10-09","text":"Übungsblatt","taughtLesson":{"subject":{"name":"Mathematik"}}}]},{"name":"Material vergessen","entries":[]}]"""
            "get-unpaid-invoices-for-widget" -> """[{"id":30,"studentId":76,"sum":"17.20","paidSum":"0","createdAt":"2026-10-01","generalInvoice":{"title":"Kopiergeld","dueDate":"2026-10-15"}},{"id":31,"studentId":99,"sum":"100","generalInvoice":{"title":"Sibling invoice"}}]"""
            "get-homework" -> "[]"
            else -> error("Unexpected request")
        }) }
        val feeds = reader.read("76", from, to)
        assertThat(feeds.single { it.kind == SchoolRecordKind.FORGOTTEN_HOMEWORK }.records.single().date).isEqualTo(LocalDate.of(2026, 10, 9))
        val payments = feeds.single { it.kind == SchoolRecordKind.PAYMENT }
        assertThat(payments.records).hasSize(1)
        assertThat(payments.records.single().text).contains("17.20 EUR").doesNotContain("Sibling")
        assertThat(payments.complete).isFalse()
    }
    @Test fun `foreign child or malformed record makes the section fail without discarding existing events`() {
        val reader = SchulmanagerRecords { _, endpoint, _ -> json.readTree(when (endpoint) {
            "get-active-modules" -> """[{"name":"exams"}]"""
            "exams-visible-for-students" -> "true"
            "get-exams" -> """[{"id":123,"studentId":99,"date":"2026-11-20","subject":{"name":"Math"}}]"""
            "get-homework" -> "[]"
            else -> error("Unexpected request")
        }) }
        val feed = reader.read("76", from, to).single { it.kind == SchoolRecordKind.EXAM }
        assertThat(feed.status).isEqualTo(SchoolFeedStatus.FAILED)
        assertThat(feed.complete).isFalse()
    }
    @Test fun `module discovery failure returns failed sections rather than aborting letter orchestration`() {
        val reader = SchulmanagerRecords { _, _, _ -> throw SchoolConnectionException("unavailable") }
        assertThat(reader.read("76", from, to)).hasSize(SchoolRecordKind.entries.size).allMatch { it.status == SchoolFeedStatus.FAILED && !it.complete }
    }
    @Test fun `common missing homework labels work and unfamiliar relevant groups fail visibly`() {
        fun reader(name: String) = SchulmanagerRecords { _, endpoint, _ -> json.readTree(when (endpoint) {
            "get-active-modules" -> """[{"name":"classbook"}]"""
            "get-current-term" -> """{"id":7}"""
            "get-entry-statistics" -> """[{"name":"$name","entries":[{"id":20,"date":"2026-10-09","text":"Worksheet"}]}]"""
            "get-homework" -> "[]"
            else -> error("Unexpected request")
        }) }
        for (name in listOf("HA vergessen", "Hausaufgaben nicht erledigt")) {
            assertThat(reader(name).read("76", from, to).single { it.kind == SchoolRecordKind.FORGOTTEN_HOMEWORK }.records).hasSize(1)
        }
        assertThat(reader("Hausaufgaben Bemerkung").read("76", from, to).single { it.kind == SchoolRecordKind.FORGOTTEN_HOMEWORK }.status).isEqualTo(SchoolFeedStatus.FAILED)
    }
}
