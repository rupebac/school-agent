package de.schoolagent.connectors.schulmanager

import com.fasterxml.jackson.databind.ObjectMapper
import de.schoolagent.connectors.school.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SchulmanagerAdditionalFeedsTest {
    private val json = ObjectMapper()
    private val identity = SchoolIdentity("1", null, listOf(SchoolStudent("7", "Synthetic Child")))

    @Test fun `messages require family-sharing permission and unambiguous child attribution`() {
        var calls = 0
        val reader = SchulmanagerMessages { _, _, _ -> calls++; null }
        assertThat(reader.read(identity, "7", false, "10").status).isEqualTo(SchoolFeedStatus.UNAVAILABLE)
        assertThat(reader.read(identity.copy(students = identity.students + SchoolStudent("8", "Synthetic Sibling")),
            "7", true, "10").status).isEqualTo(SchoolFeedStatus.UNAVAILABLE)
        assertThat(calls).isZero()
    }

    @Test fun `message import uses read calls only and excludes messages sent by the connected parent`() {
        val calls = mutableListOf<String>()
        val reader = SchulmanagerMessages { module, endpoint, parameters ->
            assertThat(module).isEqualTo("messenger"); calls += endpoint
            if (endpoint == "get-messages-by-subscription")
                assertThat((parameters as Map<*, *>)["subscriptionId"]).isEqualTo("1")
            json.readTree(when (endpoint) {
                "get-subscriptions" -> """[{"id":"1","thread":{"id":20,"subject":"Trip arrangements","lastMessageTimestamp":"2026-10-20T10:00:00Z"}}]"""
                "get-messages-by-subscription" -> """{"hasMoreMessages":false,"messages":[
                    {"id":2,"threadId":20,"sender":{"id":11},"text":"Bring a workbook.","createdAt":"2026-10-20T09:00:00Z","attachments":[]},
                    {"id":3,"threadId":20,"sender":{"id":10},"text":"Thank you.","createdAt":"2026-10-20T10:00:00Z","attachments":[]}]}"""
                else -> error("A write endpoint must never be called")
            })
        }
        val feed = reader.read(identity, "7", true, "10")
        assertThat(feed.status).isEqualTo(SchoolFeedStatus.AVAILABLE)
        assertThat(feed.notices.map { it.id }).containsExactly("message:20:2")
        assertThat(reader.details(feed.notices.single()) { error("No attachment expected") }.documents.single().bytes.decodeToString())
            .contains("Bring a workbook.").doesNotContain("Thank you.")
        assertThat(calls).containsExactly("get-subscriptions", "get-messages-by-subscription")
    }

    @Test fun `homework groups retain school publication date without inventing a due date`() {
        val reader = SchulmanagerLearningFeeds { module, endpoint, _ ->
            assertThat(module).isEqualTo("classbook"); assertThat(endpoint).isEqualTo("get-homework")
            json.readTree("""[{"date":"2026-10-20","subject":"Mathematics","homework":"<p>Workbook page 12.</p>"},
                {"date":"2026-10-20","subject":"Mathematics","homework":"Exercises 1 and 2."}]""")
        }
        val record = reader.homework("7").records.single()
        assertThat(record.date).isEqualTo(LocalDate.of(2026, 10, 20))
        assertThat(record.dueDate).isNull()
        assertThat(record.text).contains("Workbook page 12.", "Exercises 1 and 2.")
    }

    @Test fun `normal lesson observations can restore an existing exception with the same identity`() {
        var changed = true
        val reader = SchulmanagerLearningFeeds { module, endpoint, _ ->
            assertThat(module).isEqualTo("schedules"); assertThat(endpoint).isEqualTo("get-actual-lessons")
            json.readTree(if (changed) """[{"date":"2026-10-20","classHour":{"id":2},"isCancelled":true,
                "actualLesson":null,"originalLessons":[{"id":9,"subject":{"name":"Mathematics"}}]}]"""
            else """[{"date":"2026-10-20","classHour":{"id":2},"isCancelled":false,
                "actualLesson":{"id":9,"subject":{"name":"Mathematics"}},"originalLessons":[]}]""")
        }
        val before = reader.timetable("7", LocalDate.of(2026, 10, 20)).records.single()
        changed = false
        val after = reader.timetable("7", LocalDate.of(2026, 10, 20)).records.single()
        assertThat(before.id).isEqualTo(after.id)
        assertThat(before.timetable?.cancelled).isTrue()
        assertThat(after.timetable?.changed).isFalse()
        assertThat(after.time).isNull()
    }

    @Test fun `invoice history proves settlement and widget partial amounts remain exact`() {
        val reader = SchulmanagerPayments { module, endpoint, parameters ->
            json.readTree(when (endpoint) {
                "poqa" -> {
                    assertThat(module).isEqualTo("main")
                    assertThat(parameters.toString()).contains("modules/invoicing/general-invoice", "findAll")
                    """[{"id":1,"title":"School materials","items":[{"studentItems":[{"studentId":7,"amount":"17.20"}]}],
                        "studentInvoices":[{"id":30,"studentId":7,"paid":true}]}]"""
                }
                "get-unpaid-invoices-for-widget" -> """[{"id":31,"studentId":7,"sum":"25.50","paidSum":"10.25",
                    "generalInvoice":{"title":"Trip fee"}}]"""
                else -> error("Unexpected endpoint")
            })
        }
        val feed = reader.read("7")
        assertThat(feed.records.single { it.id == "30" }.payment?.status).isEqualTo(SchoolPaymentStatus.SETTLED)
        val partial = feed.records.single { it.id == "31" }.payment!!
        assertThat(partial.status).isEqualTo(SchoolPaymentStatus.PARTIAL)
        assertThat(partial.remainingMinor).isEqualTo(1525L)
    }

    @Test fun `unavailable invoice history never turns widget absence into settlement`() {
        val reader = SchulmanagerPayments { _, endpoint, _ -> if (endpoint == "poqa") null else json.readTree("[]") }
        val feed = reader.read("7")
        assertThat(feed.records).isEmpty()
        assertThat(feed.complete).isFalse()
        assertThat(feed.message).contains("cannot be inferred")
    }

    @Test fun `unknown inconsistent or overpaid amounts are never treated as a remaining payment`() {
        for ((total, paid) in listOf("1,00" to "0", "10" to "11", "-2" to "0", "1e4" to "0"))
            assertThat(SchoolPayment.observed(total, paid).status).isEqualTo(SchoolPaymentStatus.UNKNOWN)
    }
}
