package de.schoolagent.email

import de.schoolagent.documents.TextExtractor
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.InternetAddress
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties

/** Original-message identity, independent of the parent's forwarding envelope. */
data class EmailContent(
    val sender: String?, val recipients: String?, val subject: String?, val date: String?,
    val sentAt: Instant?, val messageId: String?, val references: List<String>,
    val body: String, val forwarded: Boolean,
) {
    fun fingerprint(attachmentHashes: List<String>): String? {
        if (address(sender) == null || date.isNullOrBlank() || body.isBlank()) return null
        return hash(listOf(address(sender), subject.orEmpty().trim(), sentAt?.toString() ?: date.trim(),
            body.replace(Regex("\\s+"), " ").trim(), attachmentHashes.sorted().joinToString(",")).joinToString("\n"))
    }
    companion object {
        fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        fun address(value: String?): String? = value?.let { Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").find(it)?.value?.lowercase() }
        fun ids(value: String?): List<String> = value?.let { Regex("<[^<>\\s]+>").findAll(it).map { m -> m.value }.toList() }.orEmpty()
        private val marker = Regex("(?im)^\\s*[-_ ]*(?:begin forwarded message|forwarded message|original message|weitergeleitete nachricht|ursprüngliche nachricht|mensaje reenviado|inicio del mensaje reenviado)[-_ :]*$")
        private val header = Regex("(?i)^(From|Von|De|To|An|Para|Cc|Date|Sent|Datum|Gesendet|Fecha|Enviado|Subject|Betreff|Asunto|Message-ID|In-Reply-To|References):\\s*(.*)$")
        private fun parseDate(value: String?): Instant? = runCatching {
            jakarta.mail.internet.MailDateFormat().parse(value ?: "").toInstant()
        }.getOrNull()

        fun read(raw: ByteArray): EmailContent {
            val outer = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(raw))
            var current = outer
            var forwarded = false
            fun nested(part: Part, depth: Int = 0): MimeMessage? {
                if (depth > 12) return null
                if (part.isMimeType("message/rfc822")) return part.content as? MimeMessage
                if (part.isMimeType("multipart/*")) {
                    val multi = part.content as jakarta.mail.Multipart
                    for (i in 0 until multi.count) nested(multi.getBodyPart(i), depth + 1)?.let { return it }
                }
                return null
            }
            repeat(8) { nested(current)?.let { current = it; forwarded = true } }
            val bytes = java.io.ByteArrayOutputStream().also { current.writeTo(it) }.toByteArray()
            var result = EmailContent(current.from?.joinToString { (it as? InternetAddress)?.toUnicodeString() ?: it.toString() },
                current.allRecipients?.joinToString(), current.subject, current.getHeader("Date", null),
                current.sentDate?.toInstant(), ids(current.messageID).firstOrNull(),
                (ids(current.getHeader("References", null)) + ids(current.getHeader("In-Reply-To", null))).distinct(),
                TextExtractor.fromMime(bytes).text, forwarded)
            repeat(8) {
                val m = marker.find(result.body)
                // Outlook inline forwards may omit a separator, but must have a forwarding subject.
                val start = m?.range?.last?.plus(1) ?: if (Regex("(?i)^(fwd?|wg|rv):").containsMatchIn(result.subject.orEmpty()))
                    Regex("(?im)^(From|Von|De):").find(result.body)?.range?.first else null
                if (start != null) {
                    val lines = result.body.substring(start).trimStart().lines()
                    val fields = mutableMapOf<String, String>()
                    var consumed = 0
                    for (line in lines) {
                        val h = header.matchEntire(line.trim()) ?: break
                        fields[h.groupValues[1].lowercase()] = h.groupValues[2].trim()
                        consumed++
                    }
                    fun field(vararg names: String) = names.firstNotNullOfOrNull { fields[it] }
                    val sender = field("from", "von", "de")
                    if (address(sender) != null && consumed >= 2) {
                        val date = field("date", "sent", "datum", "gesendet", "fecha", "enviado")
                        result = EmailContent(sender, field("to", "an", "para"), field("subject", "betreff", "asunto") ?: result.subject,
                            date, parseDate(date), ids(field("message-id")).firstOrNull(),
                            (ids(field("references")) + ids(field("in-reply-to"))).distinct(),
                            lines.drop(consumed).joinToString("\n").trim(), true)
                    }
                }
            }
            return result
        }
    }
}
