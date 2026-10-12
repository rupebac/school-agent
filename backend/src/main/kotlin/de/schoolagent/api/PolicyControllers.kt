package de.schoolagent.api

import de.schoolagent.domain.enums.ModelRole
import de.schoolagent.domain.model.FamilyRule
import de.schoolagent.domain.model.ModelConfig
import de.schoolagent.domain.repo.FamilyRuleRepository
import de.schoolagent.domain.repo.ModelConfigRepository
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ModelConfigDto(
    val id: UUID,
    val role: String,
    val provider: String,
    val modelName: String,
    val temperature: Double?,
    val enabled: Boolean,
    val updatedAt: Instant,
)

data class ModelUpdateRequest(
    val modelName: String? = null,
    val provider: String? = null,
    val temperature: Double? = null,
    val enabled: Boolean? = null,
)

data class FamilyRuleDto(
    val id: UUID,
    val ruleKey: String,
    val text: String,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class FamilyRuleRequest(
    val ruleKey: String? = null,
    val text: String? = null,
    val enabled: Boolean? = null,
)

/** Frontend-editable ModelRouter rows (spec §14). */
@RestController
@RequestMapping("/api/models")
class ModelConfigController(
    private val repo: ModelConfigRepository,
    private val clock: Clock,
) {

    @GetMapping
    fun list(): List<ModelConfigDto> = repo.findAll()
        .sortedBy { it.role.name }
        .map { it.toDto() }

    @PutMapping("/{role}")
    fun update(@PathVariable role: String, @RequestBody body: ModelUpdateRequest): ModelConfigDto {
        val parsed = runCatching { ModelRole.valueOf(role.uppercase()) }.getOrElse {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown role: $role")
        }
        val row = repo.findByRole(parsed)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no model config for $parsed")
        body.modelName?.trim()?.takeIf { it.isNotBlank() }?.let { row.modelName = it }
        body.provider?.trim()?.takeIf { it.isNotBlank() }?.let { row.provider = it }
        body.temperature?.let { row.temperature = BigDecimal.valueOf(it) }
        body.enabled?.let { row.enabled = it }
        row.updatedAt = clock.instant()
        return repo.save(row).toDto()
    }

    private fun ModelConfig.toDto() = ModelConfigDto(
        id = id,
        role = role.name,
        provider = provider,
        modelName = modelName,
        temperature = temperature?.toDouble(),
        enabled = enabled,
        updatedAt = updatedAt,
    )
}

/** Family policy rules (spec §35): listed, toggled, edited — never fine-tuned. */
@RestController
@RequestMapping("/api/family-rules")
class FamilyRulesController(
    private val repo: FamilyRuleRepository,
    private val clock: Clock,
) {

    @GetMapping
    fun list(): List<FamilyRuleDto> = repo.findAll()
        .sortedByDescending { it.updatedAt }
        .map { it.toDto() }

    @PostMapping
    fun create(@RequestBody body: FamilyRuleRequest): FamilyRuleDto {
        val key = body.ruleKey?.trim().orEmpty()
        val text = body.text?.trim().orEmpty()
        if (key.length < 2) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "rule key too short")
        if (text.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "rule text required")
        if (repo.findByRuleKey(key) != null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "rule key already exists")
        }
        val now = clock.instant()
        return repo.save(
            FamilyRule(ruleKey = key, ruleValue = asJsonText(text), enabled = body.enabled ?: true, createdAt = now, updatedAt = now),
        ).toDto()
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody body: FamilyRuleRequest): FamilyRuleDto {
        val row = repo.findById(id).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "rule not found")
        body.ruleKey?.trim()?.takeIf { it.length >= 2 }?.let { row.ruleKey = it }
        body.text?.trim()?.takeIf { it.isNotBlank() }?.let { row.ruleValue = asJsonText(it) }
        body.enabled?.let { row.enabled = it }
        row.updatedAt = clock.instant()
        return repo.save(row).toDto()
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID) {
        if (!repo.existsById(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "rule not found")
        repo.deleteById(id)
    }

    private fun FamilyRule.toDto() = FamilyRuleDto(
        id = id,
        ruleKey = ruleKey,
        text = displayText(ruleValue),
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun asJsonText(text: String): String {
            val trimmed = text.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
            val escaped = trimmed.replace("\\", "\\\\").replace("\"", "\\\"")
            return """{"text":"$escaped"}"""
        }

        fun displayText(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            for (field in listOf("text", "note", "caseTitle")) {
                val marker = "\"$field\":\""
                val idx = raw.indexOf(marker)
                if (idx >= 0) {
                    val start = idx + marker.length
                    val end = raw.indexOf('"', start)
                    if (end > start) return raw.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\")
                }
            }
            return raw
        }
    }
}
