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
            if (spec.button != null) {
                val buttons = addObject().put("type", "BUTTONS").putArray("buttons")
                buttons.addObject().put("type", "URL").put("text", spec.button).put("url", spec.url)
                spec.doneButton?.let { buttons.addObject().put("type", "QUICK_REPLY").put("text", it) }
            }
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
        assertThat(sync.ensure().all { it.status == "ERROR" && it.error == "Template submission failed; check Meta and retry" }).isTrue()
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

    private fun withoutDone() = remote("APPROVED").mapIndexed { index, row ->
        val components = row.components!!.deepCopy<com.fasterxml.jackson.databind.JsonNode>()
        components.firstOrNull { it.path("type").asText() == "BUTTONS" }?.path("buttons")?.let {
            if (it.size() == 2) (it as com.fasterxml.jackson.databind.node.ArrayNode).remove(1)
        }
        row.copy(id = (index + 100).toString(), components = components)
    }

    @Test fun `sync edits existing action templates in place and holds sends until matching approval`() {
        every { client.listMessageTemplates() } returns withoutDone() andThen remote("PENDING")
        every { client.editMessageTemplate(any(), any()) } just Runs
        assertThat(sync.ensure().all { it.status == "PENDING" }).isTrue()
        verify(exactly = 10) { client.editMessageTemplate(any(), match { it.doneButton != null }) }
        verify(exactly = 0) { client.createMessageTemplate(any()) }
        assertThat(sync.ready(WaTemplates.Message(catalog.first().name, "en_US", emptyList()))).isFalse()
        every { client.listMessageTemplates() } returns remote("APPROVED")
        sync.list(force = true)
        assertThat(sync.ready(WaTemplates.Message(catalog.first().name, "en_US", emptyList()))).isTrue()
    }

    @Test fun `pending mismatches and unrelated category differences are never resubmitted`() {
        every { client.listMessageTemplates() } returns withoutDone().map { it.copy(status = "PENDING") }
        sync.ensure()
        every { client.listMessageTemplates() } returns withoutDone().map { it.copy(category = "MARKETING") }
        sync.ensure()
        verify(exactly = 0) { client.editMessageTemplate(any(), any()) }
        verify(exactly = 0) { client.createMessageTemplate(any()) }
    }

    @Test fun `an eventually consistent read after editing never makes the old layout ready`() {
        every { client.listMessageTemplates() } returns withoutDone()
        every { client.editMessageTemplate(any(), any()) } just Runs
        val rows = sync.ensure()
        assertThat(rows.filter { it.name.contains("_action_") }.all { it.status == "SUBMITTED" }).isTrue()
        assertThat(sync.ready(WaTemplates.Message(catalog.first().name, "en_US", emptyList()))).isFalse()
    }
}
