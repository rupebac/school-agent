package de.schoolagent.whatsapp

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestTemplate

class GraphWhatsappClientTest {

    private val mapper = ObjectMapper()
    private val config = mockk<WhatsAppConfigService>()
    private val rest = mockk<RestTemplate>()

    @Test
    fun `template send matches Meta Cloud API shape with digits-only recipient`() {
        every { config.config() } returns WhatsAppConfigService.Config(
            apiUrl = "https://graph.facebook.com/v25.0",
            phoneNumberId = "111",
            wabaId = null,
            token = "tok",
            verifyToken = null,
            appSecret = null,
            frontendUrl = "https://school.example.org",
        )
        val urlSlot = slot<String>()
        val entitySlot = slot<HttpEntity<*>>()
        every {
            rest.exchange(capture(urlSlot), eq(HttpMethod.POST), capture(entitySlot), eq(String::class.java))
        } returns ResponseEntity.ok("""{"messages":[{"id":"wamid.TEST"}]}""")

        val id = GraphWhatsappClient(config, rest).sendTemplate(
            "+12025550111",
            "school_agent_action_v3_1_en",
            "en_US",
            listOf("Taylor", "Copying fee", "Use a labelled envelope", "5 Oct · Prepare €17.20"),
            "cases/letter",
        )

        assertThat(urlSlot.captured).isEqualTo("https://graph.facebook.com/v25.0/111/messages")
        val body = mapper.readTree(entitySlot.captured.body as String)
        assertThat(body.path("messaging_product").asText()).isEqualTo("whatsapp")
        assertThat(body.path("to").asText()).isEqualTo("12025550111")
        assertThat(body.path("type").asText()).isEqualTo("template")
        assertThat(body.path("template").path("name").asText()).isEqualTo("school_agent_action_v3_1_en")
        assertThat(body.path("template").path("language").path("code").asText()).isEqualTo("en_US")
        val params = body.path("template").path("components")[0].path("parameters")
        assertThat(params[0].path("text").asText()).isEqualTo("Taylor")
        assertThat(params).hasSize(4)
        assertThat(id).isEqualTo("wamid.TEST")
        val button = body.path("template").path("components")[1]
        assertThat(button.path("type").asText()).isEqualTo("button")
        assertThat(button.path("sub_type").asText()).isEqualTo("url")
        assertThat(button.path("parameters")[0].path("text").asText()).isEqualTo("cases/letter")
    }

    @Test
    fun `createMessageTemplate posts a positional utility body to the WABA`() {
        every { config.config() } returns WhatsAppConfigService.Config(
            apiUrl = "https://graph.facebook.com/v25.0",
            phoneNumberId = "111",
            wabaId = "222",
            token = "tok",
            verifyToken = null,
            appSecret = null,
            frontendUrl = "https://school.example.org",
        )
        val urlSlot = slot<String>()
        val entitySlot = slot<HttpEntity<*>>()
        every {
            rest.exchange(capture(urlSlot), eq(HttpMethod.POST), capture(entitySlot), eq(String::class.java))
        } returns ResponseEntity.ok("""{"id":"123","status":"PENDING"}""")

        GraphWhatsappClient(config, rest).createMessageTemplate(WaTemplates.catalog("https://school.example.org").first())

        assertThat(urlSlot.captured).isEqualTo("https://graph.facebook.com/v25.0/222/message_templates")
        val body = mapper.readTree(entitySlot.captured.body as String)
        assertThat(body.path("name").asText()).isEqualTo("school_agent_action_v5_1_en")
        assertThat(body.path("language").asText()).isEqualTo("en_US")
        assertThat(body.path("category").asText()).isEqualTo("UTILITY")
        assertThat(body.path("components")[0].path("type").asText()).isEqualTo("HEADER")
        assertThat(body.path("components")[1].path("text").asText()).contains("{{1}}").contains("{{2}}")
    }

    @Test fun `dynamic child heading is a header component separate from body and button`() {
        every { config.config() } returns WhatsAppConfigService.Config("https://graph.facebook.com/v25.0", "phone", "waba", "t", null, null, "https://school.example.org")
        val entity = slot<HttpEntity<*>>()
        every { rest.exchange(any<String>(), eq(HttpMethod.POST), capture(entity), eq(String::class.java)) } returns ResponseEntity.ok("""{"messages":[{"id":"wamid.plan"}]}""")
        val client = GraphWhatsappClient(config, rest)
        client.sendTemplate("+49123", "school_agent_plan_v5_1_en", "en_US", listOf("Mon 5 Oct", "Bring the book"), "calendar", "Tomorrow for Taylor")
        val components = mapper.readTree(entity.captured.body as String).path("template").path("components")
        assertThat(components.map { it.path("type").asText() }).containsExactly("header", "body", "button")
        assertThat(components[0].path("parameters")[0].path("text").asText()).isEqualTo("Tomorrow for Taylor")
        val spec = WaTemplates.catalog("https://school.example.org").first { it.name == "school_agent_plan_v5_1_en" }
        assertThat(mapper.readTree(client.templatePayload(spec)).path("components")[0].path("example").path("header_text")[0].asText()).isEqualTo("Next week for Taylor")
    }

    @Test fun `Done is a quick reply after Open letter with an opaque notification payload`() {
        every { config.config() } returns WhatsAppConfigService.Config("https://graph.facebook.com/v25.0", "111", "222", "test-token", null, null, "https://school.example.org")
        val entity = slot<HttpEntity<*>>()
        every { rest.exchange(any<String>(), eq(HttpMethod.POST), capture(entity), eq(String::class.java)) } returns
            ResponseEntity.ok("""{"messages":[{"id":"synthetic-message"}]}""")
        val client = GraphWhatsappClient(config, rest)
        val payload = WhatsappDoneService.payload(java.util.UUID.randomUUID())
        client.sendTemplate("+12025550111", WaTemplates.name(WaTemplates.Kind.ACTION, 1), "en_US",
            listOf("Reading book", "Bring the book"), "cases/synthetic", "Synthetic Child", payload)
        val buttons = mapper.readTree(entity.captured.body as String).path("template").path("components").filter { it.path("type").asText() == "button" }
        assertThat(buttons.map { it.path("sub_type").asText() }).containsExactly("url", "quick_reply")
        assertThat(buttons[1].path("index").asText()).isEqualTo("1")
        assertThat(buttons[1].path("parameters")[0].path("type").asText()).isEqualTo("payload")
        assertThat(buttons[1].path("parameters")[0].path("payload").asText()).isEqualTo(payload)
        val spec = WaTemplates.catalog("https://school.example.org").first()
        val layout = mapper.readTree(client.templatePayload(spec)).path("components").last().path("buttons")
        assertThat(layout.map { it.path("type").asText() }).containsExactly("URL", "QUICK_REPLY")
        assertThat(layout[1].path("text").asText()).isEqualTo("Mark as done")
    }

    @Test fun `editing uses the existing template ID and only sends editable components`() {
        every { config.config() } returns WhatsAppConfigService.Config("https://graph.facebook.com/v25.0", "111", "222", "test-token", null, null, "https://school.example.org")
        val entity = slot<HttpEntity<*>>()
        every { rest.exchange(eq("https://graph.facebook.com/v25.0/123"), eq(HttpMethod.POST), capture(entity), eq(String::class.java)) } returns ResponseEntity.ok("""{"success":true}""")
        GraphWhatsappClient(config, rest).editMessageTemplate("123", WaTemplates.catalog("https://school.example.org").first())
        val body = mapper.readTree(entity.captured.body as String)
        assertThat(body.fieldNames().asSequence().toList()).containsExactly("components")
        assertThat(body.path("components").last().path("buttons")).hasSize(2)
    }

    @Test fun `listing follows all Graph pages and preserves component schemas`() {
        every { config.config() } returns WhatsAppConfigService.Config("https://graph.facebook.com/v25.0", "phone", "waba", "t", null, null, "https://school.example.org")
        every { rest.exchange(any<String>(), eq(HttpMethod.GET), any<HttpEntity<*>>(), eq(String::class.java)) } answers {
            ResponseEntity.ok(if (firstArg<String>().contains("limit=100"))
                """{"data":[{"name":"one","language":"en_US","status":"APPROVED","category":"UTILITY","components":[{"type":"BODY","text":"Hello"}]}],"paging":{"next":"https://graph.facebook.com/v25.0/waba/message_templates?after=next"}}"""
            else """{"data":[{"name":"two","language":"es","status":"PENDING","category":"UTILITY","components":[]}]}""")
        }
        val result = GraphWhatsappClient(config, rest).listMessageTemplates()
        assertThat(result.map { it.name }).containsExactly("one", "two")
        assertThat(result.first().components!!.first().path("text").asText()).isEqualTo("Hello")
    }

    @Test
    fun `free-form text also strips plus from the recipient`() {
        every { config.config() } returns WhatsAppConfigService.Config(
            apiUrl = "https://graph.facebook.com/v25.0",
            phoneNumberId = "1",
            wabaId = null,
            token = "tok",
            verifyToken = null,
            appSecret = null,
            frontendUrl = "https://school.example.org",
        )
        val entitySlot = slot<HttpEntity<*>>()
        every {
            rest.exchange(any<String>(), eq(HttpMethod.POST), capture(entitySlot), eq(String::class.java))
        } returns ResponseEntity.ok("""{"messages":[{"id":"wamid.T"}]}""")

        GraphWhatsappClient(config, rest).sendText("+12025550111", "hello")

        val body = mapper.readTree(entitySlot.captured.body as String)
        assertThat(body.path("to").asText()).isEqualTo("12025550111")
        assertThat(body.path("type").asText()).isEqualTo("text")
    }

    @Test
    fun `subscribeWaba posts to the WABA subscribed_apps edge`() {
        every { config.config() } returns WhatsAppConfigService.Config(
            apiUrl = "https://graph.facebook.com/v25.0",
            phoneNumberId = "111",
            wabaId = "222",
            token = "tok",
            verifyToken = null,
            appSecret = null,
            frontendUrl = "https://school.example.org",
        )
        every {
            rest.exchange(any<String>(), any<HttpMethod>(), any<HttpEntity<*>>(), eq(String::class.java))
        } answers {
            val url = firstArg<String>()
            val method = secondArg<HttpMethod>()
            val json = when {
                method == HttpMethod.POST && url.endsWith("/222/subscribed_apps") ->
                    """{"success":true}"""
                method == HttpMethod.GET && url.endsWith("/222/subscribed_apps") ->
                    """{"data":[{"whatsapp_business_api_data":{"id":"app1"}}]}"""
                else -> error("unexpected $method $url")
            }
            ResponseEntity.ok(json)
        }

        val result = GraphWhatsappClient(config, rest).subscribeWaba()
        assertThat(result["wabaId"]).isEqualTo("222")
        assertThat(result["subscribe"]).contains("success")
    }

    @Test
    fun `subscribeWaba fails when WABA id is missing`() {
        every { config.config() } returns WhatsAppConfigService.Config(
            apiUrl = "https://graph.facebook.com/v25.0",
            phoneNumberId = "111",
            wabaId = null,
            token = "tok",
            verifyToken = null,
            appSecret = null,
            frontendUrl = "https://school.example.org",
        )
        val ex = assertThrows<WhatsappSendException> {
            GraphWhatsappClient(config, rest).subscribeWaba()
        }
        assertThat(ex.message).contains("WABA")
    }
    @Test
    fun `long replies preserve all paragraphs and URLs in numbered messages`() {
        every { config.config() } returns WhatsAppConfigService.Config(
            apiUrl = "https://graph.facebook.com/v25.0", phoneNumberId = "1", wabaId = null,
            token = "tok", verifyToken = null, appSecret = null, frontendUrl = "https://school.example.org",
        )
        val sent = mutableListOf<HttpEntity<*>>()
        every { rest.exchange(any<String>(), eq(HttpMethod.POST), capture(sent), eq(String::class.java)) } returns
            ResponseEntity.ok("""{"messages":[{"id":"test"}]}""")
        val paragraphs = (1..60).map { "• Jamie: school matter $it — bring the requested documents 📚\n↗ https://school.example.org/cases/${java.util.UUID.randomUUID()}" }
        GraphWhatsappClient(config, rest).sendText("+491234", paragraphs.joinToString("\n\n"))
        val bodies = sent.map { mapper.readTree(it.body as String).path("text") }
        assertThat(bodies.size).isGreaterThan(1)
        bodies.forEachIndexed { index, body ->
            assertThat(body.path("body").asText()).startsWith("(${index + 1}/${bodies.size})\n").hasSizeLessThanOrEqualTo(3500)
            assertThat(body.path("preview_url").asBoolean()).isFalse()
        }
        val reconstructed = bodies.joinToString("\n\n") { it.path("body").asText().substringAfter('\n') }
        assertThat(reconstructed).isEqualTo(paragraphs.joinToString("\n\n"))
    }

    @Test
    fun `splitting unbroken unicode never cuts a surrogate pair`() {
        val text = "📚".repeat(4000)
        val parts = WhatsAppText.parts(text)
        assertThat(parts.joinToString("") { it.substringAfter('\n') }).isEqualTo(text)
        parts.forEach { assertThat(it.last().isHighSurrogate()).isFalse() }
    }

}
