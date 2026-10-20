package de.schoolagent.whatsapp

import de.schoolagent.domain.enums.ActionStatus
import de.schoolagent.domain.enums.EventStatus
import de.schoolagent.domain.enums.NotificationChannel
import de.schoolagent.domain.enums.NotificationLevel
import de.schoolagent.domain.enums.NotificationStatus
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.model.SchoolEvent
import de.schoolagent.domain.repo.NotificationRepository
import de.schoolagent.domain.repo.ParentActionRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.repo.SchoolEventRepository
import de.schoolagent.i18n.UserLanguage
import de.schoolagent.diagnostics.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Instant new-letter WhatsApp. Always a Meta utility template so it arrives
 * even outside the 24h window. Composition reads persisted inbox copy and queues
 * typed facts; sending, localization and delivery receipts run separately. Score ≥ 2.
 */
@Service
class NotificationService(
    private val config: WhatsAppConfigService,
    private val notificationRepo: NotificationRepository,
    private val actionRepo: ParentActionRepository,
    private val eventRepo: SchoolEventRepository,
    private val caseRepo: SchoolCaseRepository,
    private val users: de.schoolagent.domain.repo.AppUserRepository,
    private val kids: de.schoolagent.domain.repo.ChildRepository,
    private val clock: Clock,
    private val requirements: de.schoolagent.domain.repo.ChildRequirementRepository,
    private val inbox: de.schoolagent.domain.service.FamilyInboxService,
    private val copy: de.schoolagent.inbox.InboxCopyService,
    private val outbox: WhatsappOutbox,
    private val policy: InstantNoticePolicy,
    private val diagnostics: IntegrationDiagnostics? = null,
) {

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")

    /** Called after reasoning decided a case. Score below 2 is not pinged. */
    @Transactional
    fun notifyDecision(caseEntity: SchoolCase) {
        if (caseEntity.schoolUpdatePending) return
        if (caseEntity.suppressInitialNotification) {
            caseEntity.suppressInitialNotification = false
            caseRepo.save(caseEntity)
            trace(caseEntity, DiagnosticOutcome.QUIET_IMPORT)
            return
        }
        if (caseEntity.muted || caseEntity.isClosed()) {
            trace(caseEntity, if (caseEntity.muted) DiagnosticOutcome.MUTED else DiagnosticOutcome.CLOSED)
            return
        }
        if (!policy.eligible(caseEntity)) { trace(caseEntity, DiagnosticOutcome.EXPIRED); return }
        dispatch(caseEntity)
    }

    /** School timetable changes use the existing information template, including a closed cancelled case. */
    @Transactional
    fun notifySchoolChange(caseEntity: SchoolCase, change: String, details: String) {
        if (caseEntity.schoolUpdatePending) return
        if (caseEntity.suppressInitialNotification) {
            caseEntity.suppressInitialNotification = false
            caseRepo.save(caseEntity)
            trace(caseEntity, DiagnosticOutcome.QUIET_IMPORT)
            return
        }
        if (!policy.eligible(caseEntity, schoolChange = true)) { trace(caseEntity, DiagnosticOutcome.EXPIRED); return }
        if (caseEntity.muted || caseEntity.importanceScore < 2) {
            trace(caseEntity, if (caseEntity.muted) DiagnosticOutcome.MUTED else DiagnosticOutcome.LOW_PRIORITY)
            return
        }
        val child = caseEntity.childId?.let { kids.findById(it).orElse(null)?.name } ?: "Family"
        val baseKey = "school-change:${caseEntity.id}:${inbox.revision(caseEntity.id)}"
        val recipients = recipientsFor(caseEntity)
        if (recipients.isEmpty()) trace(caseEntity, DiagnosticOutcome.NO_RECIPIENTS)
        for ((user, _) in recipients) {
            val messages = WaTemplates.messages(WaTemplates.Kind.INFO, UserLanguage.of(user.language), child,
                "$change: ${caseEntity.title}", details, emptyList(), "cases/${caseEntity.id}")
            messages.forEachIndexed { index, message -> outbox.enqueue(user.id, "$baseKey:${user.id}:$index", message,
                caseEntity.id, NotificationLevel.IMMEDIATE, java.time.Duration.ofDays(3), clock.instant().plusSeconds(3 * 86400)) }
        }
    }

    private fun dispatch(caseEntity: SchoolCase) {
        val today = clock.instant().atZone(berlin).toLocalDate()
        val actions = actionRepo.findByCaseId(caseEntity.id)
            .filter { it.status in listOf(ActionStatus.OPEN, ActionStatus.ASSIGNED, ActionStatus.IN_PROGRESS, ActionStatus.OVERDUE) &&
                it.dueAt?.atZone(berlin)?.toLocalDate()?.isBefore(today) != true }
        val score = de.schoolagent.domain.enums.clampImportanceScore(caseEntity.importanceScore)
        if (score < 2) { trace(caseEntity, DiagnosticOutcome.LOW_PRIORITY); return }
        val baseKey = "decision:${caseEntity.id}:${inbox.revision(caseEntity.id)}:${caseEntity.isInfo()}"

        val recipients = recipientsFor(caseEntity)
        val targets = recipients.filter { (user, _) ->
            !notificationRepo.existsByDedupKey("$baseKey:${user.id}") &&
                !notificationRepo.existsByDedupKeyStartingWith("$baseKey:${user.id}:")
        }
        if (targets.isEmpty()) {
            trace(caseEntity, if (recipients.isEmpty()) DiagnosticOutcome.NO_RECIPIENTS else DiagnosticOutcome.DUPLICATE)
            return
        }
        val now = clock.instant()
        val realEvents = eventRepo.findByCaseId(caseEntity.id).filter {
            it.status in setOf(EventStatus.PLANNED, EventStatus.CONFIRMED) &&
                it.eventType !in setOf(de.schoolagent.domain.enums.EventType.DEADLINE,
                    de.schoolagent.domain.enums.EventType.PAYMENT, de.schoolagent.domain.enums.EventType.BRING_ITEM,
                    de.schoolagent.domain.enums.EventType.SCHOOL_REPORT)
        }
        fun cutoff(e: SchoolEvent): java.time.Instant {
            val local = e.startTime.atZone(berlin)
            // Date-only events stay useful throughout the day; timed notices stop at the start.
            return if (e.allDay || local.toLocalTime() == java.time.LocalTime.MIDNIGHT)
                local.toLocalDate().plusDays(1).atStartOfDay(berlin).toInstant() else e.startTime
        }
        val event = realEvents.filter { cutoff(it).isAfter(now) }.minByOrNull { it.startTime }
        if (caseEntity.isInfo() && realEvents.isNotEmpty() && event == null) {
            trace(caseEntity, DiagnosticOutcome.EXPIRED)
            return
        }
        val expiresAt = event?.let(::cutoff)?.coerceAtMost(now.plus(java.time.Duration.ofDays(3)))
            ?: now.plus(java.time.Duration.ofDays(3))
        var projection = copy.current(listOf(caseEntity))[caseEntity.id]
        // Called only from background reasoning, never an inbox read. Generate once before
        // queueing both parents, sharing the same persisted copy with the inbox and plans.
        if (projection == null || projection.reminders.isEmpty()) {
            runCatching { copy.refresh(caseEntity.id) }.onFailure {
                org.slf4j.LoggerFactory.getLogger(javaClass).warn("Notification copy unavailable for {}: {}", caseEntity.id, it.javaClass.simpleName)
            }
            projection = copy.current(listOf(caseEntity))[caseEntity.id]
        }
        val saved = projection
        val child = caseEntity.childId?.let { kids.findById(it).orElse(null)?.name } ?: "Family"
        val actionable = if (caseEntity.isInfo()) emptyList() else actions
        val details = mutableListOf<String>()
        event?.let { e -> details += "On ${eventWhen(e)}" }
        eventRepo.findByCaseId(caseEntity.id).firstOrNull { it.eventType == de.schoolagent.domain.enums.EventType.SCHOOL_REPORT &&
            it.status in setOf(EventStatus.PLANNED, EventStatus.CONFIRMED) }?.let { details += "Reported on ${eventWhen(it)}" }
        if (!caseEntity.isInfo()) {
            val childNeeds = requirements.findByCaseId(caseEntity.id).filter {
                it.required && it.status == de.schoolagent.domain.enums.RequirementStatus.OPEN &&
                    it.relevantOn?.isBefore(today) != true
            }
            val grouped = (actionable.map { it.dueAt?.atZone(berlin)?.toLocalDate()?.toString() ?: "undated" } +
                childNeeds.map { it.relevantOn?.toString() ?: "undated" }).distinct()
            grouped.forEach { date ->
                val aa = actionable.filter { (it.dueAt?.atZone(berlin)?.toLocalDate()?.toString() ?: "undated") == date }
                val rr = childNeeds.filter { (it.relevantOn?.toString() ?: "undated") == date }
                val reminder = saved?.reminder(date, aa, emptyList(), rr)
                if (reminder != null) {
                    val prefix = if (date == "undated") "" else
                        (if (aa.isNotEmpty()) "By " else "On ") + java.time.LocalDate.parse(date).format(dateFmt) + ": "
                    details += prefix + reminder
                } else {
                    details += aa.map { action ->
                        val label = (if (!action.required) "Optional · " else "") +
                            (saved?.actions?.get(action.id.toString()) ?: action.title)
                        action.dueAt?.let { "By ${it.atZone(berlin).toLocalDate().format(dateFmt)}: $label" } ?: label
                    }
                    details += rr.map { need ->
                        need.relevantOn?.let { "On ${it.format(dateFmt)}: ${need.description}" } ?: need.description
                    }
                }
            }
        }
        val context = if (saved != null) (if (event != null) saved.activity ?: saved.description else saved.description).orEmpty()
            else caseEntity.summary ?: "The school has sent a new letter."
        for ((user, _) in targets) {
            val kind = if (caseEntity.isInfo()) WaTemplates.Kind.INFO else WaTemplates.Kind.ACTION
            val lang = UserLanguage.of(user.language)
            val messages = WaTemplates.messages(kind, lang, child, saved?.title ?: caseEntity.title, context, details,
                "cases/${caseEntity.id}", abbreviations = saved?.abbreviations.orEmpty())
            messages.forEachIndexed { index, message ->
                outbox.enqueue(user.id, "$baseKey:${user.id}:$index", message, caseEntity.id,
                    if (score >= 4) NotificationLevel.IMMEDIATE else NotificationLevel.DIGEST,
                    java.time.Duration.ofDays(3), expiresAt)
            }
        }
    }

    private fun trace(c: SchoolCase, outcome: DiagnosticOutcome) = diagnostics?.record(
        DiagnosticTrace(DiagnosticStage.NOTICE, outcome, childId = c.childId, caseId = c.id))

    /** Parents assigned to this kid (admin sees all kids → all accounts), if they want instant pings. */
    private fun recipientsFor(caseEntity: SchoolCase): List<Pair<de.schoolagent.domain.model.AppUser, String>> {
        val childId = caseEntity.childId
        val raw = if (childId == null) {
            config.recipients()
        } else {
            val all = users.findAll()
            val admins = all.filter { it.role == "ADMIN" }
            val assigned = all.filter { u -> u.kids.any { it.id == childId } }
            (admins + assigned).distinctBy { it.id }
                .mapNotNull { u -> u.whatsappNumber?.let { u to it } }
        }
        return raw.filter { it.first.instantEnabled }
    }

    /** Immediate WhatsApp replaced the daily digest. Leftover queued rows are skipped, not sent. */
    @Transactional
    fun skipLeftoverDigestQueue(): Int {
        val queued = notificationRepo.findAll()
            .filter { it.channel == NotificationChannel.DIGEST && it.status == NotificationStatus.QUEUED }
        queued.forEach {
            it.status = NotificationStatus.SKIPPED
            notificationRepo.save(it)
        }
        return queued.size
    }

    @Deprecated("Use skipLeftoverDigestQueue; no WhatsApp digest is sent")
    fun sendDigest(): Int = skipLeftoverDigestQueue()

    private fun eventWhen(event: SchoolEvent): String {
        val local = event.startTime.atZone(berlin)
        val day = local.toLocalDate().format(dateFmt)
        if (event.allDay || (local.hour == 0 && local.minute == 0)) return day
        return "$day, ${local.format(clockFmt)}"
    }
}
