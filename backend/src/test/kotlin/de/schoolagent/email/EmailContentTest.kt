package de.schoolagent.email

import de.schoolagent.domain.model.Setting
import de.schoolagent.domain.repo.SettingRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID

class EmailContentTest {
    private fun forwarded(parent: String, wrapperId: String, body: String = "Please bring trainers on Friday.") = """
From: $parent
Message-ID: <$wrapperId@parent.example>
Date: Mon, 28 Sep 2026 10:00:00 +0200
Subject: Fwd: Sports day
Content-Type: text/plain; charset=UTF-8

---------- Forwarded message ---------
From: School <office@school.example>
Date: Fri, 25 Sep 2026 09:00:00 +0200
Subject: Sports day
To: Parents <parent@example.org>

$body
""".trimIndent().toByteArray()

    @Test fun `two parents inline forwarding the same mail have the same original fingerprint`() {
        val a = EmailContent.read(forwarded("mum@example.org", "one"))
        val b = EmailContent.read(forwarded("dad@example.org", "two"))
        assertThat(a.forwarded).isTrue()
        assertThat(a.messageId).isNull()
        assertThat(a.sender).contains("office@school.example")
        assertThat(a.subject).isEqualTo("Sports day")
        assertThat(a.body).isEqualTo("Please bring trainers on Friday.")
        assertThat(a.fingerprint(listOf("attachment"))).isEqualTo(b.fingerprint(listOf("attachment")))
        assertThat(a.fingerprint(listOf("attachment"))).isNotEqualTo(b.fingerprint(listOf("changed-attachment")))
        assertThat(a.fingerprint(emptyList())).isNotEqualTo(EmailContent.read(forwarded("dad@example.org", "three", "Correction: Monday.")).fingerprint(emptyList()))
    }

    @Test fun `attached MIME forward preserves original references and identity`() {
        val raw = """
From: parent@example.org
Subject: Fwd: Meeting
MIME-Version: 1.0
Content-Type: multipart/mixed; boundary=part

--part
Content-Type: text/plain

FYI
--part
Content-Type: message/rfc822

From: Teacher <teacher@school.example>
To: parent@example.org
Subject: Re: Meeting
Date: Mon, 28 Sep 2026 10:00:00 +0200
Message-ID: <reply@school.example>
In-Reply-To: <first@school.example>
References: <first@school.example>
Content-Type: text/plain

Tuesday works for me.
--part--
""".trimIndent()
        val mail = EmailContent.read(raw.toByteArray())
        assertThat(mail.messageId).isEqualTo("<reply@school.example>")
        assertThat(mail.references).containsExactly("<first@school.example>")
        assertThat(mail.body).isEqualTo("Tuesday works for me.")
        assertThat(mail.forwarded).isTrue()
    }

    @Test fun `domain matching includes subdomains and original recipients but excludes suffix spoofing`() {
        val repo = mockk<SettingRepository>()
        every { repo.findById(any()) } returns Optional.of(Setting(key = "test", valueJson = "school.example"))
        val settings = SchoolEmailSettings(repo); val kid = UUID.randomUUID()
        val mail = EmailContent.read(forwarded("mum@example.org", "one"))
        assertThat(settings.accepts(kid, mail)).isTrue()
        assertThat(settings.accepts(kid, mail.copy(sender = "teacher@sub.school.example"))).isTrue()
        assertThat(settings.accepts(kid, mail.copy(sender = "teacher@evilschool.example"))).isFalse()
        assertThat(settings.accepts(kid, mail.copy(sender = "parent@example.org", recipients = "teacher@school.example"))).isTrue()
    }

    @Test fun `ambiguous date-free forward never deduplicates by body alone`() {
        val mail = EmailContent.read(forwarded("mum@example.org", "one"))
        assertThat(mail.copy(date = null, sentAt = null).fingerprint(emptyList())).isNull()
    }
}
