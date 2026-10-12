package de.schoolagent.whatsapp

import com.fasterxml.jackson.databind.ObjectMapper
import de.schoolagent.ai.ModelRouter
import de.schoolagent.domain.enums.*
import de.schoolagent.domain.model.*
import de.schoolagent.domain.repo.*
import de.schoolagent.health.HealthTracker
import de.schoolagent.i18n.UserLanguage
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.*
import java.util.Optional

class WhatsappOutboxTest {
    private val rows = mockk<NotificationRepository>()
    private val receipts = mockk<WhatsappReceiptRepository>()
    private val users = mockk<AppUserRepository>()
    private val cases = mockk<SchoolCaseRepository>()
    private val config = mockk<WhatsAppConfigService>()
    private val templates = mockk<WhatsAppTemplateSync>()
    private val client = mockk<WhatsappClient>()
    private val model = mockk<ModelRouter>()
    private val health = mockk<HealthTracker>(relaxed = true)
    private val now = Instant.parse("2026-10-02T10:00:00Z")
    private var currentTime = now
    private val clock = object : Clock() {
        override fun instant() = currentTime
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private val saved = mutableListOf<Notification>()
    private val received = mutableMapOf<String, WhatsappReceipt>()
    private val parent = AppUser(name = "morgan", displayName = "Morgan", passwordHash = "x", whatsappNumber = "+49123")
    private val inbox = mockk<de.schoolagent.domain.service.FamilyInboxService>(relaxed = true)
    private lateinit var outbox: WhatsappOutbox
    @BeforeEach fun setup() {
        outbox = WhatsappOutbox(rows, receipts, users, cases, config, templates, client, model, health, clock, inbox)
        every { config.config() } returns WhatsAppConfigService.Config("https://graph.facebook.com/v25.0", "phone", "waba", "t", null, null, "https://school.example.org")
        every { config.isConfigured() } returns true
        every { rows.existsByDedupKey(any()) } answers { saved.any { it.dedupKey == firstArg<String>() } }
        every { rows.save(any<Notification>()) } answers { firstArg<Notification>().also { if (it !in saved) saved += it } }
        every { rows.saveAndFlush(any<Notification>()) } answers { firstArg<Notification>().also { if (it !in saved) saved += it } }
        every { rows.findByMessageId(any()) } answers { saved.firstOrNull { it.messageId == firstArg<String>() } }
        every { users.findById(parent.id) } returns Optional.of(parent)
        every { receipts.findById(any()) } answers { Optional.ofNullable(received[firstArg<String>()]) }
        every { receipts.saveAndFlush(any<WhatsappReceipt>()) } answers { firstArg<WhatsappReceipt>().also { received[it.messageId] = it } }
        every { templates.ready(any()) } returns true
        every { client.sendTemplate(any(), any(), any(), any(), any(), any()) } returns "wamid.1"
    }
    private fun message(language: UserLanguage = UserLanguage.EN) = WaTemplates.messages(WaTemplates.Kind.PLAN, language, "Jamie", "Tomorrow", "School plan", listOf("5 Oct · Prepare €17.20"), "calendar?kid=jamie").single()
    private fun queue(language: UserLanguage = UserLanguage.EN): Notification {
        outbox.enqueue(parent.id, "briefing:day", message(language))
        return saved.single()
    }
    private fun status(state: String, code: Int? = null) = ObjectMapper().readTree("""{"id":"wamid.1","status":"$state","errors":[{"code":${code ?: 0},"title":"Not delivered"}]}""")
    @Test fun `dedup persists before sending and acceptance is distinct from delivery`() {
        val row = queue()
        outbox.enqueue(parent.id, "briefing:day", message())
        assertThat(saved).hasSize(1)
        verify(exactly = 0) { client.sendTemplate(any(), any(), any(), any(), any(), any()) }
        outbox.send(row)
        assertThat(row.status).isEqualTo(NotificationStatus.ACCEPTED)
        assertThat(row.messageId).isEqualTo("wamid.1")
        assertThat(row.nextAttemptAt).isNull()
        assertThat(row.message).isEqualTo(WaTemplates.render(message(), config.config().frontendUrl))
        outbox.record(status("delivered"))
        assertThat(row.status).isEqualTo(NotificationStatus.DELIVERED)
        outbox.record(status("read")); outbox.record(status("sent")); outbox.record(status("failed", 131000))
        assertThat(row.status).isEqualTo(NotificationStatus.READ)
        assertThat(row.lastError).isNull()
    }
    @Test fun `header and child survive a durable queue round trip and are sent separately`() {
        val message = message().copy(childId = "jamie")
        val restored = outbox.decode(outbox.encode(message))
        assertThat(restored).isEqualTo(message)
        outbox.enqueue(parent.id, "briefing:header", restored.copy(childId = null))
        outbox.send(saved.single())
        verify { client.sendTemplate(any(), message.name, message.language, message.params, message.suffix, "Tomorrow for Jamie") }
        val legacy = outbox.decode("""{"name":"school_agent_plan_v3_1_en","language":"en_US","params":["Jamie","Tomorrow","Plan","Bring the book"],"suffix":"calendar"}""")
        assertThat(legacy.header).isNull()
        assertThat(legacy.params).containsExactly("Jamie", "Tomorrow", "Plan", "Bring the book")
    }

    @Test fun `unapproved templates hold a durable queue without consuming send attempts`() {
        every { templates.ready(any()) } returns false
        val row = queue(); outbox.send(row)
        assertThat(row.status).isEqualTo(NotificationStatus.QUEUED)
        assertThat(row.attemptCount).isZero()
        assertThat(row.nextAttemptAt).isAfter(now)
        verify(exactly = 0) { client.sendTemplate(any(), any(), any(), any(), any(), any()) }
    }
    @Test fun `approval after the event cutoff skips the notice without sending or translating`() {
        val cutoff = now.plusSeconds(60)
        outbox.enqueue(parent.id, "briefing:cutoff", message(UserLanguage.ES), expiresAt = cutoff)
        val row = saved.single()
        every { templates.ready(any()) } returns false
        outbox.send(row)
        assertThat(row.status).isEqualTo(NotificationStatus.QUEUED)
        assertThat(row.expiresAt).isEqualTo(cutoff)
        currentTime = cutoff
        every { templates.ready(any()) } returns true
        outbox.send(row)
        assertThat(row.status).isEqualTo(NotificationStatus.SKIPPED)
        assertThat(row.attemptCount).isZero()
        verify(exactly = 0) { client.sendTemplate(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { model.complete(any(), any(), any(), any()) }
    }
    @Test fun `translation that crosses the cutoff never sends a stale notice`() {
        every { model.complete(ModelRole.TEXT, any(), any(), any()) } answers {
            currentTime = now.plusSeconds(120)
            """{"texts":["Mañana","5 oct · Preparar €17.20"]}"""
        }
        outbox.enqueue(parent.id, "briefing:cutoff", message(UserLanguage.ES), expiresAt = now.plusSeconds(60))
        outbox.send(saved.single())
        assertThat(saved.single().status).isEqualTo(NotificationStatus.SKIPPED)
        verify(exactly = 0) { client.sendTemplate(any(), any(), any(), any(), any(), any()) }
    }
    @Test fun `expired absolute cutoff is not extended by preparation or queue time`() {
        val cutoff = now.minusSeconds(60)
        outbox.enqueue(parent.id, "briefing:late", message(), expiresAt = cutoff)
        val row = saved.single()
        assertThat(row.expiresAt).isEqualTo(cutoff)
        outbox.send(row)
        assertThat(row.status).isEqualTo(NotificationStatus.SKIPPED)
    }
    @Test fun `send timestamp records acceptance after preparation and the HTTP request`() {
        every { client.sendTemplate(any(), any(), any(), any(), any(), any()) } answers {
            currentTime = now.plusSeconds(5)
            "wamid.1"
        }
        val row = queue()
        outbox.send(row)
        assertThat(row.sentAt).isEqualTo(now.plusSeconds(5))
    }
    @Test fun `known transient errors retry but uncertain network outcomes never blindly resend`() {
        every { client.sendTemplate(any(), any(), any(), any(), any(), any()) } throws WhatsappSendException("Rate limit", retryable = true)
        val row = queue(); outbox.send(row)
        assertThat(row.status).isEqualTo(NotificationStatus.FAILED)
        assertThat(row.nextAttemptAt).isAfter(now)
        every { client.sendTemplate(any(), any(), any(), any(), any(), any()) } throws WhatsappSendException("Timeout", ambiguous = true)
        outbox.send(row)
        assertThat(row.status).isEqualTo(NotificationStatus.UNKNOWN)
        assertThat(row.nextAttemptAt).isNull()
    }
    @Test fun `delivery failures persist and retry only known temporary non-delivery codes`() {
        val row = queue(); outbox.send(row); outbox.record(status("failed", 131016))
        assertThat(row.status).isEqualTo(NotificationStatus.FAILED)
        assertThat(row.lastError).contains("131016")
        assertThat(row.nextAttemptAt).isAfter(now)
        outbox.record(status("sent"))
        assertThat(row.status).isEqualTo(NotificationStatus.FAILED)
    }
    @Test fun `permanent failure and exhausted attempts have no automatic retry`() {
        val row = queue(); outbox.send(row); outbox.record(status("failed", 131026))
        assertThat(row.nextAttemptAt).isNull()
        row.status = NotificationStatus.QUEUED; row.attemptCount = 4
        every { client.sendTemplate(any(), any(), any(), any(), any(), any()) } throws WhatsappSendException("Temporary", retryable = true)
        outbox.send(row)
        assertThat(row.attemptCount).isEqualTo(5)
        assertThat(row.nextAttemptAt).isNull()
    }
    @Test fun `expired briefings and disabled preferences are skipped`() {
        val row = queue(); row.expiresAt = now.minusSeconds(1); outbox.send(row)
        assertThat(row.status).isEqualTo(NotificationStatus.SKIPPED)
        row.status = NotificationStatus.QUEUED; row.expiresAt = now.plusSeconds(60); parent.briefingEnabled = false
        outbox.send(row)
        assertThat(row.status).isEqualTo(NotificationStatus.SKIPPED)
        verify(exactly = 0) { client.sendTemplate(any(), any(), any(), any(), any(), any()) }
    }
    @Test fun `school corrections completion and withdrawn preferences cancel pending sends`() {
        val c = SchoolCase(title = "Letter", importanceScore = 4, status = CaseStatus.ACTION_REQUIRED)
        every { cases.findById(c.id) } returns Optional.of(c)
        every { inbox.revision(c.id) } returns "current"
        outbox.enqueue(parent.id, "decision:${c.id}:old:false:${parent.id}:0", message(), c.id)
        val row = saved.single(); outbox.send(row)
        assertThat(row.status).isEqualTo(NotificationStatus.SKIPPED)
        row.status = NotificationStatus.QUEUED; row.dedupKey = "decision:${c.id}:current:false:${parent.id}:0"
        c.status = CaseStatus.COMPLETED; outbox.send(row)
        assertThat(row.status).isEqualTo(NotificationStatus.SKIPPED)
        c.status = CaseStatus.ACTION_REQUIRED; row.status = NotificationStatus.QUEUED; parent.instantEnabled = false
        outbox.send(row)
        assertThat(row.status).isEqualTo(NotificationStatus.SKIPPED)
        verify(exactly = 0) { client.sendTemplate(any(), any(), any(), any(), any(), any()) }
    }
    @Test fun `an archived case is not sent even though its task is still open`() {
        val relevance = mockk<de.schoolagent.inbox.CaseRelevanceService>()
        val c = SchoolCase(title = "Undated request", importanceScore = 5, status = CaseStatus.ACTION_REQUIRED)
        every { cases.findById(c.id) } returns Optional.of(c)
        every { relevance.isArchived(c) } returns true
        val guarded = WhatsappOutbox(rows, receipts, users, cases, config, templates, client, model, health,
            Clock.fixed(now, ZoneOffset.UTC), inbox, relevance)
        guarded.enqueue(parent.id, "decision:${c.id}:current:false:${parent.id}:0", message(), c.id)
        guarded.send(saved.single())
        assertThat(saved.single().status).isEqualTo(NotificationStatus.SKIPPED)
        assertThat(c.status).isEqualTo(CaseStatus.ACTION_REQUIRED)
        verify(exactly = 0) { client.sendTemplate(any(), any(), any(), any(), any(), any()) }
    }
    @Test fun `translation cannot silently change an amount or remove a letter link`() {
        every { model.complete(ModelRole.TEXT, any(), any(), any()) } returns """{"texts":["Mañana","5 oct · Preparar €7.20"]}"""
        val row = queue(UserLanguage.ES); outbox.send(row)
        assertThat(row.status).isEqualTo(NotificationStatus.FAILED)
        assertThat(row.lastError).contains("amounts")
        verify(exactly = 0) { client.sendTemplate(any(), any(), any(), any(), any(), any()) }
    }
    @Test fun `preferences are checked again if they change while translating`() {
        every { model.complete(ModelRole.TEXT, any(), any(), any()) } answers {
            parent.briefingEnabled = false
            """{"texts":["Mañana","5 oct · Preparar €17.20"]}"""
        }
        val row = queue(UserLanguage.ES); outbox.send(row)
        assertThat(row.status).isEqualTo(NotificationStatus.SKIPPED)
        verify(exactly = 0) { client.sendTemplate(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `receipt arriving before message save is applied after acceptance`() {
        outbox.record(status("delivered"))
        val row = queue(); outbox.send(row)
        assertThat(row.status).isEqualTo(NotificationStatus.DELIVERED)
    }
    @Test fun `Spanish rendering is saved once and reused after a transient send failure`() {
        every { model.complete(ModelRole.TEXT, any(), any(), any()) } returns """{"texts":["Mañana","5 oct · Preparar €17.20"]}"""
        every { client.sendTemplate(any(), any(), any(), any(), any(), any()) } throws WhatsappSendException("Rate limit", retryable = true) andThen "wamid.1"
        val row = queue(UserLanguage.ES); outbox.send(row); outbox.send(row)
        assertThat(row.status).isEqualTo(NotificationStatus.ACCEPTED)
        assertThat(row.message).contains("Mañana", "Preparar €17.20", "Abrir plan")
        verify(exactly = 1) { model.complete(ModelRole.TEXT, any(), any(), any()) }
        verify(exactly = 2) { client.sendTemplate(any(), any(), "es", match { it.contains("Mañana") }, any(), any()) }
    }
}
