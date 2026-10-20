package de.schoolagent.whatsapp

import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

/** Outbound send port — faked in tests, generic provider in production. */
interface WhatsappClient {
    /** Chat replies only. Meta drops free-form text outside the 24h window. */
    fun sendText(toNumber: String, text: String)

    /**
     * Template message — required for the first outbound (and after the window).
     * [bodyParams] fills positional `{{1}}`, `{{2}}`, … in the template body.
     */
    fun sendTemplate(
        toNumber: String,
        name: String,
        language: String = "en_US",
        bodyParams: List<String> = emptyList(),
        urlSuffix: String? = null,
        headerText: String? = null,
        quickReplyPayload: String? = null,
    ): String
}

class WhatsappSendException(message: String, val retryable: Boolean = false, val ambiguous: Boolean = false) : RuntimeException(message)

/**
 * Cloud-API-shaped client: POST {apiUrl}/{phoneNumberId}/messages with a
 * bearer token. Meta's Graph API is the default; any gateway exposing the
 * same shape (self-hosted bridges etc.) works by changing the base URL.
 */
@Service
class GraphWhatsappClient(
    private val config: WhatsAppConfigService,
    private val rest: RestTemplate,
) : WhatsappClient {

    override fun sendText(toNumber: String, text: String) {
        for (part in WhatsAppText.parts(text)) {
            val body = factory.objectNode().apply {
                put("messaging_product", "whatsapp")
                put("to", waId(toNumber))
                put("type", "text")
                putObject("text").put("body", part).put("preview_url", false)
            }
            postMessage(body)
        }
    }

    override fun sendTemplate(toNumber: String, name: String, language: String, bodyParams: List<String>, urlSuffix: String?, headerText: String?, quickReplyPayload: String?): String {
        val body = factory.objectNode().apply {
            put("messaging_product", "whatsapp")
            put("to", waId(toNumber))
            put("type", "template")
            putObject("template").apply {
                put("name", name)
                putObject("language").put("code", language)
                val components = putArray("components")
                headerText?.let { text ->
                    components.addObject().put("type", "header").putArray("parameters")
                        .addObject().put("type", "text").put("text", text)
                }
                if (bodyParams.isNotEmpty()) {
                    components.addObject().apply {
                        put("type", "body")
                        val params = putArray("parameters")
                        bodyParams.forEach { value ->
                            params.addObject().apply {
                                put("type", "text")
                                put("text", value)
                            }
                        }
                    }
                }
                urlSuffix?.let { suffix ->
                    components.addObject().apply {
                        put("type", "button"); put("sub_type", "url"); put("index", "0")
                        putArray("parameters").addObject().put("type", "text").put("text", suffix)
                    }
                }
                quickReplyPayload?.let { payload ->
                    components.addObject().apply {
                        put("type", "button"); put("sub_type", "quick_reply"); put("index", "1")
                        putArray("parameters").addObject().put("type", "payload").put("payload", payload)
                    }
                }
            }
        }
        return postMessage(body).path("messages").path(0).path("id").asText(null)
            ?.takeIf { it.isNotBlank() } ?: throw WhatsappSendException("Meta accepted no message ID", ambiguous = true)
    }

    data class RemoteTemplate(val name: String, val language: String, val status: String,
                              val components: com.fasterxml.jackson.databind.JsonNode? = null, val category: String = "UTILITY",
                              val id: String? = null)

    fun listMessageTemplates(): List<RemoteTemplate> {
        val cfg = config.config()
        val wabaId = cfg.wabaId?.trim()?.takeIf { it.isNotBlank() }
            ?: throw WhatsappSendException("WhatsApp Business Account ID (WABA) is missing")
        var url: String? = "${cfg.apiUrl}/$wabaId/message_templates?limit=100&fields=id,name,language,status,components,category"
        val result = mutableListOf<RemoteTemplate>()
        val seen = mutableSetOf<String>()
        while (url != null) {
            require(seen.add(url)) { "Graph pagination repeated a page" }
            // Never forward the vault token to a pagination URL outside the configured Graph origin.
            require(java.net.URI(url).let { page -> java.net.URI(cfg.apiUrl).let { base -> page.scheme == base.scheme && page.rawAuthority == base.rawAuthority } }) { "Invalid Graph pagination origin" }
            val node = graphJson("GET", url)
            result += node.path("data").map {
                RemoteTemplate(it.path("name").asText(), it.path("language").asText(), it.path("status").asText("UNKNOWN"),
                    it.path("components"), it.path("category").asText(), it.path("id").asText(null))
            }.filter { it.name.isNotBlank() }
            url = node.path("paging").path("next").asText(null)?.takeIf { it.isNotBlank() }
        }
        return result
    }

    fun templatePayload(spec: WaTemplates.Spec): String = factory.objectNode().apply {
        put("name", spec.name); put("language", spec.language); put("category", "UTILITY"); put("parameter_format", "POSITIONAL")
        val components = putArray("components")
        components.addObject().apply {
            put("type", "HEADER"); put("format", "TEXT"); put("text", spec.header)
            spec.headerExample?.let { putObject("example").putArray("header_text").add(it) }
        }
        components.addObject().apply {
            put("type", "BODY"); put("text", spec.body)
            val examples = putObject("example").putArray("body_text").addArray()
            spec.examples.forEach { examples.add(it) }
        }
        components.addObject().put("type", "FOOTER").put("text", "School Agent")
        if (spec.button != null) components.addObject().apply {
            put("type", "BUTTONS")
            val buttons = putArray("buttons")
            buttons.addObject().apply {
                put("type", "URL"); put("text", spec.button); put("url", spec.url)
                putArray("example").add(spec.url!!.replace("{{1}}", "cases/6d6970fa-a143-437a-a97f-843f1f5e17ed"))
            }
            spec.doneButton?.let { buttons.addObject().put("type", "QUICK_REPLY").put("text", it) }
        }
    }.toString()

    fun createMessageTemplate(spec: WaTemplates.Spec) {
        val cfg = config.config()
        val wabaId = cfg.wabaId?.takeIf { it.isNotBlank() } ?: throw WhatsappSendException("WABA ID is missing")
        graphJson("POST", "${cfg.apiUrl}/$wabaId/message_templates", templatePayload(spec))
    }

    /** Edit the existing language variant in place; Meta reviews the changed components again. */
    fun editMessageTemplate(id: String, spec: WaTemplates.Spec) {
        require(id.matches(Regex("[0-9]+"))) { "Invalid Meta template identity" }
        val body = factory.objectNode().set<com.fasterxml.jackson.databind.JsonNode>("components",
            mapper.readTree(templatePayload(spec)).path("components"))
        graphJson("POST", "${config.config().apiUrl}/$id", body.toString())
    }

    /**
     * Attach this app to the stored WABA so live inbound chats are POSTed
     * to the callback URL (dashboard Test POSTs do not require this).
     * WABA ID is saved in Settings — Graph does not expose it as a field
     * on the phone-number node (`whatsapp_business_account` is invalid).
     */
    fun subscribeWaba(): Map<String, String> {
        val cfg = config.config()
        val wabaId = cfg.wabaId?.trim()?.takeIf { it.isNotBlank() }
            ?: throw WhatsappSendException("WhatsApp Business Account ID (WABA) is missing — paste it in Settings")
        val subscribe = graphJson("POST", "${cfg.apiUrl}/$wabaId/subscribed_apps")
        val apps = graphJson("GET", "${cfg.apiUrl}/$wabaId/subscribed_apps")
        return mapOf(
            "wabaId" to wabaId,
            "subscribe" to subscribe.toString(),
            "apps" to apps.toString(),
        )
    }

    private fun postMessage(body: com.fasterxml.jackson.databind.node.ObjectNode): com.fasterxml.jackson.databind.JsonNode {
        val cfg = config.config()
        val phoneId = cfg.phoneNumberId
            ?: throw WhatsappSendException("whatsapp phone-number-id not configured")
        return graphJson("POST", "${cfg.apiUrl}/$phoneId/messages", body.toString())
    }

    /** Meta media id → bytes + mime (photo slips inbound). */
    fun downloadMedia(mediaId: String): Pair<ByteArray, String> {
        val cfg = config.config()
        val token = cfg.token ?: throw WhatsappSendException("whatsapp token not configured")
        val meta = graphJson("GET", "${cfg.apiUrl}/$mediaId")
        val url = meta.path("url").asText(null)?.takeIf { it.isNotBlank() }
            ?: throw WhatsappSendException("media $mediaId has no download url")
        val mime = meta.path("mime_type").asText("image/jpeg")
        val headers = org.springframework.http.HttpHeaders().apply { setBearerAuth(token) }
        try {
            val bytes = rest.exchange(
                url,
                org.springframework.http.HttpMethod.GET,
                org.springframework.http.HttpEntity<Void>(headers),
                ByteArray::class.java,
            ).body ?: throw WhatsappSendException("empty media download")
            return bytes to mime
        } catch (e: WhatsappSendException) {
            throw e
        } catch (e: org.springframework.web.client.RestClientException) {
            throw WhatsappSendException("media download failed: ${e.message?.take(200)}")
        }
    }

    private fun graphJson(method: String, url: String, body: String? = "{}"): com.fasterxml.jackson.databind.JsonNode {
        val cfg = config.config()
        val token = cfg.token ?: throw WhatsappSendException("whatsapp token not configured")
        val headers = org.springframework.http.HttpHeaders().apply {
            setBearerAuth(token)
            set("Content-Type", "application/json")
        }
        val entity = if (method == "GET") {
            org.springframework.http.HttpEntity<Void>(headers)
        } else {
            org.springframework.http.HttpEntity(body ?: "{}", headers)
        }
        try {
            val raw = rest.exchange(
                url,
                org.springframework.http.HttpMethod.valueOf(method),
                entity,
                String::class.java,
            ).body ?: throw WhatsappSendException("empty Graph response")
            val node = mapper.readTree(raw)
            val err = node.path("error")
            if (!err.isMissingNode && !err.isNull) {
                throw WhatsappSendException("Meta ${err.path("code").asText()}/${err.path("error_subcode").asText()}: ${err.path("error_user_msg").asText(err.path("message").asText("Request rejected")).take(300)}", retryable = err.path("is_transient").asBoolean(false))
            }
            return node
        } catch (e: WhatsappSendException) {
            throw e
        } catch (e: org.springframework.web.client.RestClientException) {
            val http = e as? org.springframework.web.client.HttpStatusCodeException
            val err = runCatching { mapper.readTree(http?.responseBodyAsString ?: "{}").path("error") }.getOrNull()
            throw WhatsappSendException("Graph $method failed: ${http?.statusCode?.value() ?: "connection"} ${err?.path("error_user_msg")?.asText(err.path("message").asText("request failed"))?.take(250) ?: "request outcome unknown"}",
                retryable = http?.statusCode?.value() == 429 || err?.path("is_transient")?.asBoolean(false) == true,
                ambiguous = http == null)

        }
    }

    companion object {
        fun waId(number: String): String = number.filter { it.isDigit() }
        private val factory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
        private val mapper = com.fasterxml.jackson.databind.ObjectMapper()
    }
}
