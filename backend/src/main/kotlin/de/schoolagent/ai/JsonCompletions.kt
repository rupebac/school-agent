package de.schoolagent.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** Models wrap JSON in fences or chatter — strip that before Jackson. */
object JsonCompletions {
    private val mapper = ObjectMapper()

    fun tree(raw: String): JsonNode = mapper.readTree(clean(raw))

    fun field(raw: String, name: String): String? =
        runCatching {
            tree(raw).path(name).asText(null)?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull()

    fun clean(raw: String): String {
        var cleaned = raw.trim()
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        if (!cleaned.startsWith("{")) {
            cleaned = Regex("\\{.*}", RegexOption.DOT_MATCHES_ALL).find(cleaned)?.value ?: cleaned
        }
        return cleaned
    }
}
