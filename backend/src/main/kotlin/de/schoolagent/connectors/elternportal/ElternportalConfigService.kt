package de.schoolagent.connectors.elternportal

import de.schoolagent.domain.model.Child
import de.schoolagent.domain.model.Setting
import de.schoolagent.domain.repo.AppUserRepository
import de.schoolagent.domain.repo.ChildRepository
import de.schoolagent.domain.repo.SettingRepository
import de.schoolagent.security.SecretVault
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Elternportal configuration — PER KID (multi-kid model): each child has its
 * own portal URL + credentials (schools may differ). The password lives ONLY
 * in the encrypted vault. Legacy global keys were migrated to the first
 * child's scope in V8.
 */
@Service
class ElternportalConfigService(
    private val settings: SettingRepository,
    private val vault: SecretVault,
    private val kids: ChildRepository,
    private val users: AppUserRepository,
    private val clock: Clock,
) {

    data class Config(val childId: UUID, val url: String, val username: String, val password: String?)

    /** Public navigation needs only the address, never decrypted credentials. */
    fun url(childId: UUID): String? = setting(urlKey(childId))

    fun config(childId: UUID): Config? {
        val url = url(childId) ?: return null
        val username = vault.read(usernameKey(childId)) ?: return null
        return Config(childId, url, username, vault.read(passwordKey(childId)))
    }

    fun save(childId: UUID, url: String, username: String, password: String?) {
        val normalized = url.trim().trimEnd('/')
        require(normalized.startsWith("https://") || normalized.startsWith("http://")) { "url must start with http(s)://" }
        settings.save(Setting(key = urlKey(childId), valueJson = normalized, updatedAt = clock.instant()))
        vault.store(usernameKey(childId), username.trim())
        if (password != null) {
            if (password.isBlank()) vault.delete(passwordKey(childId))
            else vault.store(passwordKey(childId), password)
        }
    }

    fun isConfigured(childId: UUID): Boolean = config(childId)?.let { it.password != null } == true

    fun clear(childId: UUID) {
        vault.delete(usernameKey(childId))
        vault.delete(passwordKey(childId))
        settings.deleteById(urlKey(childId))
        settings.deleteById(lastSyncKey(childId))
    }

    /** Kids whose portal is configured — the poll loop iterates these. */
    fun configuredKids(): List<Child> = kids.findAll().filter { isConfigured(it.id) }

    fun recordSync(childId: UUID) {
        settings.save(Setting(key = lastSyncKey(childId), valueJson = clock.instant().toString(), updatedAt = clock.instant()))
    }

    fun lastSync(childId: UUID): Instant? =
        settings.findById(lastSyncKey(childId)).orElse(null)?.valueJson?.trim()
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }

    fun userForNumber(from: String): de.schoolagent.domain.model.AppUser? {
        val digits = from.filter { it.isDigit() }.takeLast(9)
        return users.findAll().firstOrNull {
            it.whatsappNumber?.filter { c -> c.isDigit() }?.takeLast(9) == digits
        }
    }

    private fun setting(key: String): String? = settings.findById(key).orElse(null)?.valueJson?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun urlKey(childId: UUID) = "elternportal.$childId.url"
    private fun usernameKey(childId: UUID) = "elternportal.$childId.username"
    private fun passwordKey(childId: UUID) = "elternportal.$childId.password"
    private fun lastSyncKey(childId: UUID) = "elternportal.$childId.last-sync"
}
