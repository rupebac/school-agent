package de.schoolagent.connectors.schulmanager

import com.fasterxml.jackson.databind.JsonNode
import de.schoolagent.connectors.school.*
import org.jsoup.Jsoup
import java.time.Instant

/** Fetching messages and setting a subscription read are separate provider operations.
 * This adapter exclusively uses the two read endpoints.
 */
internal class SchulmanagerMessages(private val call: (String, String, Any) -> JsonNode?) {
    private val messages = mutableMapOf<String, JsonNode>()

    fun read(identity: SchoolIdentity, studentId: String, enabled: Boolean, selfId: String?): SchoolNoticeFeed {
        if (!enabled) return unavailable("Enable sharing connected-account messages with the family to import this inbox")
        if (identity.students.singleOrNull()?.id != studentId)
            return unavailable("This account has multiple children; messages need explicit child attribution before import")
        if (selfId?.toLongOrNull() == null) return unavailable("The account sender identity could not be verified")
        return try {
            val subscriptions = call("messenger", "get-subscriptions",
                mapOf("all" to true, "includeArchived" to true, "reason" to "initial"))
                ?: return unavailable("Messages are not available to this account")
            require(subscriptions.isArray && subscriptions.size() <= 2000)
            val notices = mutableListOf<SchoolNotice>()
            for (subscription in subscriptions.sortedByDescending { it.path("thread").path("lastMessageTimestamp").asText() }.take(50)) {
                val subscriptionId = subscription.path("id").asText()
                val thread = subscription.path("thread")
                val threadId = thread.path("id").asText()
                require(subscriptionId.toLongOrNull() != null && threadId.toLongOrNull() != null)
                var before: Long? = null
                for (pageIndex in 0 until 5) {
                    val page = call("messenger", "get-messages-by-subscription",
                        mapOf("subscriptionId" to subscriptionId.toLong(), "before" to before, "loadAll" to false))
                        ?: throw SchoolConnectionException("Message page is unavailable")
                    val rows = page.path("messages")
                    require(rows.isArray && rows.size() <= 200 && page.path("hasMoreMessages").isBoolean)
                    for (row in rows) {
                        val id = row.path("id").asText()
                        require(id.toLongOrNull() != null && row.path("threadId").asText() == threadId && row.path("text").isTextual)
                        require(row.path("sender").path("id").asText().toLongOrNull() != null)
                        if (row.path("sender").path("id").asText() == selfId) continue
                        require(row.path("attachments").isArray)
                        val nativeId = "message:$threadId:$id"
                        messages[nativeId] = row
                        notices += SchoolNotice(nativeId, thread.path("subject").asText().ifBlank { "School message" },
                            Instant.parse(row.path("createdAt").asText()),
                            "${SchulmanagerSession.BASE_URL}/#/modules/messenger",
                            kind = SchoolNoticeKind.MESSAGE)
                        require(notices.size <= 2000) { "Message window exceeds import limit" }
                    }
                    if (!page.path("hasMoreMessages").asBoolean()) break
                    val next = rows.minOfOrNull { it.path("id").asLong() }
                        ?: throw SchoolConnectionException("Message pagination did not advance")
                    require(before == null || next < before) { "Message pagination did not advance" }
                    before = next
                }
            }
            require(notices.map { it.id }.distinct().size == notices.size)
            SchoolNoticeFeed(SchoolNoticeKind.MESSAGE, SchoolFeedStatus.AVAILABLE, notices,
                "Checks the latest 50 conversations, up to five pages each; older imported messages are retained")
        } catch (_: Exception) {
            SchoolNoticeFeed(SchoolNoticeKind.MESSAGE, SchoolFeedStatus.FAILED,
                message = "Could not verify the message inbox; existing messages are retained")
        }
    }

    fun details(notice: SchoolNotice, download: (JsonNode) -> SchoolDocument): SchoolNoticeDetails {
        val row = messages[notice.id] ?: throw SchoolConnectionException("Message was not part of this verified inbox")
        val text = "${notice.title}\n${de.schoolagent.connectors.school.SchoolText.fromHtml(row.path("text").asText())}"
        return SchoolNoticeDetails(listOf(SchoolDocument("letter", notice.title, "text/plain",
            text.toByteArray(), notice.receivedAt)) + row.path("attachments").map(download))
    }

    private fun unavailable(reason: String) = SchoolNoticeFeed(SchoolNoticeKind.MESSAGE, SchoolFeedStatus.UNAVAILABLE, message = reason)
}
