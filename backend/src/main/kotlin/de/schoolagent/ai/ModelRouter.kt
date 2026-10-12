package de.schoolagent.ai

import com.fasterxml.jackson.databind.JsonNode
import de.schoolagent.domain.enums.ModelRole
import de.schoolagent.domain.model.AiUsage
import de.schoolagent.domain.repo.AiUsageRepository
import de.schoolagent.domain.repo.ModelConfigRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock

/** Chat-completion transport; faked in tests. */
interface ChatHttpClient {
    fun complete(baseUrl: String, apiKey: String, body: String): JsonNode
}

class AiUnavailableException(message: String) : RuntimeException(message)

/**
 * Two editable models (spec §13/§14): TEXT for all language work,
 * VISION for page images. Every call is usage-logged (§41).
 * A disabled role makes the router report unavailable() — callers then
 * degrade to deterministic behavior.
 */
@Service
class ModelRouter(
    private val configRepo: ModelConfigRepository,
    private val usageRepo: AiUsageRepository,
    private val chat: ChatHttpClient,
    private val clock: Clock,
    @Value("\${schoolagent.ai.base-url:https://api.openai.com/v1}") private val baseUrl: String,
    @Value("\${schoolagent.ai.api-key:}") private val apiKey: String,
    private val responseCache: AiResponseCache? = null,
) {

    private val requestLocks = Array(64) { Any() }
    private val log = LoggerFactory.getLogger(javaClass)

    fun available(role: ModelRole): Boolean {
        if (apiKey.isBlank()) return false
        val config = configRepo.findByRole(role) ?: return false
        return config.enabled && config.modelName.isNotBlank()
    }

    /**
     * JSON-mode completion. Returns the raw JSON string, or throws
     * [AiUnavailableException] when the role is not usable.
     */
    fun complete(role: ModelRole, system: String, user: String, purpose: String = "completion"): String {
        return completeValidated(role, system, user, purpose) {}
    }

    /** Validate both cached and fresh structured answers before allowing them into the cache. */
    fun completeValidated(role: ModelRole, system: String, user: String, purpose: String, validate: (String) -> Unit): String {
        val config = configRepo.findByRole(role)
            ?: throw AiUnavailableException("no model configured for role $role")
        if (!config.enabled || config.modelName.isBlank()) {
            throw AiUnavailableException("model for role $role is disabled")
        }
        if (apiKey.isBlank()) throw AiUnavailableException("no AI API key configured (SCHOOLAGENT_AI_API_KEY)")

        val body = buildBody(config, system, user, jsonMode = true)

        return cached(body, validate) {
            val response = try {
                chat.complete(baseUrl, apiKey, body)
            } catch (e: AiUnavailableException) {
                if (!e.message.orEmpty().contains("response_format", ignoreCase = true)) throw e
                // some OpenAI-compatible providers reject response_format — retry without it
                chat.complete(baseUrl, apiKey, buildBody(config, system, user, jsonMode = false))
            }
            val content = response.path("choices").firstOrNull()?.path("message")?.path("content")?.asText(null)
                ?: throw AiUnavailableException("completion without content")
            usageRepo.save(
                AiUsage(
                    role = role.name,
                    model = config.modelName,
                    purpose = purpose,
                    inputTokens = response.path("usage").path("prompt_tokens").asInt(0),
                    outputTokens = response.path("usage").path("completion_tokens").asInt(0),
                    calls = 1,
                    createdAt = clock.instant(),
                ),
            )
            log.debug("model call role={} model={} tokens logged", role, config.modelName)
            content
        }
    }

    /**
     * JSON-mode completion with page images (vision role, spec §13): images
     * travel as OpenAI-style content parts and are translated per protocol.
     */
    fun completeWithImages(role: ModelRole, system: String, user: String, imagesPng: List<ByteArray>): String {
        val config = configRepo.findByRole(role)
            ?: throw AiUnavailableException("no model configured for role $role")
        if (!config.enabled || config.modelName.isBlank()) {
            throw AiUnavailableException("model for role $role is disabled")
        }
        if (apiKey.isBlank()) throw AiUnavailableException("no AI API key configured (SCHOOLAGENT_AI_API_KEY)")
        if (imagesPng.isEmpty()) throw AiUnavailableException("no images provided")

        val body = buildBody(config, system, user, jsonMode = true, imagesPng = imagesPng)
        return cached(body) {
            val response = try {
                chat.complete(baseUrl, apiKey, body)
            } catch (e: AiUnavailableException) {
                if (!e.message.orEmpty().contains("response_format", ignoreCase = true)) throw e
                chat.complete(baseUrl, apiKey, buildBody(config, system, user, jsonMode = false, imagesPng = imagesPng))
            }
            val content = response.path("choices").firstOrNull()?.path("message")?.path("content")?.asText(null)
                ?: throw AiUnavailableException("completion without content")
            usageRepo.save(
                AiUsage(
                    role = role.name,
                    model = config.modelName,
                    purpose = "vision",
                    inputTokens = response.path("usage").path("prompt_tokens").asInt(0),
                    outputTokens = response.path("usage").path("completion_tokens").asInt(0),
                    calls = 1,
                    createdAt = clock.instant(),
                ),
            )
            content
        }
    }

    private fun cached(body: String, validate: (String) -> Unit = {}, request: () -> String): String {
        val key = de.schoolagent.email.EmailContent.hash("v1:$baseUrl:$body")
        return synchronized(requestLocks[(key.hashCode() and Int.MAX_VALUE) % requestLocks.size]) {
            val saved = responseCache?.get(key)
            if (saved != null) {
                if (runCatching { validate(saved) }.isSuccess) return@synchronized saved
                responseCache?.evict(key)
            }
            request().also { value ->
                validate(value)
                // Failed/invalid responses remain retryable; cache only valid structured output.
                if (runCatching { JsonCompletions.tree(value).isObject }.getOrDefault(false)) responseCache?.put(key, value)
            }
        }
    }

    private fun buildBody(
        config: de.schoolagent.domain.model.ModelConfig,
        system: String,
        user: String,
        jsonMode: Boolean,
        imagesPng: List<ByteArray> = emptyList(),
    ): String {
        val body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().apply {
            put("model", config.modelName)
            put("max_tokens", 8192)
            putArray("messages").apply {
                addObject().apply {
                    put("role", "system")
                    put("content", system)
                }
                addObject().apply {
                    put("role", "user")
                    if (imagesPng.isEmpty()) {
                        put("content", user)
                    } else {
                        val parts = putArray("content")
                        parts.addObject().apply {
                            put("type", "text")
                            put("text", user)
                        }
                        imagesPng.forEach { png ->
                            parts.addObject().apply {
                                put("type", "image_url")
                                putObject("image_url")
                                    .put("url", "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png))
                            }
                        }
                    }
                }
            }
            if (jsonMode) putObject("response_format").put("type", "json_object")
            config.temperature?.let { put("temperature", it.toDouble()) }
        }
        return body.toString()
    }
}

/** OpenAI-protocol transport; selected via schoolagent.ai.protocol=openai. */
class RestChatHttpClient(private val rest: org.springframework.web.client.RestTemplate) : ChatHttpClient {
    override fun complete(baseUrl: String, apiKey: String, body: String): JsonNode {
        val headers = org.springframework.http.HttpHeaders().apply {
            setBearerAuth(apiKey)
            set("Content-Type", "application/json")
        }
        return try {
            // Boot 4 converters run Jackson 3 — fetch as String, parse with our (Jackson 2) mapper
            val raw = rest.postForObject(
                "$baseUrl/chat/completions",
                org.springframework.http.HttpEntity(body, headers),
                String::class.java,
            )!!
            com.fasterxml.jackson.databind.ObjectMapper().readTree(raw)
        } catch (e: org.springframework.web.client.RestClientException) {
            throw AiUnavailableException("AI provider unreachable: ${e.message}")
        }
    }
}
