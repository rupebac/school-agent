package de.schoolagent.whatsapp

import org.springframework.stereotype.Service

@Service
class WhatsappMessageProcessor(
    private val config: WhatsAppConfigService,
    private val qa: QuestionAnsweringService,
    private val graph: GraphWhatsappClient,
    private val photos: de.schoolagent.ingestion.PhotoIngestionService,
) {
    companion object {
        fun accessStamp(user: de.schoolagent.domain.model.AppUser): String = user.role + ":" + user.kids.map { it.id.toString() }.sorted().joinToString(",")
    }
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
    data class PreparedReply(val recipient: String, val text: String, val childId: java.util.UUID?, val userId: String, val accessStamp: String)
    fun prepare(value: com.fasterxml.jackson.databind.JsonNode, message: com.fasterxml.jackson.databind.JsonNode): PreparedReply? {
            var prepared: PreparedReply? = null
            val from = message.path("from").asText(null)
            val type = message.path("type").asText("")
            val text = message.path("text").path("body").asText(null)
            val phoneNumberId = value.path("metadata").path("phone_number_id").asText(null)
            val childId = config.childIdForPhoneNumberId(phoneNumberId)
            log.debug("WhatsApp inbound message received")
            if (from == null) return null

            config.withChild(childId) {
                val user = config.userForNumber(from)
                if (user == null) {
                    log.debug("WhatsApp sender is not a registered account")
                    return@withChild
                }
                val lang = de.schoolagent.i18n.UserLanguage.of(user.language)
                val reply = when {
                    type == "image" || type == "document" -> ingestMedia(message, type, user, lang)
                    !text.isNullOrBlank() ->
                        qa.answerAndRemember(text, user).forWhatsApp(config.config().frontendUrl)
                    else -> return@withChild
                }
                prepared = PreparedReply(from, reply, childId, user.id.toString(), accessStamp(user))
            }
            return prepared
    }

    private fun ingestMedia(
        message: com.fasterxml.jackson.databind.JsonNode,
        type: String,
        user: de.schoolagent.domain.model.AppUser,
        lang: de.schoolagent.i18n.UserLanguage,
    ): String {
        val node = message.path(type)
        val mediaId = node.path("id").asText(null)?.takeIf { it.isNotBlank() }
            ?: return de.schoolagent.i18n.WaCopy.photoFailed(lang)
        val caption = node.path("caption").asText(null)
        val filename = node.path("filename").asText(null)
        return try {
            val (bytes, mime) = graph.downloadMedia(mediaId)
            val ingested = photos.ingest(bytes, mime, filename, caption, user, childId = null)
            if (!ingested.duplicate) photos.processAsync(ingested.schoolCase.id)
            de.schoolagent.i18n.WaCopy.photoReceived(lang, ingested.schoolCase.title, ingested.duplicate)
        } catch (e: Exception) {
            log.warn("whatsapp photo ingest failed: {}", e.javaClass.simpleName)
            throw e
        }
    }

}
