package de.schoolagent.whatsapp

import de.schoolagent.domain.enums.*
import de.schoolagent.domain.model.*
import de.schoolagent.domain.repo.*
import de.schoolagent.inbox.InboxCopy
import de.schoolagent.inbox.InboxCopyService
import de.schoolagent.diagnostics.*
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class NotificationServiceTest {
    private val users = mockk<AppUserRepository>(relaxed = true)
    private val kids = mockk<ChildRepository>(relaxed = true)
    private val actions = mockk<ParentActionRepository>(relaxed = true)
    private val events = mockk<SchoolEventRepository>(relaxed = true)
    private val cases = mockk<SchoolCaseRepository>(relaxed = true)
    private val config = mockk<WhatsAppConfigService>(relaxed = true)
    private val copy = mockk<InboxCopyService>(relaxed = true)
    private val outbox = mockk<WhatsappOutbox>(relaxed = true)
    private val inbox = mockk<de.schoolagent.domain.service.FamilyInboxService>(relaxed = true)
    private val diagnostics = mockk<IntegrationDiagnostics>(relaxed = true)
    private val parent = AppUser(name = "morgan", displayName = "Morgan", passwordHash = "x", whatsappNumber = "+49123")
    private val clock = Clock.fixed(Instant.parse("2026-10-02T16:00:00Z"), ZoneOffset.UTC)
    private val service = NotificationService(config, mockk(relaxed = true), actions, events, cases, users, kids, clock, mockk(relaxed = true), inbox, copy, outbox, diagnostics)
    private fun case(score: Int = 4) = SchoolCase(title = "School copying fee by 5 October", summary = "Prepare €17.20", importanceScore = score, status = CaseStatus.ACTION_REQUIRED)
    init {
        every { cases.save(any<SchoolCase>()) } answers { firstArg() }
        every { config.recipients() } returns listOf(parent to "+49123")
        every { kids.findById(any()) } returns Optional.empty()
    }
    @Test fun `historical imports and muted closed quiet or disabled notifications stay quiet`() {
        service.notifyDecision(case().apply { suppressInitialNotification = true })
        service.notifyDecision(case().apply { muted = true })
        service.notifyDecision(case().apply { status = CaseStatus.COMPLETED })
        service.notifyDecision(case(1))
        parent.instantEnabled = false
        service.notifyDecision(case())
        verify(exactly = 0) { outbox.enqueue(any(), any(), any(), any(), any(), any(), any()) }
        listOf(DiagnosticOutcome.QUIET_IMPORT, DiagnosticOutcome.MUTED, DiagnosticOutcome.CLOSED,
            DiagnosticOutcome.LOW_PRIORITY, DiagnosticOutcome.NO_RECIPIENTS).forEach { outcome ->
            verify { diagnostics.record(match { it.stage == DiagnosticStage.NOTICE && it.outcome == outcome }) }
        }
    }
    @Test fun `uses saved concise copy with each structured date and obligation and durable revision key`() {
        val c = case()
        val a = ParentAction(caseId = c.id, title = "Pay copying fee by 5 October", dueAt = Instant.parse("2026-10-05T00:00:00Z"), dedupKey = "a")
        every { actions.findByCaseId(c.id) } returns listOf(a)
        every { copy.current(listOf(c)) } returns mapOf(c.id to InboxCopy("Copying fee", "Use a labelled envelope.", mapOf(a.id.toString() to "Prepare €17.20")))
        every { inbox.revision(c.id) } returns "facts-v1"
        val message = slot<WaTemplates.Message>()
        service.notifyDecision(c)
        verify { outbox.enqueue(parent.id, match { it.contains("facts-v1") }, capture(message), c.id, NotificationLevel.IMMEDIATE, any(), any()) }
        assertThat(message.captured.params).containsExactly("Copying fee", "Use a labelled envelope; By 05.10.2026: Prepare €17.20")
        assertThat(message.captured.params).doesNotContain(a.title)
    }
    @Test fun `new letter generates one shared persisted reminder before either parent is queued`() {
        val c = case()
        val a = ParentAction(caseId = c.id, title = "Buy the class book", dueAt = Instant.parse("2026-10-05T00:00:00Z"), dedupKey = "book")
        every { actions.findByCaseId(c.id) } returns listOf(a)
        val wife = AppUser(name = "casey", displayName = "Casey", passwordHash = "x", whatsappNumber = "+49456")
        every { config.recipients() } returns listOf(parent to "+49123", wife to "+49456")
        val projection = InboxCopy("Reading book", null, mapOf(a.id.toString() to "Buy the class book"),
            mapOf("2026-10-05" to de.schoolagent.inbox.ReminderCopy("Buy the class reading book", setOf("action:${a.id}"))))
        every { copy.current(listOf(c)) } returns emptyMap() andThen mapOf(c.id to projection)
        every { copy.refresh(c.id) } returns true
        service.notifyDecision(c)
        verify(exactly = 1) { copy.refresh(c.id) }
        verify(exactly = 2) { outbox.enqueue(any(), any(), match { it.params.contains("By 05.10.2026: Buy the class reading book") }, c.id, any(), any(), any()) }
    }

    @Test fun `information never includes stale actions and uses information layout`() {
        val c = case().apply { informationOnly = true }
        every { actions.findByCaseId(c.id) } returns listOf(ParentAction(caseId = c.id, title = "Stale task", dedupKey = "x"))
        val message = slot<WaTemplates.Message>()
        service.notifyDecision(c)
        verify { outbox.enqueue(any(), any(), capture(message), c.id, any(), any(), any()) }
        assertThat(message.captured.name).contains("info")
        assertThat(message.captured.params).doesNotContain("Stale task")
    }
    @Test fun `a meeting notice leads with its purpose and keeps the date without crowding it with the address`() {
        val c = case().apply { informationOnly = true }
        val start = clock.instant().plusSeconds(3600)
        every { events.findByCaseId(c.id) } returns listOf(SchoolEvent(caseId = c.id, title = "Parents' evening",
            eventType = EventType.PARENT_EVENT, startTime = start, location = "School hall", dedupKey = "meeting"))
        every { copy.current(listOf(c)) } returns mapOf(c.id to InboxCopy("Parents' evening", "Continue in the classroom at 20:00.", emptyMap()))
        val message = slot<WaTemplates.Message>()
        service.notifyDecision(c)
        verify { outbox.enqueue(parent.id, any(), capture(message), c.id, any(), any(), start) }
        assertThat(message.captured.params).containsExactly("Parents' evening",
            "Continue in the classroom at 20:00; On 02.10.2026, 19:00")
        assertThat(message.captured.name).isEqualTo(WaTemplates.name(WaTemplates.Kind.INFO, 1))
    }
    @Test fun `a started meeting does not trigger a new information notice or copy generation`() {
        val c = case().apply { informationOnly = true }
        every { events.findByCaseId(c.id) } returns listOf(SchoolEvent(caseId = c.id, title = "Parents' evening",
            eventType = EventType.PARENT_EVENT, startTime = clock.instant(), dedupKey = "past"))
        service.notifyDecision(c)
        verify(exactly = 0) { outbox.enqueue(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { copy.refresh(any()) }
    }
    @Test fun `event and registration deadline remain one matter with clearly different date roles`() {
        val c = case()
        val a = ParentAction(caseId = c.id, title = "Register for the evening", dueAt = Instant.parse("2026-10-12T22:00:00Z"), dedupKey = "register")
        every { actions.findByCaseId(c.id) } returns listOf(a)
        every { events.findByCaseId(c.id) } returns listOf(SchoolEvent(caseId = c.id, title = "Parents' evening",
            eventType = EventType.PARENT_EVENT, startTime = Instant.parse("2026-10-16T16:00:00Z"), location = "Family centre", dedupKey = "evening"))
        every { copy.current(listOf(c)) } returns mapOf(c.id to InboxCopy("Parents' evening", "Speaker: a psychologist.",
            mapOf(a.id.toString() to "Register by phone or email"),
            activity = "A talk about understanding attention difficulties and supporting children."))
        val message = slot<WaTemplates.Message>()
        service.notifyDecision(c)
        verify { outbox.enqueue(parent.id, any(), capture(message), c.id, any(), any(), any()) }
        assertThat(message.captured.params).containsExactly("Parents' evening",
            "A talk about understanding attention difficulties and supporting children; On 16.10.2026, 18:00; By 13.10.2026: Register by phone or email")
        assertThat(message.captured.name).isEqualTo(WaTemplates.name(WaTemplates.Kind.ACTION, 1))
    }
    @Test fun `date-only information remains useful until local midnight`() {
        val c = case().apply { informationOnly = true }
        every { events.findByCaseId(c.id) } returns listOf(SchoolEvent(caseId = c.id, title = "School closure",
            eventType = EventType.SCHOOL_EVENT, startTime = Instant.parse("2026-10-01T22:00:00Z"), allDay = true, dedupKey = "day"))
        service.notifyDecision(c)
        verify { outbox.enqueue(parent.id, any(), any(), c.id, any(), any(), Instant.parse("2026-10-02T22:00:00Z")) }
    }
    @Test fun `passed event does not swallow an outstanding actionable duty`() {
        val c = case()
        every { events.findByCaseId(c.id) } returns listOf(SchoolEvent(caseId = c.id, title = "Past meeting",
            eventType = EventType.PARENT_EVENT, startTime = clock.instant().minusSeconds(60), location = "Old hall", dedupKey = "past"))
        every { actions.findByCaseId(c.id) } returns listOf(ParentAction(caseId = c.id, title = "Return form", dedupKey = "form"))
        val message = slot<WaTemplates.Message>()
        service.notifyDecision(c)
        verify { outbox.enqueue(parent.id, any(), capture(message), c.id, any(), any(), any()) }
        assertThat(message.captured.params.joinToString()).contains("Return form").doesNotContain("Old hall")
    }
    @Test fun `a newly received forgotten homework report is sent despite its historical incident date`() {
        val c = case().apply { informationOnly = true; summary = "The school reports a missing mathematics worksheet." }
        every { events.findByCaseId(c.id) } returns listOf(SchoolEvent(caseId = c.id, title = "Forgotten homework",
            eventType = EventType.SCHOOL_REPORT, startTime = Instant.parse("2026-10-01T22:00:00Z"), allDay = true, dedupKey = "report"))
        val message = slot<WaTemplates.Message>()
        service.notifyDecision(c)
        verify { outbox.enqueue(parent.id, any(), capture(message), c.id, NotificationLevel.IMMEDIATE, any(), any()) }
        assertThat(message.captured.params.joinToString()).contains("Reported on 02.10.2026").doesNotContain("By ")
        assertThat(message.captured.name).isEqualTo(WaTemplates.name(WaTemplates.Kind.INFO, 1))
    }
    @Test fun `spanish account selects Spanish template for background durable translation`() {
        parent.language = "es"
        val message = slot<WaTemplates.Message>()
        service.notifyDecision(case())
        verify { outbox.enqueue(parent.id, any(), capture(message), any(), any(), any(), any()) }
        assertThat(message.captured.language).isEqualTo("es")
        assertThat(message.captured.localized).isFalse()
    }
    @Test fun `cancelled and moved exams reuse the approved information template with one clear entry`() {
        val c = case().apply { status = CaseStatus.CANCELLED; title = "Mathematics exam" }
        every { inbox.revision(c.id) } returns "withdrawn-facts"
        val message = slot<WaTemplates.Message>()
        service.notifySchoolChange(c, "Cancelled", "Removed from the school's exam timetable. Previous date: 20.11.2026.")
        verify { outbox.enqueue(parent.id, match { it.contains("withdrawn-facts") }, capture(message), c.id, NotificationLevel.IMMEDIATE, any(), any()) }
        assertThat(message.captured.name).isEqualTo(WaTemplates.name(WaTemplates.Kind.INFO, 1))
        assertThat(message.captured.params.joinToString()).contains("Cancelled", "Mathematics exam", "20.11.2026")
        verify(exactly = 0) { copy.refresh(any()) }
    }
}
