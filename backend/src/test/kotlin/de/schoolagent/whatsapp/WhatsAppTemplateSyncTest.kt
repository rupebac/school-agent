package de.schoolagent.whatsapp

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock

class WhatsAppTemplateSyncTest {
    private val client = mockk<GraphWhatsappClient>()
    private val config = mockk<WhatsAppConfigService>()
    private val catalog = WaTemplates.catalog("https://school.example.org")
    private val mapper = ObjectMapper()
    private lateinit var sync: WhatsAppTemplateSync
    @BeforeEach fun setup() {
        every { config.isConfigured() } returns true
        every { config.config() } returns WhatsAppConfigService.Config("https://graph.facebook.com/v25.0", "1", "waba", "t", null, null, "https://school.example.org")
        sync = WhatsAppTemplateSync(client, config, Clock.systemUTC())
    }
    private fun remote(status: String) = catalog.map { spec ->
        val components = mapper.createArrayNode().apply {
            addObject().put("type", "HEADER").put("text", spec.header)
            addObject().put("type", "BODY").put("text", spec.body)
            addObject().put("type", "FOOTER").put("text", "School Agent")
            if (spec.button != null) addObject().put("type", "BUTTONS").putArray("buttons").addObject()
                .put("type", "URL").put("text", spec.button).put("url", spec.url)
        }
        GraphWhatsappClient.RemoteTemplate(spec.name, spec.language, status, components)
    }
    @Test fun `registration submits missing templates but pending cannot send`() {
        every { client.listMessageTemplates() } returns emptyList() andThen remote("PENDING")
        every { client.createMessageTemplate(any()) } just Runs
        assertThat(sync.ensure().all { it.status == "PENDING" }).isTrue()
        verify(exactly = catalog.size) { client.createMessageTemplate(any()) }
        assertThat(sync.ready(WaTemplates.Message(catalog.first().name, "en_US", emptyList()))).isFalse()
    }
    @Test fun `failed list is an error and never attempts registration`() {
        every { client.listMessageTemplates() } throws WhatsappSendException("No permission")
        assertThat(sync.ensure().all { it.status == "ERROR" }).isTrue()
        verify(exactly = 0) { client.createMessageTemplate(any()) }
    }
    @Test fun `failed creation is an error rather than fabricated pending approval`() {
        every { client.listMessageTemplates() } returns emptyList()
        every { client.createMessageTemplate(any()) } throws WhatsappSendException("Invalid template")
        assertThat(sync.ensure().all { it.status == "ERROR" && it.error == "Invalid template" }).isTrue()
    }
    @Test fun `read-only refresh sees approval changes without recreating templates`() {
        every { client.listMessageTemplates() } returns remote("PENDING") andThen remote("APPROVED") andThen remote("PAUSED")
        assertThat(sync.list().all { it.status == "PENDING" }).isTrue()
        assertThat(sync.list(force = true).all { it.status == "APPROVED" }).isTrue()
        assertThat(sync.list(force = true).all { it.status == "PAUSED" }).isTrue()
        verify(exactly = 0) { client.createMessageTemplate(any()) }
    }
    @Test fun `approval for a mismatched body or category cannot enable sends`() {
        every { client.listMessageTemplates() } returns remote("APPROVED").map { it.copy(category = "MARKETING") }
        assertThat(sync.list().all { it.status == "MISMATCH" }).isTrue()
        verify(exactly = 0) { client.createMessageTemplate(any()) }
    }
}
