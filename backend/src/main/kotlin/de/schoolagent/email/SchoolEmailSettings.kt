package de.schoolagent.email

import de.schoolagent.domain.model.Setting
import de.schoolagent.domain.repo.SettingRepository
import de.schoolagent.security.KidAccessService
import org.springframework.stereotype.Service
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@Service
class SchoolEmailSettings(private val settings: SettingRepository) {
    fun domains(kid: UUID): List<String> = settings.findById("gmail.$kid.school-domains").orElse(null)?.valueJson?.lines()?.filter { it.isNotBlank() }.orEmpty()
    fun save(kid: UUID, entries: List<String>): List<String> {
        val normalized = entries.map { it.trim().lowercase().removePrefix("@").removePrefix("*.") }.filter { it.isNotEmpty() }.distinct()
        if (normalized.size > 30 || normalized.any { it.length > 253 || !Regex("(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}").matches(it) })
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter school domains such as school.example, without URLs or email addresses.")
        settings.save(Setting(key = "gmail.$kid.school-domains", valueJson = normalized.joinToString("\n"), updatedAt = Instant.now()))
        return normalized
    }
    fun accepts(kid: UUID, mail: EmailContent): Boolean {
        val allowed = domains(kid)
        // Empty preserves the existing dedicated school mailbox behavior.
        if (allowed.isEmpty()) return true
        val addresses = listOfNotNull(mail.sender, mail.recipients).flatMap { text ->
            Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@([A-Za-z0-9.-]+)").findAll(text).map { it.groupValues[1].lowercase() }.toList()
        }
        return addresses.any { domain -> allowed.any { domain == it || domain.endsWith(".$it") } }
    }
}
@RestController
@RequestMapping("/api/google/email-settings")
class SchoolEmailSettingsController(private val settings: SchoolEmailSettings, private val access: KidAccessService) {
    data class Config(val domains: List<String>?)
    @GetMapping
    fun get(@RequestParam kid: UUID, @AuthenticationPrincipal user: UserDetails): Config {
        access.requireAccess(user, kid); return Config(settings.domains(kid))
    }
    @PutMapping
    fun save(@RequestParam kid: UUID, @RequestBody config: Config, @AuthenticationPrincipal user: UserDetails): Config {
        access.requireAccess(user, kid); return Config(settings.save(kid, config.domains.orEmpty()))
    }
}
