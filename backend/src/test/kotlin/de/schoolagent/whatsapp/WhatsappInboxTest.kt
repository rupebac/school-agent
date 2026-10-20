package de.schoolagent.whatsapp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.schoolagent.domain.model.AppUser
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.*

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class WhatsappInboxTest {
    companion object {
        @Container @ServiceConnection @JvmStatic val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17")
    }
    @Autowired lateinit var jdbc: JdbcTemplate
    private val processor = mockk<WhatsappMessageProcessor>()
    private val client = mockk<WhatsappClient>(relaxed = true)
    private val config = mockk<WhatsAppConfigService>()
    private val user = AppUser(name = "parent", displayName = "Parent", passwordHash = "synthetic")
    private val clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC)
    private val json = jacksonObjectMapper()
    private fun queue() = WhatsappInbox(jdbc, processor, client, config, clock)
    private fun enqueue(queue: WhatsappInbox) = queue.enqueue(json.readTree("""{"metadata":{"phone_number_id":"synthetic-phone"}}"""),
        json.readTree("""{"id":"synthetic-message","from":"12025550111","type":"text","text":{"body":"Agenda"}}"""))
    @BeforeEach fun setup() {
        jdbc.update("delete from whatsapp_inbound")
        every { processor.prepare(any(), any()) } returns WhatsappMessageProcessor.PreparedReply("12025550111", "School agenda", null, user.id.toString(), WhatsappMessageProcessor.accessStamp(user))
        every { config.userForNumber(any()) } returns user
        every { config.withChild<Unit>(any(), any()) } answers { secondArg<() -> Unit>().invoke() }
    }
    @AfterEach fun cleanup() { jdbc.update("delete from whatsapp_inbound") }
    @Test fun `duplicate deliveries survive a worker restart and send once`() {
        enqueue(queue()); enqueue(queue())
        assertThat(jdbc.queryForObject("select count(*) from whatsapp_inbound", Int::class.java)).isEqualTo(1)
        queue().drain(); enqueue(queue()); queue().drain()
        verify(exactly = 1) { processor.prepare(any(), any()) }
        verify(exactly = 1) { client.sendText(any(), any()) }
        assertThat(queue().statusCounts()).containsEntry("DONE", 1)
        assertThat(jdbc.queryForObject("select payload from whatsapp_inbound", String::class.java)).isNull()
    }
    @Test fun `preparation failures retry but uncertain sends never automatically duplicate`() {
        every { processor.prepare(any(), any()) } throws IllegalStateException("synthetic") andThen
            WhatsappMessageProcessor.PreparedReply("12025550111", "School agenda", null, user.id.toString(), WhatsappMessageProcessor.accessStamp(user))
        enqueue(queue()); queue().drain()
        assertThat(queue().statusCounts()).containsEntry("RETRY", 1)
        jdbc.update("update whatsapp_inbound set next_attempt_at=created_at")
        every { client.sendText(any(), any()) } throws WhatsappSendException("timeout", ambiguous = true)
        queue().drain(); queue().drain(); enqueue(queue()); queue().drain()
        assertThat(queue().statusCounts()).containsEntry("UNKNOWN", 1)
        verify(exactly = 1) { client.sendText(any(), any()) }
    }
}
