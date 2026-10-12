package de.schoolagent.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity

class AnthropicChatHttpClientTest {

    private val mapper = ObjectMapper()

    @Test
    fun `translates openai request to anthropic protocol and normalizes the response`() {
        val rest = mockk<org.springframework.web.client.RestTemplate>()
        val anthropicResponse = mapper.readTree(
            """{"content":[{"type":"thinking","thinking":"internal"},
                 {"type":"text","text":"{\"relevance\":\"INFO\"}"}],
                "usage":{"input_tokens":45,"output_tokens":8}}""",
        )
        val bodySlot = slot<HttpEntity<String>>()
        every {
            rest.postForObject(any<String>(), capture(bodySlot), eq(String::class.java))
        } returns anthropicResponse.toString()

        val client = AnthropicChatHttpClient(rest, apiVersion = "2023-06-01")
        val openAiBody = """{"model":"glm-5.3-flash",
            "messages":[{"role":"system","content":"Answer ONLY as JSON."},
                        {"role":"user","content":"classify this"}],
            "response_format":{"type":"json_object"}}"""

        val result = client.complete("https://api.z.ai/api/anthropic", "key", openAiBody)

        // normalized OpenAI shape
        assertThat(result.path("choices")[0].path("message").path("content").asText())
            .isEqualTo("{\"relevance\":\"INFO\"}")
        assertThat(result.path("usage").path("prompt_tokens").asInt()).isEqualTo(45)
        assertThat(result.path("usage").path("completion_tokens").asInt()).isEqualTo(8)

        // request was translated: system lifted out, thinking disabled, no response_format
        val sent = mapper.readTree(bodySlot.captured.body)
        assertThat(sent.path("system").asText()).isEqualTo("Answer ONLY as JSON.")
        assertThat(sent.path("thinking").path("type").asText()).isEqualTo("disabled")
        assertThat(sent.path("messages").size()).isEqualTo(1)
        assertThat(sent.path("messages")[0].path("role").asText()).isEqualTo("user")
        assertThat(sent.has("response_format")).isFalse()
        assertThat(sent.path("max_tokens").asInt()).isGreaterThan(0)
    }
}
