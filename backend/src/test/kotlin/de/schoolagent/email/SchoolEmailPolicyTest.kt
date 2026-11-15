package de.schoolagent.email

import de.schoolagent.domain.enums.SourceType
import de.schoolagent.domain.model.Source
import de.schoolagent.domain.model.Setting
import de.schoolagent.domain.repo.SourceRepository
import de.schoolagent.domain.repo.SettingRepository
import io.mockk.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID

class SchoolEmailPolicyTest {
    private val accountSender = "Google <notification@${SchoolEmailPolicy.GOOGLE_ACCOUNT_DOMAIN}>"
    private fun mail(sender: String = accountSender, forwarded: Boolean = false) = EmailContent(sender,
        "pupil@school.example", "Synthetic account notification", null, null, null, emptyList(), "Synthetic text", forwarded)
    private fun source(id: UUID, sender: String = accountSender, kind: SourceType = SourceType.EMAIL, forwarded: Boolean = false) =
        Source(caseId = id, sourceType = kind, externalId = "synthetic", dedupKey = UUID.randomUUID().toString(),
            contentHash = "synthetic-hash", sender = sender, forwardedBy = if (forwarded) "parent@example.org" else null)

    @Test fun `direct account notices are rejected with empty and configured domain filters`() {
        val repo = mockk<SettingRepository>()
        val settings = SchoolEmailSettings(repo)
        every { repo.findById(any()) } returns Optional.empty()
        assertThat(settings.accepts(UUID.randomUUID(), mail())).isFalse()
        every { repo.findById(any()) } returns Optional.of(Setting(key = "synthetic", valueJson = "school.example"))
        // Even a pupil's school-domain recipient cannot make an account-administration notice school mail.
        assertThat(settings.accepts(UUID.randomUUID(), mail())).isFalse()
    }

    @Test fun `Classroom school topics lookalike domains and explicit forwards remain eligible`() {
        val repo = mockk<SettingRepository>()
        every { repo.findById(any()) } returns Optional.empty()
        val settings = SchoolEmailSettings(repo)
        val classroomDomain = "classroom.google.com"
        for (sender in listOf("notice@$classroomDomain", "teacher@school.example", "notice@accounts.google.com.example.test"))
            assertThat(settings.accepts(UUID.randomUUID(), mail(sender).copy(subject = "Google security alert class exercise"))).isTrue()
        assertThat(settings.accepts(UUID.randomUUID(), mail(forwarded = true))).isTrue()
        assertThat(SchoolEmailPolicy.isDirectAccountNotice(null)).isFalse()
    }

    @Test fun `display names cannot spoof allowed school sender or recipient domains`() {
        val repo = mockk<SettingRepository>()
        every { repo.findById(any()) } returns Optional.of(Setting(key = "synthetic", valueJson = "school.example"))
        val settings = SchoolEmailSettings(repo)
        val forged = mail("\"teacher@school.example\" <outsider@example.org>").copy(recipients = "\"office@school.example\" <parent@example.org>")
        assertThat(settings.accepts(UUID.randomUUID(), forged)).isFalse()
        assertThat(EmailContent.address(forged.sender)).isEqualTo("outsider@example.org")
        assertThat(settings.accepts(UUID.randomUUID(), forged.copy(sender = "teacher@sub.school.example"))).isTrue()
        assertThat(settings.accepts(UUID.randomUUID(), forged.copy(recipients = "Teacher <teacher@school.example>"))).isTrue()
        // A Google-looking display name must not discard a legitimate school's actual address.
        assertThat(SchoolEmailPolicy.isDirectAccountNotice("\"$accountSender\" <teacher@school.example>")).isFalse()
    }

    @Test fun `existing account-only cases are suppressed but mixed forwarded and school evidence stays eligible`() {
        val repo = mockk<SourceRepository>()
        val policy = SchoolEmailPolicy(repo)
        val id = UUID.randomUUID()
        val account = source(id)
        val attachment = source(id, kind = SourceType.ATTACHMENT)
        every { repo.findByCaseId(id) } returns listOf(account, attachment)
        assertThat(policy.permitsCase(id)).isFalse()
        val school = source(id, "teacher@school.example")
        every { repo.findByCaseId(id) } returns listOf(account, school)
        assertThat(policy.permitsCase(id)).isTrue()
        school.superseded = true
        assertThat(policy.permitsCase(id)).isFalse()
        every { repo.findByCaseId(id) } returns listOf(source(id, forwarded = true))
        assertThat(policy.permitsCase(id)).isTrue()
        every { repo.findByCaseId(id) } returns listOf(account, source(id, kind = SourceType.PORTAL_LETTER))
        assertThat(policy.permitsCase(id)).isTrue()
        every { repo.findByCaseId(id) } returns emptyList()
        assertThat(policy.permitsCase(id)).isTrue()
    }

    @Test fun `plan batch filtering uses the same case provenance rule`() {
        val repo = mockk<SourceRepository>()
        val policy = SchoolEmailPolicy(repo)
        val account = UUID.randomUUID(); val school = UUID.randomUUID(); val unknown = UUID.randomUUID()
        val ids = listOf(account, school, unknown)
        every { repo.findByCaseIdIn(ids) } returns listOf(source(account), source(school, "teacher@school.example"))
        assertThat(policy.permittedCases(ids)).containsExactlyInAnyOrder(school, unknown)
    }
}
