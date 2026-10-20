package de.schoolagent.whatsapp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.schoolagent.domain.enums.CaseStatus
import de.schoolagent.domain.enums.NotificationChannel
import de.schoolagent.domain.model.AppUser
import de.schoolagent.domain.repo.AppUserRepository
import de.schoolagent.domain.repo.NotificationRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.service.FamilyInboxService
import de.schoolagent.i18n.UserLanguage
import de.schoolagent.i18n.WaCopy
import org.springframework.security.core.userdetails.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** Explicit template actions never pass through the conversational AI. */
@Service
class WhatsappDoneService(
    private val notifications: NotificationRepository,
    private val cases: SchoolCaseRepository,
    private val users: AppUserRepository,
    private val inbox: FamilyInboxService,
) {
    private val json = jacksonObjectMapper()

    @Transactional
    fun handle(message: com.fasterxml.jackson.databind.JsonNode, sender: AppUser): String {
        val lang = UserLanguage.of(sender.language)
        val raw = when (message.path("type").asText()) {
            "button" -> message.path("button").path("payload").asText()
            "interactive" -> message.path("interactive").path("button_reply").path("id").asText()
            else -> ""
        }
        val notificationId = raw.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) }
            ?.let(UUID::fromString) ?: return WaCopy.doneUnavailable(lang)
        val notification = notifications.findById(notificationId).orElse(null)
            ?: return WaCopy.doneUnavailable(lang)
        val context = message.path("context").path("id").asText()
        if (notification.recipientId != sender.id || notification.channel != NotificationChannel.WHATSAPP ||
            context.isBlank() || context != notification.messageId) return WaCopy.doneUnavailable(lang)
        val template = runCatching { json.readTree(notification.templatePayload).path("name").asText() }.getOrNull()
        if (WaTemplates.catalog("").none { it.name == template && it.doneButton != null }) return WaCopy.doneUnavailable(lang)
        val account = users.findById(sender.id).orElse(null) ?: return WaCopy.doneUnavailable(lang)
        if (account.whatsappNumber?.filter(Char::isDigit).isNullOrBlank() ||
            account.whatsappNumber?.filter(Char::isDigit) != message.path("from").asText()) return WaCopy.doneUnavailable(lang)
        val id = notification.caseId ?: return WaCopy.doneUnavailable(lang)
        val c = cases.lockById(id) ?: return WaCopy.doneUnavailable(lang)
        val principal = User.withUsername(account.name).password("").roles(account.role).build()
        try {
            inbox.accessible(id, principal)
        } catch (_: ResponseStatusException) { return WaCopy.doneUnavailable(lang) }
        if (c.status == CaseStatus.COMPLETED) return WaCopy.alreadyDone(lang, c.title)
        if (c.isInfo() || c.isClosed() ||
            c.status !in setOf(CaseStatus.ACTION_REQUIRED, CaseStatus.WAITING_FOR_PARENT, CaseStatus.NEEDS_REVIEW))
            return WaCopy.doneChanged(lang)
        // A button on an older school revision must never complete newly added duties.
        if (!notification.dedupKey.startsWith("decision:$id:${inbox.revision(id)}:false:"))
            return WaCopy.doneChanged(lang)
        inbox.complete(id, principal)
        return WaCopy.markedDone(lang, c.title)
    }

    companion object {
        private const val PREFIX = "school-agent:done:"
        fun payload(notificationId: UUID): String = "$PREFIX$notificationId"
    }
}
