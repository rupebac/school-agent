package de.schoolagent.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Plaintext secrets never leave the vault (spec §31). */
interface SecretVault {
    fun store(key: String, value: String)
    fun read(key: String): String?
    fun exists(key: String): Boolean
    fun delete(key: String)
}

/**
 * AES-256-GCM encryption at rest, persisted through the secret_ref table.
 * The master key comes from SCHOOLAGENT_MASTER_KEY (base64, 32 bytes) and is
 * never stored. Ciphertext format: base64(iv[12] || cipher || tag[16]).
 */
@Service
class AesGcmSecretVault(
    private val refs: de.schoolagent.domain.repo.SecretRefRepository,
    private val clock: java.time.Clock,
    @Value("\${schoolagent.master-key:}") masterKeyBase64: String,
) : SecretVault {

    private val random = SecureRandom()
    private val key: SecretKey = try { decodeKey(masterKeyBase64) } catch (_: Exception) {
        throw IllegalArgumentException("SCHOOLAGENT_MASTER_KEY must be explicitly set to a base64-encoded 32-byte key")
    }

    override fun store(key: String, value: String) {
        val cipher = encrypt(value)
        val existing = refs.findById(key).orElse(null)
        if (existing != null) {
            existing.cipherText = cipher
            existing.updatedAt = clock.instant()
            refs.save(existing)
        } else {
            refs.save(
                de.schoolagent.domain.model.SecretRef(
                    key = key,
                    cipherText = cipher,
                    updatedAt = clock.instant(),
                ),
            )
        }
    }

    override fun read(key: String): String? {
        val ref = refs.findById(key).orElse(null) ?: return null
        return decrypt(ref.cipherText)
    }

    override fun exists(key: String): Boolean = refs.existsById(key)

    override fun delete(key: String) {
        refs.deleteById(key)
    }

    private fun encrypt(plain: String): String {
        val k = requireKey()
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    private fun decrypt(stored: String): String {
        val k = requireKey()
        val all = Base64.getDecoder().decode(stored)
        require(all.size > IV_LEN) { "ciphertext too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, all, 0, IV_LEN))
        val plain = cipher.doFinal(all, IV_LEN, all.size - IV_LEN)
        return String(plain, Charsets.UTF_8)
    }

    private fun requireKey(): SecretKey = key

    private fun decodeKey(base64: String): SecretKey {
        val bytes = Base64.getDecoder().decode(base64.trim())
        require(bytes.size == 32) { "master key must be 32 bytes, was ${bytes.size}" }
        return SecretKeySpec(bytes, "AES")
    }

    companion object {
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
    }
}
