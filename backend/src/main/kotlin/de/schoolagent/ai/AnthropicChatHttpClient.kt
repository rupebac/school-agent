package de.schoolagent.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

/**
 * The family's GLM access comes via a Z.ai coding-plan subscription, which
 * serves its quota through the Anthropic-compatible endpoint
 * (https://api.z.ai/api/anthropic) — the OpenAI-style /api/paas/v4 path is
 * pay-as-you-go only and answers "insufficient balance" for plan users.
 * [AnthropicChatHttpClient] therefore speaks the Anthropic messages protocol
 * while [ModelRouter] keeps building OpenAI-shaped requests: this client
 * translates both ways. Instantiated by [AiClientConfig], not a bean itself.
 */
class AnthropicChatHttpClient(
    private val rest: RestTemplate,
    @Value("\${schoolagent.ai.anthropic-version:2023-06-01}") private val apiVersion: String,
) : ChatHttpClient {

    override fun complete(baseUrl: String, apiKey: String, body: String): JsonNode {
        val openAiBody = com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
        val anthropic = JsonNodeFactory.instance.objectNode().apply {
            put("model", openAiBody.path("model").asText())
            put("max_tokens", openAiBody.path("max_tokens").asInt(MAX_TOKENS))
            // structured family calls: no hidden thinking → fast, cheap, parseable
            putObject("thinking").put("type", "disabled")
            openAiBody.path("temperature").takeIf { !it.isMissingNode && !it.isNull }
                ?.let { set<JsonNode>("temperature", it) }
            val system = openAiBody.path("messages").firstOrNull()?.takeIf { it.path("role").asText() == "system" }
                ?.path("content")?.asText()
            if (system != null) put("system", system)
            putArray("messages").addAll(
                openAiBody.path("messages")
                    .filter { it.path("role").asText() != "system" }
                    .map { msg ->
                        JsonNodeFactory.instance.objectNode().apply {
                            put("role", if (msg.path("role").asText() == "assistant") "assistant" else "user")
                            val content = msg.path("content")
                            if (content.isTextual) {
                                put("content", content.asText())
                            } else {
                                // content parts: text stays, image_url data-URIs become anthropic image blocks
                                putArray("content").addAll(
                                    content.map { part -> translatePart(part) },
                                )
                            }
                        }
                    },
            )
        }

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("x-api-key", apiKey)
            set("anthropic-version", apiVersion)
        }
        val response = try {
            // Boot 4 converters run Jackson 3 — fetch as String, parse with our (Jackson 2) mapper
            val raw = rest.postForObject("$baseUrl/v1/messages", HttpEntity(anthropic.toString(), headers), String::class.java)
                ?: throw AiUnavailableException("empty anthropic response")
            com.fasterxml.jackson.databind.ObjectMapper().readTree(raw)
        } catch (e: org.springframework.web.client.RestClientException) {
            throw AiUnavailableException("AI provider unreachable: ${e.message?.take(300)}")
        }

        // normalize Anthropic → OpenAI shape so ModelRouter stays protocol-agnostic
        val text = response.path("content")
            .filter { it.path("type").asText() == "text" }
            .joinToString("") { it.path("text").asText() }
        return JsonNodeFactory.instance.objectNode().apply {
            putArray("choices").addObject().putObject("message").put("content", text)
            putObject("usage").apply {
                put("prompt_tokens", response.path("usage").path("input_tokens").asInt(0))
                put("completion_tokens", response.path("usage").path("output_tokens").asInt(0))
            }
        }
    }

    private fun translatePart(part: JsonNode): JsonNode {
        if (part.path("type").asText() == "image_url") {
            val dataUri = part.path("image_url").path("url").asText()
            val base64 = dataUri.substringAfter("base64,", "")
            val mediaType = Regex("data:([^;]+);").find(dataUri)?.groupValues?.get(1) ?: "image/png"
            return JsonNodeFactory.instance.objectNode().apply {
                put("type", "image")
                putObject("source").apply {
                    put("type", "base64")
                    put("media_type", mediaType)
                    put("data", base64)
                }
            }
        }
        return JsonNodeFactory.instance.objectNode().apply {
            put("type", "text")
            put("text", part.path("text").asText())
        }
    }

    companion object {
        const val MAX_TOKENS = 8192
    }
}

/** Selects the transport by protocol; both implement the same ChatHttpClient port. */
@Configuration
class AiClientConfig {

    @Bean
    fun chatHttpClient(
        @Value("\${schoolagent.ai.protocol:anthropic}") protocol: String,
        @Value("\${schoolagent.ai.anthropic-version:2023-06-01}") anthropicVersion: String,
        rest: RestTemplate,
    ): ChatHttpClient = when (protocol.lowercase()) {
        "openai" -> RestChatHttpClient(rest)
        else -> AnthropicChatHttpClient(rest, anthropicVersion)
    }
}
