package de.schoolagent.security

import de.schoolagent.domain.model.SecretRef
import de.schoolagent.domain.repo.SecretRefRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Optional

class AesGcmSecretVaultTest {

    private val refs = mockk<SecretRefRepository>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var vault: AesGcmSecretVault

    private val stored = mutableMapOf<String, SecretRef>()

    @BeforeEach
    fun setUp() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        vault = AesGcmSecretVault(refs, clock, key)
        every { refs.findById(any()) } answers { Optional.ofNullable(stored[firstArg()]) }
        every { refs.save(any<SecretRef>()) } answers {
            val ref = firstArg<SecretRef>()
            stored[ref.key] = ref
            ref
        }
        every { refs.existsById(any()) } answers { stored.containsKey(firstArg()) }
    }

    @Test
    fun `roundtrip stores ciphertext, not plaintext`() {
        vault.store("google.refresh-token", "super-secret-token")

        val persisted = stored.getValue("google.refresh-token").cipherText
        assertThat(persisted).doesNotContain("super-secret-token")
        assertThat(vault.read("google.refresh-token")).isEqualTo("super-secret-token")
    }

    @Test
    fun `same plaintext encrypts to different ciphertext (random IV)`() {
        vault.store("k", "value")
        val first = stored.getValue("k").cipherText
        vault.store("k", "value")
        assertThat(stored.getValue("k").cipherText).isNotEqualTo(first)
        assertThat(vault.read("k")).isEqualTo("value")
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        vault.store("k", "value")
        val bytes = Base64.getDecoder().decode(stored.getValue("k").cipherText)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        stored["k"] = SecretRef(key = "k", cipherText = Base64.getEncoder().encodeToString(bytes))

        assertThatThrownBy { vault.read("k") }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `missing master key fails with actionable message`() {
        assertThatThrownBy { AesGcmSecretVault(refs, clock, "") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("SCHOOLAGENT_MASTER_KEY")
    }
}
