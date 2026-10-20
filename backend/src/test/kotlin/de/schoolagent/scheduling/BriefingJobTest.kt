package de.schoolagent.scheduling

import de.schoolagent.briefing.BriefingService
import de.schoolagent.diagnostics.*
import de.schoolagent.domain.model.AppUser
import de.schoolagent.domain.repo.AppUserRepository
import de.schoolagent.domain.repo.NotificationRepository
import de.schoolagent.whatsapp.WaTemplates
import de.schoolagent.whatsapp.WhatsAppConfigService
import de.schoolagent.whatsapp.WhatsappOutbox
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.*
import java.util.UUID

class BriefingJobTest {
    private val users = mockk<AppUserRepository>()
    private val briefing = mockk<BriefingService>()
    private val notifications = mockk<NotificationRepository>(relaxed = true)
    private val config = mockk<WhatsAppConfigService>()
    private val outbox = mockk<WhatsappOutbox>(relaxed = true)
    private val kid = UUID.randomUUID()
    private val parent = AppUser(name = "morgan", displayName = "Morgan", passwordHash = "x", whatsappNumber = "+49123")

    init {
        every { config.isConfigured() } returns true
        every { users.findAll() } returns listOf(parent)
        every { briefing.weekKey(any()) } returns "2026-W43"
    }

    @Test fun `evening plans select tomorrow in Berlin and expire at midnight across DST changes`() {
        listOf("2026-10-24T17:00:00Z", "2026-10-25T18:00:00Z", "2026-03-28T18:00:00Z", "2026-03-29T17:00:00Z").forEach { instant ->
            clearMocks(outbox, answers = false)
            val now = Instant.parse(instant)
            val nextDay = now.atZone(ZoneId.of("Europe/Berlin")).toLocalDate().plusDays(1)
            every { briefing.plans(any(), nextDay, false) } returns listOf(BriefingService.Plan(kid, "Taylor", nextDay.toString(), listOf("Bring the book")))
            val job = BriefingJob(users, briefing, notifications, config, outbox, Clock.fixed(now, ZoneOffset.UTC))
            job.check()
            val message = slot<WaTemplates.Message>()
            verify(exactly = 1) { outbox.enqueue(parent.id, any(), capture(message), any(), any(), any(), nextDay.atStartOfDay(ZoneId.of("Europe/Berlin")).toInstant()) }
            assertThat(message.captured.header).isEqualTo("Tomorrow for Taylor")
            assertThat(message.captured.params.first()).isEqualTo(nextDay.toString())
        }
    }

    @Test fun `Sunday weekly plan starts on Monday and cannot be sent under next week after Sunday`() {
        val now = Instant.parse("2026-10-25T09:00:00Z") // 10:00 after the autumn clock change.
        val monday = LocalDate.parse("2026-10-26")
        every { briefing.plans(any(), monday, true) } returns listOf(BriefingService.Plan(kid, "Jamie", "26 Oct – 1 Nov", listOf("Return the form")))
        val job = BriefingJob(users, briefing, notifications, config, outbox, Clock.fixed(now, ZoneOffset.UTC))
        job.check()
        verify(exactly = 1) { outbox.enqueue(parent.id, any(), match { it.header == "Next week for Jamie" }, any(), any(), any(), Instant.parse("2026-10-25T23:00:00Z")) }
    }

    @Test fun `empty evening plan is distinguished from a missed schedule window`() {
        val diagnostics = mockk<IntegrationDiagnostics>(relaxed = true)
        val now = Instant.parse("2026-10-20T17:00:00Z")
        val tomorrow = LocalDate.parse("2026-10-21")
        every { briefing.plans(any(), tomorrow, false) } returns emptyList()
        BriefingJob(users, briefing, notifications, config, outbox, Clock.fixed(now, ZoneOffset.UTC), diagnostics).check()
        verify { diagnostics.oncePerDay(match { it.outcome == DiagnosticOutcome.NO_ITEMS && it.accountId == parent.id && it.period == tomorrow }) }
        verify { diagnostics.record(match { it.stage == DiagnosticStage.BRIEFING && it.outcome == DiagnosticOutcome.COMPLETED && it.count == 0 }) }
        verify(exactly = 0) { outbox.enqueue(any(), any(), any(), any(), any(), any(), any()) }
        clearMocks(diagnostics)
        BriefingJob(users, briefing, notifications, config, outbox, Clock.fixed(now.plusSeconds(1800), ZoneOffset.UTC), diagnostics).check()
        verify { diagnostics.oncePerDay(match { it.outcome == DiagnosticOutcome.WINDOW_PASSED }) }
    }

    @Test fun `disabled unconfigured and already queued reminders have distinct diagnostics`() {
        val diagnostics = mockk<IntegrationDiagnostics>(relaxed = true)
        val clock = Clock.fixed(Instant.parse("2026-10-20T17:00:00Z"), ZoneOffset.UTC)
        val job = BriefingJob(users, briefing, notifications, config, outbox, clock, diagnostics)
        every { config.isConfigured() } returns false
        job.check()
        verify { diagnostics.oncePerDay(match { it.outcome == DiagnosticOutcome.NOT_CONFIGURED }) }
        every { config.isConfigured() } returns true
        parent.briefingEnabled = false
        job.check()
        verify { diagnostics.oncePerDay(match { it.outcome == DiagnosticOutcome.DISABLED }) }
        parent.briefingEnabled = true
        every { notifications.existsByDedupKey(any()) } returns true
        job.check()
        verify { diagnostics.oncePerDay(match { it.outcome == DiagnosticOutcome.DUPLICATE }) }
        verify(exactly = 0) { briefing.plans(any(), any(), any()) }
    }
    @Test fun `single digit hours accepted by preferences still send a briefing`() {
        parent.briefingTime = "9:00"
        every { briefing.plans(any(), LocalDate.parse("2026-10-21"), false) } returns
            listOf(BriefingService.Plan(kid, "Jamie", "21 October", listOf("Bring the book")))
        BriefingJob(users, briefing, notifications, config, outbox,
            Clock.fixed(Instant.parse("2026-10-20T07:05:00Z"), ZoneOffset.UTC)).check()
        verify(exactly = 1) { outbox.enqueue(parent.id, any(), any(), any(), any(), any(), any()) }
    }

}
