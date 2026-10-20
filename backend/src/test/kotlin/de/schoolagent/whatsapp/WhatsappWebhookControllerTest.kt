package de.schoolagent.whatsapp

import de.schoolagent.domain.model.AppUser
import de.schoolagent.health.HealthTracker
import de.schoolagent.ingestion.PhotoIngestionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.UUID

class WhatsappWebhookControllerTest {

    private val childId = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val config = mockk<WhatsAppConfigService>(relaxed = true)
    private val qa = mockk<QuestionAnsweringService>()
    private val client = mockk<WhatsappClient>(relaxed = true)
    private val graph = mockk<GraphWhatsappClient>()
    private val photos = mockk<PhotoIngestionService>()
    private val outbox = mockk<WhatsappOutbox>(relaxed = true)
    private val health = mockk<HealthTracker>(relaxed = true)
    private val morgan = AppUser(name = "morgan", displayName = "Morgan", passwordHash = "x", whatsappNumber = "+12025550111")

    private lateinit var controller: WhatsappWebhookController

    @BeforeEach
    fun setUp() {
        every { config.appSecrets() } returns listOf("test-secret")
        every { config.config() } returns WhatsAppConfigService.Config(
            apiUrl = "https://graph.facebook.com/v23.0",
            phoneNumberId = "1",
            wabaId = null,
            token = "t",
            verifyToken = null,
            appSecret = null,
            frontendUrl = "https://school.example.org",
        )
        every { config.withChild<Unit>(any(), any()) } answers {
            secondArg<() -> Unit>().invoke()
        }
        val inbox = mockk<WhatsappInbox>()
        val processor = WhatsappMessageProcessor(config, qa, graph, photos)
        every { inbox.enqueue(any(), any()) } answers {
            processor.prepare(firstArg(), secondArg())?.let { client.sendText(it.recipient, it.text) }
            Unit
        }
        controller = WhatsappWebhookController(config, outbox, inbox)
    }

    @Test
    fun `replies with the kid-scoped token matching metadata phone_number_id`() {
        every { config.childIdForPhoneNumberId("111") } returns childId
        every { config.userForNumber("12025550111") } returns morgan
        every { qa.answerAndRemember("hi", morgan) } returns ChatAnswer("hello from school")

        val response = controller.inbound(TEXT_PAYLOAD, signature = sign(TEXT_PAYLOAD))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        verify { config.withChild(childId, any()) }
        verify { client.sendText("12025550111", "hello from school") }
    }

    @Test
    fun `pending is answered by the chat agent not a command list`() {
        every { config.childIdForPhoneNumberId("111") } returns childId
        every { config.userForNumber("12025550111") } returns morgan
        every { qa.answerAndRemember("pending", morgan) } returns ChatAnswer("Swimming on Tuesday")

        val payload = TEXT_PAYLOAD.replace("\"hi\"", "\"pending\"")
        val response = controller.inbound(payload, signature = sign(payload))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        verify { qa.answerAndRemember("pending", morgan) }
        verify { client.sendText("12025550111", "Swimming on Tuesday") }
    }

    @Test
    fun `status callbacks without messages are ignored`() {
        val response = controller.inbound(STATUS_PAYLOAD, signature = sign(STATUS_PAYLOAD))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).isEqualTo("ok")
        verify(exactly = 0) { client.sendText(any(), any()) }
        verify(exactly = 0) { qa.answerAndRemember(any(), any()) }
    }

    @Test fun `all signed receipt batches are processed and an invalid signature changes nothing`() {
        val payload = """{"entry":[{"changes":[{"value":{"statuses":[{"id":"one","status":"sent"},{"id":"two","status":"delivered"}]}},{"value":{"statuses":[{"id":"three","status":"read"}]}}]},{"changes":[{"value":{"statuses":[{"id":"four","status":"failed"}]}}]}]}"""
        every { config.appSecrets() } returns listOf("test-secret")
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec("test-secret".toByteArray(), "HmacSHA256")) }
        val signature = "sha256=" + mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        assertThat(controller.inbound(payload, "sha256=invalid").statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        verify(exactly = 0) { outbox.record(any()) }
        assertThat(controller.inbound(payload, signature).statusCode).isEqualTo(HttpStatus.OK)
        verify(exactly = 4) { outbox.record(any()) }
        verify(exactly = 0) { client.sendText(any(), any()) }
    }

    private fun sign(payload: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(javax.crypto.spec.SecretKeySpec("test-secret".toByteArray(), "HmacSHA256"))
        }
        return "sha256=" + mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val TEXT_PAYLOAD = """
            {
              "object": "whatsapp_business_account",
              "entry": [{
                "changes": [{
                  "value": {
                    "messaging_product": "whatsapp",
                    "metadata": { "phone_number_id": "111" },
                    "messages": [{
                      "from": "12025550111",
                      "type": "text",
                      "text": { "body": "hi" }
                    }]
                  }
                }]
              }]
            }
        """.trimIndent()

        private val STATUS_PAYLOAD = """
            {
              "object": "whatsapp_business_account",
              "entry": [{
                "changes": [{
                  "value": {
                    "messaging_product": "whatsapp",
                    "metadata": { "phone_number_id": "111" },
                    "statuses": [{ "status": "delivered" }]
                  }
                }]
              }]
            }
        """.trimIndent()
    }

    @Test fun `missing app secret rejects all inbound activity`() {
        every { config.appSecrets() } returns emptyList()
        assertThat(controller.inbound(TEXT_PAYLOAD, null).statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        verify(exactly = 0) { config.recordWebhook() }
        verify(exactly = 0) { client.sendText(any(), any()) }
        verify(exactly = 0) { outbox.record(any()) }
    }

}
