package de.schoolagent.scheduling

import de.schoolagent.briefing.BriefingService
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
}
