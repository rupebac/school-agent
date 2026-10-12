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
    fun fetchMetadata(id: String): MessageMetadata
    fun fetchRaw(id: String): ByteArray
    fun fetchAttachments(id: String): List<AttachmentData>
}

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

class GmailApiException(message: String, val authRelated: Boolean) : RuntimeException(message)

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

    override fun listMessageIds(maxResults: Int): List<String> {
        val ids = linkedSetOf<String>()
        var page: String? = null
        val visited = mutableSetOf<String>()
        do {
            val url = org.springframework.web.util.UriComponentsBuilder.fromUriString("$base/messages")
                .queryParam("maxResults", maxResults.coerceIn(1, 500))
                .queryParam("q", props.gmailQuery)
            page?.let { url.queryParam("pageToken", it) }
            val node = get(url.build().encode().toUriString())
            ids += node.path("messages").map { it.path("id").asText() }
            page = node.path("nextPageToken").asText(null)?.takeIf { it.isNotBlank() }
            if (page != null && !visited.add(page)) throw GmailApiException("Repeated Gmail page token", false)
        } while (page != null)
        return ids.toList()
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
        return full.path("payload").findParts()
            .mapNotNull { part ->
                val filename = part.path("filename").asText("")
                if (filename.isBlank()) return@mapNotNull null
                val attachmentId = part.path("body").path("attachmentId").asText(null)
                val data = if (attachmentId != null) get("$base/messages/$id/attachments/$attachmentId").path("data").asText()
                    else part.path("body").path("data").asText("")
                if (data.isBlank()) return@mapNotNull null
                AttachmentData(
                    filename = filename,
                    mimeType = part.path("mimeType").asText("application/octet-stream"),
                    bytes = b64UrlDecode(data),
                )
            }
    }

    private fun JsonNode.findParts(): List<JsonNode> {
        val result = mutableListOf<JsonNode>()
        fun walk(node: JsonNode) {
            result.add(node)
            node.path("parts").forEach { walk(it) }
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
        } catch (e: org.springframework.web.client.RestClientException) {
            throw GmailApiException("Gmail API unreachable: ${e.message}", authRelated = false)
        }
    }

    private fun b64UrlDecode(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value.padEnd(value.length + (4 - value.length % 4) % 4, '='))
}
