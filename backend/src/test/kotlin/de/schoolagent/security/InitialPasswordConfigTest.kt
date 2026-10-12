package de.schoolagent.security

import de.schoolagent.domain.model.AppUser
import de.schoolagent.domain.repo.AppUserRepository
import io.mockk.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class InitialPasswordConfigTest {
    private val users = mockk<AppUserRepository>()
    private val encoder = mockk<PasswordEncoder>()
    private val clock = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC)
    private fun run(username: String = "parent-admin", password: String = "synthetic-test-password") =
        InitialPasswordConfig().initialPasswordSetter(users, encoder, clock, username, password).run(DefaultApplicationArguments())

    @Test fun `fresh administrator requires explicit credentials and has no default login`() {
        val pending = AppUser(name = "bootstrap-admin", displayName = "Administrator", passwordHash = "PENDING")
        every { users.findAll() } returns listOf(pending)
        every { users.findByName(any()) } returns null
        every { encoder.encode(any()) } returns "bcrypt-test-hash"
        every { users.save(any<AppUser>()) } answers { firstArg() }
        for (password in listOf("", "   ", "change-me-on-first-login", "schoolagent-dev", "short")) {
            assertThatThrownBy { run(password = password) }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("SCHOOLAGENT_INITIAL_PASSWORD")
        }
        assertThatThrownBy { run(username = "") }.hasMessageContaining("SCHOOLAGENT_INITIAL_USERNAME")
        verify(exactly = 0) { users.save(any<AppUser>()) }
        run()
        assertThat(pending.name).isEqualTo("parent-admin")
        assertThat(pending.passwordHash).isEqualTo("bcrypt-test-hash")
        assertThat(pending.updatedAt).isEqualTo(clock.instant())
    }

    @Test fun `existing accounts are never renamed or reset and require no bootstrap credentials`() {
        val existing = AppUser(name = "existing-parent", displayName = "Private display", passwordHash = "existing-hash")
        every { users.findAll() } returns listOf(existing)
        run(username = "", password = "")
        assertThat(existing.name).isEqualTo("existing-parent")
        assertThat(existing.passwordHash).isEqualTo("existing-hash")
        verify(exactly = 0) { users.save(any<AppUser>()) }
        verify(exactly = 0) { encoder.encode(any()) }
    }

    @Test fun `invalid deployment passwords fail without echoing supplied values`() {
        val password = "tiny-secret"
        assertThatThrownBy { DatabasePasswordValidation(password) }.hasMessageNotContaining(password)
        assertThatThrownBy { DatabasePasswordValidation("") }.hasMessageContaining("SCHOOLAGENT_DB_PASSWORD")
    }

    @Test fun `legacy person-specific actors remain readable without hardcoded names`() {
        val converter = LegacyActorConverter()
        assertThat(converter.convertToEntityAttribute("OLD_PARENT_LABEL")).isEqualTo(de.schoolagent.domain.enums.Actor.MANUAL)
        assertThat(converter.convertToEntityAttribute("AI")).isEqualTo(de.schoolagent.domain.enums.Actor.AI)
    }
}
