package de.schoolagent.whatsapp

import org.springframework.stereotype.Service
import java.time.Clock

data class TemplateStatusDto(val name: String, val language: String, val status: String, val error: String? = null, val category: String? = null, val metaStatus: String? = null)

/** Registration is explicit. Readiness refreshes read-only, and failed reads never imply missing templates. */
@Service
class WhatsAppTemplateSync(private val client: GraphWhatsappClient, private val config: WhatsAppConfigService,
                           private val clock: Clock) {
    private var cached: List<TemplateStatusDto> = emptyList()
    private var checkedAt = java.time.Instant.EPOCH
    private var fingerprint = ""
    @Synchronized
    fun list(force: Boolean = false): List<TemplateStatusDto> {
        val cfg = config.config()
        val key = "${cfg.apiUrl}:${cfg.wabaId}:${cfg.frontendUrl}:${cfg.token?.hashCode()}"
        if (!force && key == fingerprint && checkedAt.plusSeconds(60).isAfter(clock.instant())) return cached
        fingerprint = key
        val catalog = WaTemplates.catalog(cfg.frontendUrl)
        cached = if (!config.isConfigured() || cfg.wabaId.isNullOrBlank()) {
            catalog.map { TemplateStatusDto(it.name, it.language, "MISSING", "WhatsApp token, phone or WABA ID is missing") }
        } else try {
            val remote = client.listMessageTemplates()
            catalog.map { spec ->
                val found = remote.find { it.name == spec.name && it.language == spec.language }
                when {
                    found == null -> TemplateStatusDto(spec.name, spec.language, "MISSING")
                    !matches(spec, found) -> TemplateStatusDto(spec.name, spec.language, "MISMATCH", "Meta status ${found.status}, category ${found.category}: components/category differ from the registered utility layout", found.category, found.status)
                    else -> TemplateStatusDto(spec.name, spec.language, found.status, category = found.category, metaStatus = found.status)
                }
            }
        } catch (e: Exception) {
            catalog.map { TemplateStatusDto(it.name, it.language, "ERROR", e.message?.take(250)) }
        }
        checkedAt = clock.instant()
        return cached
    }
    fun ready(message: WaTemplates.Message): Boolean = list().any {
        it.name == message.name && it.language == message.language && it.status == "APPROVED"
    }
    @Synchronized
    fun ensure(): List<TemplateStatusDto> {
        val before = list(force = true)
        // No creates if the authoritative read failed.
        if (before.any { it.status == "ERROR" || it.error != null }) return before
        val errors = mutableMapOf<Pair<String, String>, String>()
        val submitted = mutableSetOf<Pair<String, String>>()
        WaTemplates.catalog(config.config().frontendUrl).forEach { spec ->
            if (before.any { it.name == spec.name && it.language == spec.language && it.status == "MISSING" }) {
                try { client.createMessageTemplate(spec); submitted += spec.name to spec.language }
                catch (e: Exception) { errors[spec.name to spec.language] = e.message?.take(250) ?: "Registration failed" }
            }
        }
        cached = list(force = true).map { row ->
            val key = row.name to row.language
            when {
                errors.containsKey(key) -> row.copy(status = "ERROR", error = errors[key])
                row.status == "MISSING" && key in submitted -> row.copy(status = "SUBMITTED")
                else -> row
            }
        }
        return cached
    }
    internal fun matches(spec: WaTemplates.Spec, found: GraphWhatsappClient.RemoteTemplate): Boolean {
        val components = found.components ?: return false
        fun component(type: String) = components.firstOrNull { it.path("type").asText().equals(type, true) }
        val buttons = component("BUTTONS")?.path("buttons")
        val button = buttons?.firstOrNull()
        return components.size() == (if (spec.button == null) 3 else 4) && found.category == "UTILITY" && component("HEADER")?.path("text")?.asText() == spec.header &&
            component("BODY")?.path("text")?.asText() == spec.body && component("FOOTER")?.path("text")?.asText() == "School Agent" &&
            if (spec.button == null) button == null else buttons?.size() == 1 && button?.path("type")?.asText() == "URL" &&
                button.path("text").asText() == spec.button && button.path("url").asText() == spec.url
    }
}
