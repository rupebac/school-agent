package de.schoolagent.ingestion

import de.schoolagent.ai.ModelRouter
import de.schoolagent.connectors.school.*
import io.mockk.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SchoolRecordInterpreterTest {
    private val model = mockk<ModelRouter>()
    private val service = SchoolRecordInterpreter(model)
    private val record = SchoolRecord("payment-1", SchoolRecordKind.PAYMENT, "Kopiergeld", "Erstellt am 05.10.2026. Kopiergeld: 17,20 EUR.", url = "https://school.example/payments")
    @Test fun `AI cannot turn a payment creation date into a deadline or change an authoritative deadline`() {
        every { model.completeValidated(any(), any(), any(), any(), any()) } returns """{"title":"School copying fee","summary":"Pay €17.20.","action":"Pay €17.20","dueDate":"2026-10-05","dueEvidence":"Erstellt am 05.10.2026"}"""
        assertThat(service.interpret(record).dueDate).isNull()
        assertThat(service.interpret(record.copy(dueDate = LocalDate.of(2026, 10, 15))).dueDate).isEqualTo(LocalDate.of(2026, 10, 15))
    }
    @Test fun `a literal explicit deadline can be extracted from an unfamiliar payment column`() {
        val payment = record.copy(text = "Kopiergeld: 17,20 EUR. Zahlbar bis 15.10.2026.")
        every { model.completeValidated(any(), any(), any(), any(), any()) } returns """{"title":"School copying fee","summary":"Pay €17.20.","action":"Pay €17.20","dueDate":"2026-10-15","dueEvidence":"Zahlbar bis 15.10.2026"}"""
        assertThat(service.interpret(payment).dueDate).isEqualTo(LocalDate.of(2026, 10, 15))
    }
    @Test fun `the language model cannot add tasks or deadlines to exams and forgotten reports`() {
        every { model.completeValidated(any(), any(), any(), any(), any()) } returns """{"title":"Forgotten mathematics homework","summary":"A worksheet was missing.","action":"Finish it tomorrow","dueDate":"2026-10-15","dueEvidence":"deadline 2026-10-15"}"""
        val report = service.interpret(record.copy(kind = SchoolRecordKind.FORGOTTEN_HOMEWORK, date = LocalDate.of(2026, 10, 9)))
        assertThat(report.action).isNull(); assertThat(report.dueDate).isNull()
    }
    @Test fun `localized or unreadable AI deadlines remain safe and native dates still win`() {
        every { model.completeValidated(any(), any(), any(), any(), any()) } returns """{"title":"School copying fee","summary":"Pay €17.20.","action":"Pay €17.20","dueDate":"15.10.2026","dueEvidence":"Zahlbar bis 15.10.2026"}"""
        assertThat(service.interpret(record.copy(text = "Zahlbar bis 15.10.2026")).dueDate).isEqualTo(LocalDate.of(2026, 10, 15))
        every { model.completeValidated(any(), any(), any(), any(), any()) } returns """{"title":"School copying fee","summary":"Pay €17.20.","action":"Pay €17.20","dueDate":"not a date"}"""
        assertThat(service.interpret(record).dueDate).isNull()
        assertThat(service.interpret(record.copy(dueDate = LocalDate.of(2026, 10, 20))).dueDate).isEqualTo(LocalDate.of(2026, 10, 20))
    }
    @Test fun `AI outage or invalid copy does not hide an authoritative exam`() {
        val exam = record.copy(kind = SchoolRecordKind.EXAM, title = "Mathematik", date = LocalDate.of(2026, 11, 20))
        every { model.completeValidated(any(), any(), any(), any(), any()) } throws de.schoolagent.ai.AiUnavailableException("unavailable")
        assertThat(service.interpret(exam).title).contains("Mathematik")
        assertThat(service.interpret(exam).ai).isFalse()
        every { model.completeValidated(any(), any(), any(), any(), any()) } returns """{"title":"","summary":""}"""
        assertThat(service.interpret(exam).ai).isFalse()
    }
    @Test fun `deadline evidence needs actual deadline words rather than incidental word fragments`() {
        every { model.completeValidated(any(), any(), any(), any(), any()) } returns """{"title":"Copying fee","summary":"Pay the fee.","action":"Pay copying fee","dueDate":"2026-10-05","dueEvidence":"Bisher erstellt am 05.10.2026"}"""
        assertThat(service.interpret(record.copy(text = "Bisher erstellt am 05.10.2026")).dueDate).isNull()
    }

    @Test fun `opaque timetable annotations never reach translation while the original source is retained`() {
        val input = slot<String>()
        every { model.completeValidated(any(), any(), capture(input), any(), any()) } returns
            """{"title":"German exam","summary":"A scheduled German assessment."}"""
        for (code in listOf("BUS", "XYZ", "ÄB-7", "AB/CD")) {
            val exam = record.copy(kind = SchoolRecordKind.EXAM, title = "Schulaufgabe in Deutsch ($code)",
                text = "01.12.2026 · Schulaufgabe in Deutsch ($code)", date = LocalDate.of(2026, 12, 1))
            val copy = service.interpret(exam)
            val sent = com.fasterxml.jackson.databind.ObjectMapper().readTree(input.captured)
            assertThat(sent.path("title").asText()).isEqualTo("Schulaufgabe in Deutsch")
            assertThat(sent.path("sourceText").asText()).isEqualTo("01.12.2026 · Schulaufgabe in Deutsch")
            assertThat(copy.title).isEqualTo("German exam")
            assertThat(exam.title).contains("($code)")
            assertThat(exam.text).contains("($code)")
        }
    }

    @Test fun `meaningful parenthetical text and acronyms in other kinds remain part of translation`() {
        val input = slot<String>()
        every { model.completeValidated(any(), any(), capture(input), any(), any()) } returns
            """{"title":"School report","summary":"The original school information."}"""
        service.interpret(record.copy(kind = SchoolRecordKind.EXAM, title = "Deutsch (erhöhtes Niveau)", text = "Deutsch (erhöhtes Niveau)"))
        assertThat(input.captured).contains("erhöhtes Niveau")
        service.interpret(record.copy(kind = SchoolRecordKind.FORGOTTEN_HOMEWORK, text = "Information zu ADHS (ADHD)."))
        assertThat(input.captured).contains("ADHS (ADHD)")
    }

    @Test fun `invented parenthetical exam title is rejected before it can be cached or displayed`() {
        every { model.completeValidated(any(), any(), any(), any(), any()) } answers {
            val answer = """{"title":"German exam (Invented School Track)","summary":"A German assessment."}"""
            lastArg<(String) -> Unit>().invoke(answer)
            answer
        }
        val copy = service.interpret(record.copy(kind = SchoolRecordKind.EXAM, title = "Deutsch (XYZ)", text = "Deutsch (XYZ)"))
        assertThat(copy.ai).isFalse()
        assertThat(copy.title).doesNotContain("Invented School Track")
        assertThat(copy.summary).doesNotContain("Invented School Track")
    }
}
