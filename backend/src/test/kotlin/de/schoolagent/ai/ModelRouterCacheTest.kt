package de.schoolagent.ai

import com.fasterxml.jackson.databind.ObjectMapper
import de.schoolagent.domain.enums.ModelRole
import de.schoolagent.domain.model.AiUsage
import de.schoolagent.domain.model.ModelConfig
import de.schoolagent.domain.repo.AiUsageRepository
import de.schoolagent.domain.repo.ModelConfigRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class ModelRouterCacheTest {
    private val config = mockk<ModelConfigRepository> {
        every { findByRole(any()) } returns ModelConfig(role = ModelRole.TEXT, provider = "test", modelName = "test-model")
    }
    private val usage = mockk<AiUsageRepository> { every { save(any<AiUsage>()) } answers { firstArg() } }
    private val chat = mockk<ChatHttpClient>()
    private val data = ConcurrentHashMap<String, AiResponse>()
    private val repo = mockk<AiResponseRepository> {
        every { findById(any()) } answers { Optional.ofNullable(data[firstArg<String>()]) }
        every { save(any<AiResponse>()) } answers { firstArg<AiResponse>().also { data[it.id] = it } }
        every { deleteById(any()) } answers { data.remove(firstArg<String>()); Unit }
    }
    private val router = ModelRouter(config, usage, chat, Clock.systemUTC(), "test", "test", AiResponseCache(repo))
    @Test fun `simultaneous identical requests share one provider response`() {
        every { chat.complete(any(), any(), any()) } returns ObjectMapper().readTree("""{"choices":[{"message":{"content":"{\"text\":\"Saved\"}"}}]}""")
        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            val results = (1..10).map { pool.submit<String> { router.complete(ModelRole.TEXT, "system", "same") } }
            assertThat(results.map { it.get() }.distinct()).hasSize(1)
        }
        verify(exactly = 1) { chat.complete(any(), any(), any()) }
    }
    @Test fun `provider outages are not immediately retried as JSON format failures`() {
        every { chat.complete(any(), any(), any()) } throws AiUnavailableException("provider 429 quota exceeded")
        assertThrows<AiUnavailableException> { router.complete(ModelRole.TEXT, "system", "same") }
        verify(exactly = 1) { chat.complete(any(), any(), any()) }
        assertThat(data).isEmpty()
    }
    private fun response(content: String) = ObjectMapper().createObjectNode().apply {
        putArray("choices").addObject().putObject("message").put("content", content)
    }
    @Test fun `schema validation prevents poisoning the cache and repairs previously cached bad answers`() {
        val invalid = """{"title":""}"""; val valid = """{"title":"Mathematics exam"}"""
        every { chat.complete(any(), any(), any()) } returns response(invalid) andThen response(valid)
        // Simulate an answer cached before schema validation was introduced.
        assertThat(router.complete(ModelRole.TEXT, "record", "same")).isEqualTo(invalid)
        val validate: (String) -> Unit = { require(JsonCompletions.tree(it).path("title").asText().isNotBlank()) }
        assertThat(router.completeValidated(ModelRole.TEXT, "record", "same", "school-record", validate)).isEqualTo(valid)
        assertThat(router.completeValidated(ModelRole.TEXT, "record", "same", "school-record", validate)).isEqualTo(valid)
        verify(exactly = 2) { chat.complete(any(), any(), any()) }
        assertThat(data.values.map { it.response }).containsExactly(valid)
    }
    @Test fun `fresh schema-invalid answers are not cached`() {
        every { chat.complete(any(), any(), any()) } returns response("""{"title":""}""")
        assertThrows<IllegalArgumentException> { router.completeValidated(ModelRole.TEXT, "bad", "same", "school-record") {
            require(JsonCompletions.tree(it).path("title").asText().isNotBlank())
        } }
        assertThat(data).isEmpty()
    }
}
