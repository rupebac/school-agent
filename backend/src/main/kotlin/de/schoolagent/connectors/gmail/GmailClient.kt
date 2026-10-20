package de.schoolagent.connectors.gmail

import com.fasterxml.jackson.databind.JsonNode
import de.schoolagent.connectors.google.GoogleAuthException
import de.schoolagent.connectors.google.GoogleOAuthService
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.util.Base64

/** What ingestion needs from a mailbox — one implementation today, trivially fakeable. */
interface GmailClient {
    fun listMessageIds(maxResults: Int): List<String>
    fun listMessagePage(maxResults: Int, pageToken: String? = null): MessageIdPage = MessageIdPage(listMessageIds(maxResults).take(maxResults), null)
    fun fetchMetadata(id: String): MessageMetadata
    fun fetchRaw(id: String): ByteArray
    fun fetchAttachments(id: String): List<AttachmentData>
}

data class MessageIdPage(val ids: List<String>, val nextPageToken: String?)

data class MessageMetadata(
    val from: String?,
    val subject: String?,
    val internalDateMs: Long,
    val threadId: String? = null,
)

data class AttachmentData(
    val filename: String,
    val mimeType: String,
    val bytes: ByteArray,
)

class GmailApiException(message: String, val authRelated: Boolean, val statusCode: Int? = null) : RuntimeException(message)

/**
 * Thin REST client for the Gmail API (no heavyweight SDK; Bearer from the
 * OAuth service). Base64url decoding per Gmail's JSON encoding.
 */
@Service
class GmailRestClient(
    private val oauth: GoogleOAuthService,
    rest: RestTemplate,
    private val props: de.schoolagent.connectors.google.GoogleProperties = de.schoolagent.connectors.google.GoogleProperties(),
) : GmailClient {

    private val tokenChildId = ThreadLocal<java.util.UUID?>()

    private val rest: RestTemplate = rest
    private val base = "https://gmail.googleapis.com/gmail/v1/users/me"

    fun withChild(childId: java.util.UUID?) { tokenChildId.set(childId) }

    override fun listMessageIds(maxResults: Int): List<String> = listMessagePage(maxResults).ids

    override fun listMessagePage(maxResults: Int, pageToken: String?): MessageIdPage {
        val url = org.springframework.web.util.UriComponentsBuilder.fromUriString("$base/messages")
            .queryParam("maxResults", maxResults.coerceIn(1, 100)).queryParam("q", props.gmailQuery)
        pageToken?.let { url.queryParam("pageToken", it) }
        val node = get(url.build().encode().toUriString())
        val next = node.path("nextPageToken").asText(null)?.takeIf { it.isNotBlank() }
        if (next != null && next == pageToken) throw GmailApiException("Repeated Gmail page token", false)
        return MessageIdPage(node.path("messages").take(maxResults).map { it.path("id").asText() }, next)
    }

    fun mailbox(childId: java.util.UUID?): String? {
        withChild(childId)
        return try { get("$base/profile").path("emailAddress").asText(null) } finally { withChild(null) }
    }

    override fun fetchMetadata(id: String): MessageMetadata {
        val node = get(
            "$base/messages/$id?format=metadata" +
                "&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=Date",
        )
        val headers = node.path("payload").path("headers").associate {
            it.path("name").asText().lowercase() to it.path("value").asText()
        }
        return MessageMetadata(
            from = headers["from"],
            subject = headers["subject"],
            internalDateMs = node.path("internalDate").asLong(0),
            threadId = node.path("threadId").asText(null),
        )
    }

    override fun fetchRaw(id: String): ByteArray =
        b64UrlDecode(get("$base/messages/$id?format=raw").path("raw").asText())

    override fun fetchAttachments(id: String): List<AttachmentData> {
        val full = get("$base/messages/$id?format=full")
        var total = 0L
        return full.path("payload").findParts().also {
            if (it.count { part -> part.path("filename").asText().isNotBlank() } > 30) throw GmailApiException("Too many email attachments", false)
        }
            .mapNotNull { part ->
                val filename = part.path("filename").asText("")
                if (filename.isBlank()) return@mapNotNull null
                val attachmentId = part.path("body").path("attachmentId").asText(null)
                val data = if (attachmentId != null) get("$base/messages/$id/attachments/$attachmentId").path("data").asText()
                    else part.path("body").path("data").asText("")
                if (data.isBlank()) return@mapNotNull null
                val bytes = b64UrlDecode(data)
                total += bytes.size
                if (total > 25 * 1024 * 1024) throw GmailApiException("Email attachments exceed size limit", false)
                AttachmentData(
                    filename = filename,
                    mimeType = part.path("mimeType").asText("application/octet-stream"),
                    bytes = bytes,
                )
            }
    }

    private fun JsonNode.findParts(): List<JsonNode> {
        val result = mutableListOf<JsonNode>()
        fun walk(node: JsonNode, depth: Int = 0) {
            if (depth > 32 || result.size >= 1000) throw GmailApiException("Email structure exceeds limit", false)
            result.add(node)
            node.path("parts").forEach { walk(it, depth + 1) }
        }
        walk(this)
        return result
    }

    private fun get(url: String): JsonNode {
        val token = try {
            oauth.accessToken(tokenChildId.get())
        } catch (e: GoogleAuthException) {
            throw GmailApiException(e.message ?: "auth failure", authRelated = true)
        }
        val headers = HttpHeaders().apply { setBearerAuth(token) }
        return try {
            com.fasterxml.jackson.databind.ObjectMapper().readTree(rest.exchange(java.net.URI.create(url), HttpMethod.GET, HttpEntity<Void>(headers), String::class.java).body!!)
        } catch (e: org.springframework.web.client.HttpClientErrorException.Unauthorized) {
            throw GmailApiException("Gmail API 401", authRelated = true)
        } catch (e: org.springframework.web.client.HttpStatusCodeException) {
            throw GmailApiException("Gmail API HTTP ${e.statusCode.value()}", e.statusCode.value() in setOf(401, 403), e.statusCode.value())
        } catch (e: org.springframework.web.client.RestClientException) {
            throw GmailApiException("Gmail API request failed (${e.javaClass.simpleName})", authRelated = false)
        }
    }

    private fun b64UrlDecode(value: String): ByteArray {
        if (value.length > 12 * 1024 * 1024) throw GmailApiException("Email part exceeds size limit", false)
        return Base64.getUrlDecoder().decode(value.padEnd(value.length + (4 - value.length % 4) % 4, '='))
    }
}
