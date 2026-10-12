package de.schoolagent.whatsapp

import de.schoolagent.domain.enums.*
import de.schoolagent.domain.model.*
import de.schoolagent.domain.repo.*
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.Optional

class WhatsappAdminControllerTest {
    @Test fun `parents see only their own notifications for children they can still access`() {
        val child = Child(name = "Taylor")
        val other = Child(name = "Jamie")
        val parent = AppUser(name = "parent", displayName = "Parent", passwordHash = "x").apply { kids += child }
        val admin = AppUser(name = "admin", displayName = "Admin", passwordHash = "x", role = "ADMIN")
        val allowed = SchoolCase(title = "Taylor letter", childId = child.id)
        val denied = SchoolCase(title = "Jamie letter", childId = other.id)
        fun row(c: SchoolCase?, who: AppUser, text: String) = Notification(caseId = c?.id, recipientId = who.id,
            level = NotificationLevel.DIGEST, channel = NotificationChannel.WHATSAPP, message = text, dedupKey = "decision:$text", createdAt = Instant.EPOCH)
        val config = mockk<WhatsAppConfigService>(relaxed = true)
        every { config.config(any()) } returns WhatsAppConfigService.Config("https://graph.facebook.com/v25.0", "p", "w", "t", null, null, "https://school.example.org")
        every { config.accounts() } returns listOf(parent, admin)
        val rows = mockk<NotificationRepository>()
        every { rows.findTop20ByChannelOrderByCreatedAtDesc(any()) } returns listOf(
            row(allowed, parent, "Allowed"), row(denied, parent, "Access removed"), row(allowed, admin, "Other recipient"), row(null, parent, "Old family briefing"))
        val cases = mockk<SchoolCaseRepository>()
        every { cases.findById(allowed.id) } returns Optional.of(allowed)
        every { cases.findById(denied.id) } returns Optional.of(denied)
        val controller = WhatsappAdminController(config, mockk(), mockk(), mockk(relaxed = true), mockk(), rows, cases)
        try {
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(parent.name, "x")
            assertThat(controller.status(null).deliveries.map { it.message }).containsExactly("Allowed")
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(admin.name, "x")
            assertThat(controller.status(null).deliveries).hasSize(4)
        } finally { SecurityContextHolder.clearContext() }
    }
}
