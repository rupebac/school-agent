package de.schoolagent.whatsapp

import de.schoolagent.domain.model.Setting
import de.schoolagent.domain.repo.AppUserRepository
import de.schoolagent.domain.repo.ChildRepository
import de.schoolagent.domain.repo.SettingRepository
import de.schoolagent.security.SecretVault
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/**
 * Generic WhatsApp configuration (spec §25): any Cloud-API-compatible
 * endpoint works — Meta Graph by default, but base URL, token, phone-number
 * id and webhook secrets are all swappable at runtime via Settings/env.
 * One family connection serves every child; child IDs only scope message content.
 * The token lives ONLY in the encrypted vault.
 */
@Service
class WhatsAppConfigService(
    private val settings: SettingRepository,
    private val vault: SecretVault,
    private val users: AppUserRepository,
    private val kids: ChildRepository,
    private val clock: Clock,
    @param:Value("\${schoolagent.public-url:http://127.0.0.1:4200}")
    private val publicUrl: String,
) {

    data class Config(
        val apiUrl: String,
        val phoneNumberId: String?,
        val wabaId: String?,
        val token: String?,
        val verifyToken: String?,
        val appSecret: String?,
        val frontendUrl: String,
    )

    // Compatibility signatures for existing callers; credentials are always family-wide.
    fun <T> withChild(childId: UUID?, block: () -> T): T = block()
    fun config(): Config = resolve(null)
    fun config(childId: UUID?): Config = resolve(null)

    fun webhookUrl(): String = "${publicUrl.trimEnd('/')}/api/whatsapp/webhook"

    fun lastWebhookAt(): String? = setting(LAST_WEBHOOK_KEY)

    fun recordWebhook() {
        put(LAST_WEBHOOK_KEY, clock.instant().toString())
    }

    fun isConfigured(): Boolean = isConfigured(null)

    fun isConfigured(childId: UUID?): Boolean = resolve(childId).let {
        it.phoneNumberId != null && !it.token.isNullOrBlank()
    }

    fun matchesVerifyToken(provided: String?): Boolean =
        !provided.isNullOrBlank() && vault.read(VERIFY_TOKEN_KEY) == provided

    fun appSecrets(): List<String> = listOfNotNull(vault.read(APP_SECRET_KEY)).filter(String::isNotBlank)

    @org.springframework.transaction.annotation.Transactional
    fun save(
        childId: UUID?,
        apiUrl: String?,
        phoneNumberId: String?,
        wabaId: String?,
        token: String?,
        verifyToken: String?,
        appSecret: String?,
        frontendUrl: String?,
        parentNumbers: Map<String, String>?,
    ) {
        apiUrl?.trim()?.takeIf { it.isNotBlank() }?.let { put(key(childId, API_URL_KEY), it.trimEnd('/')) }
        phoneNumberId?.trim()?.takeIf { it.isNotBlank() }?.let { put(key(childId, PHONE_ID_KEY), it) }
        wabaId?.trim()?.takeIf { it.isNotBlank() }?.let { put(key(childId, WABA_ID_KEY), it) }
        frontendUrl?.trim()?.takeIf { it.isNotBlank() }?.let { put(key(childId, FRONTEND_URL_KEY), it.trimEnd('/')) }
        putSecret(key(childId, VERIFY_TOKEN_KEY), verifyToken)
        putSecret(key(childId, APP_SECRET_KEY), appSecret)
        putSecret(key(childId, TOKEN_KEY), token)

        parentNumbers?.forEach { (userName, number) ->
            users.findByName(userName.trim().lowercase())?.let { user ->
                val normalized = number.trim().takeIf { it.isNotBlank() }
                user.whatsappNumber = normalized
                user.updatedAt = clock.instant()
                users.save(user)
            }
        }
    }

    fun accounts(): List<de.schoolagent.domain.model.AppUser> =
        users.findAll().sortedBy { it.displayName.lowercase() }

    /** Parents with a registered number — the notification recipients. */
    fun recipients(): List<Pair<de.schoolagent.domain.model.AppUser, String>> =
        accounts().mapNotNull { u -> u.whatsappNumber?.let { u to it } }

    fun userForNumber(from: String): de.schoolagent.domain.model.AppUser? {
        val digits = from.filter { it.isDigit() }.takeLast(9)
        return users.findAll().firstOrNull {
            it.whatsappNumber?.filter { c -> c.isDigit() }?.takeLast(9) == digits
        }
    }

    /** A family phone number never identifies which child a message concerns. */
    fun childIdForPhoneNumberId(phoneNumberId: String?): UUID? = null
    fun defaultChildId(): UUID? = null

    private fun resolve(childId: UUID?): Config = Config(
        apiUrl = readSetting(childId, API_URL_KEY) ?: DEFAULT_API_URL,
        phoneNumberId = readSetting(childId, PHONE_ID_KEY),
        wabaId = readSetting(childId, WABA_ID_KEY),
        token = readSecret(childId, TOKEN_KEY),
        verifyToken = readSecret(childId, VERIFY_TOKEN_KEY),
        appSecret = readSecret(childId, APP_SECRET_KEY),
        frontendUrl = readSetting(childId, FRONTEND_URL_KEY) ?: publicUrl.trimEnd('/'),
    )

    private fun key(childId: UUID?, global: String): String = global
    private fun readSetting(childId: UUID?, globalKey: String): String? = setting(globalKey)
    private fun readSecret(childId: UUID?, globalKey: String): String? = vault.read(globalKey)

    private fun setting(key: String): String? = settings.findById(key).orElse(null)?.valueJson?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun put(key: String, value: String) {
        settings.save(Setting(key = key, valueJson = value, updatedAt = clock.instant()))
    }

    /** null = leave stored value; blank = delete; otherwise replace. */
    private fun putSecret(key: String, value: String?) {
        if (value == null) return
        if (value.isBlank()) vault.delete(key) else vault.store(key, value)
    }

    companion object {
        const val API_URL_KEY = "whatsapp.api-url"
        const val PHONE_ID_KEY = "whatsapp.phone-number-id"
        const val WABA_ID_KEY = "whatsapp.waba-id"
        const val FRONTEND_URL_KEY = "whatsapp.frontend-url"
        const val TOKEN_KEY = "whatsapp.token"
        const val VERIFY_TOKEN_KEY = "whatsapp.verify-token"
        const val APP_SECRET_KEY = "whatsapp.app-secret"
        const val LAST_WEBHOOK_KEY = "whatsapp.last-webhook"
        const val DEFAULT_API_URL = "https://graph.facebook.com/v25.0"
    }
}
