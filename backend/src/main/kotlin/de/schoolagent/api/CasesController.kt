package de.schoolagent.api

import de.schoolagent.actions.ActionService
import de.schoolagent.domain.enums.ActionStatus
import de.schoolagent.domain.enums.Actor
import de.schoolagent.domain.enums.CaseStatus
import de.schoolagent.domain.repo.CaseChangeRepository
import de.schoolagent.domain.repo.CaseStatusEntryRepository
import de.schoolagent.domain.repo.ChildRequirementRepository
import de.schoolagent.domain.repo.EvidenceRepository
import de.schoolagent.domain.repo.ExternalFormRepository
import de.schoolagent.domain.repo.InvestigationRepository
import de.schoolagent.domain.repo.InvestigationStepRepository
import de.schoolagent.domain.repo.ParentActionRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.repo.SchoolEventRepository
import de.schoolagent.domain.repo.ChildRepository
import de.schoolagent.domain.repo.AppUserRepository
import de.schoolagent.domain.repo.ActionAcknowledgmentRepository
import de.schoolagent.domain.repo.SourceRepository
import de.schoolagent.domain.model.ChildRequirement
import de.schoolagent.domain.model.ParentAction
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.security.KidAccessService
import de.schoolagent.i18n.UserLanguage
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import java.time.Instant

/** Read API for the case inbox (spec §28): list, filter, drill into a case. */
@RestController
@RequestMapping("/api/cases")
class CasesController(
    private val caseRepo: SchoolCaseRepository,
    private val cardCopy: de.schoolagent.inbox.InboxCopyService,
    private val sourceRepo: SourceRepository,
    private val evidenceRepo: EvidenceRepository,
    private val actionRepo: ParentActionRepository,
    private val requirementRepo: ChildRequirementRepository,
    private val eventRepo: SchoolEventRepository,
    private val formRepo: ExternalFormRepository,
    private val changeRepo: CaseChangeRepository,
    private val statusRepo: CaseStatusEntryRepository,
    private val investigationRepo: InvestigationRepository,
    private val stepRepo: InvestigationStepRepository,
    private val kids: ChildRepository,
    private val users: AppUserRepository,
    private val ackRepo: ActionAcknowledgmentRepository,
    private val access: KidAccessService,
    private val readings: SourceReadingService,
    private val titles: de.schoolagent.i18n.TitleEnglishService,
    private val caseService: de.schoolagent.domain.service.CaseService,
    private val actionService: ActionService,
    private val inbox: de.schoolagent.domain.service.FamilyInboxService,
    private val relevance: de.schoolagent.inbox.CaseRelevanceService,
    private val actionLinks: de.schoolagent.actions.ActionLinkService,
) {

    @GetMapping
    fun list(
        @RequestParam(required = false) status: List<String>?,
        @RequestParam(required = false) kid: UUID?,
        @AuthenticationPrincipal user: org.springframework.security.core.userdetails.UserDetails,
    ): List<CaseSummaryDto> {
        access.requireAccess(user, kid)
        val allowed = access.accessibleKidIds(user)
        val kidNameById = kids.findAll().associate { it.id to (it.name to it.color) }
        val statuses = status?.map { raw ->
            runCatching { CaseStatus.valueOf(raw.uppercase()) }.getOrElse {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown case status: $raw")
            }
        }
        val cases = (statuses?.let { caseRepo.findByStatusIn(it) } ?: caseRepo.findAll())
            .filter { c -> c.childId == null || allowed.contains(c.childId) }
            .filter { c -> kid == null || c.childId == kid }
        val openCount = actionRepo.findByStatusIn(listOf(ActionStatus.OPEN, ActionStatus.ASSIGNED, ActionStatus.IN_PROGRESS, ActionStatus.OVERDUE))
            .groupingBy { it.caseId }
            .eachCount()
        val displayCopy = cardCopy.current(cases)
        val relevanceById = relevance.current(cases)
        return cases
            .sortedWith(compareByDescending<SchoolCase> { it.importanceScore }.thenByDescending { it.openedAt })
            .map { c ->
                val caseSources = sourceRepo.findByCaseId(c.id)
                val sent = caseSources.filter { it.sourceType in listOf(de.schoolagent.domain.enums.SourceType.EMAIL, de.schoolagent.domain.enums.SourceType.PORTAL_LETTER, de.schoolagent.domain.enums.SourceType.SCHULMANAGER_LETTER) }
                    .maxByOrNull { it.originalSentAt ?: it.receivedAt ?: Instant.EPOCH }
                c.toSummary((openCount[c.id] ?: 0).toLong(), c.childId, kidNameById[c.childId]?.first, kidNameById[c.childId]?.second).copy(
                    inboxCopy = displayCopy[c.id],
                    relevance = relevanceById[c.id],
                    revision = inbox.revision(c.id),
                    read = access.account(user)?.id?.let { inbox.readRevision(c.id, it) == inbox.revision(c.id) } ?: false,
                    sourceTypes = caseSources.map { it.sourceType.name }.distinct(),
                    sourceDate = sent?.originalSentAt ?: sent?.receivedAt ?: c.openedAt,
                    sourceDateLabel = when { sent?.originalSentAt != null -> "Sent"; sent?.receivedAt != null -> "Received"; else -> "Added" },
                    latestChange = changeRepo.findByCaseIdOrderByCreatedAtAsc(c.id).lastOrNull { it.changeType == de.schoolagent.domain.enums.ChangeType.MOVE }?.let {
                        "${changeDate(it.beforeJson)} → ${changeDate(it.afterJson)}"
                    },
                )
            }
    }

    private fun changeDate(raw: String?): String = runCatching {
        Instant.parse(raw?.trim('"')).atZone(java.time.ZoneId.of("Europe/Berlin"))
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm", java.util.Locale.ENGLISH))
    }.getOrElse { "date updated" }

    @GetMapping("/{id}")
    fun detail(@PathVariable id: UUID, @AuthenticationPrincipal user: org.springframework.security.core.userdetails.UserDetails): CaseDetailDto {
        val found = inbox.accessible(id, user)
        val investigations = investigationRepo.findByCaseIdOrderByStartedAtDesc(found.id)
        val userById = users.findAll().associateBy { it.id }
        val detailActions = actionRepo.findByCaseId(found.id)
        val links = actionLinks.resolveAll(detailActions, mapOf(found.id to found))
        return CaseDetailDto(
            id = found.id,
            childId = found.childId,
            childName = found.childId?.let { kids.findById(it).orElse(null)?.name },
            childColor = found.childId?.let { kids.findById(it).orElse(null)?.color },
            title = found.title,
            summary = found.summary,
            category = found.category.name,
            status = found.status.name,
            importance = found.importance.name,
            score = found.importanceScore,
            confidence = found.confidence?.toDouble(),
            investigationComplete = found.investigationComplete,
            investigationNotes = found.investigationNotes,
            openedAt = found.openedAt.takeIf { it != java.time.Instant.EPOCH },
            closedAt = found.closedAt,
            muted = found.muted,
            informationOnly = found.isInfo(),
            classificationOverridden = found.infoOverride != null,
            classificationReview = found.classificationReview,
            revision = inbox.revision(id),
            read = access.account(user)?.id?.let { inbox.readRevision(id, it) == inbox.revision(id) } ?: false,
            canUndo = found.status == CaseStatus.COMPLETED && found.completionSnapshot != null,
            relevance = relevance.current(listOf(found))[found.id],
            sources = sourceRepo.findByCaseId(found.id).map { it.toDto() },
            evidence = evidenceRepo.findByCaseId(found.id).map { it.toDto() },
            actions = detailActions
                .sortedWith(compareByDescending<ParentAction> { it.required }.thenBy(nullsLast()) { it.dueAt })
                .map { action ->
                    val saved = action
                    val name = saved.assignedTo?.let { users.findById(it).orElse(null)?.displayName }
                    saved.toDto(name, acknowledgmentsOf(saved.id, userById), links[saved.id])
                },
            requirements = requirementRepo.findByCaseId(found.id)
                .sortedWith(compareByDescending<ChildRequirement> { it.required }.thenBy(nullsLast()) { it.relevantOn })
                .map { it.toDto() },
            events = eventRepo.findByCaseId(found.id).map { it.toDto() },
            externalForms = formRepo.findByCaseId(found.id).map { it.toDto() },
            changes = changeRepo.findByCaseIdOrderByCreatedAtAsc(found.id).map { it.toDto() },
            statusHistory = statusRepo.findByCaseIdOrderByCreatedAtAsc(found.id).map { it.toDto() },
            investigations = investigations.map { inv ->
                inv.toDto(stepRepo.findByInvestigationIdOrderByCreatedAtAsc(inv.id))
            },
        )
    }

    @GetMapping("/{id}/sources/{sourceId}")
    fun sourceReading(
        @PathVariable id: UUID,
        @PathVariable sourceId: UUID,
        @RequestParam(required = false) lang: String?,
        @AuthenticationPrincipal user: org.springframework.security.core.userdetails.UserDetails,
    ): SourceReadingDto {
        val found = caseRepo.findById(id).orElseThrow { CaseNotFoundException(id) }
        val childId = found.childId
        if (childId != null && !access.canAccess(user, childId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "no access to this case")
        }
        val language = UserLanguage.of(lang ?: access.account(user)?.language)
        return readings.read(found.id, sourceId, language)
    }

    /** Mark a case done for every parent — used for coming/noted items with no action. */
    @PutMapping("/{id}/complete")
    fun complete(
        @PathVariable id: UUID,
        @AuthenticationPrincipal user: org.springframework.security.core.userdetails.UserDetails,
    ): Map<String, String> {
        inbox.complete(id, user)
        return mapOf("status" to "COMPLETED")
    }

    data class ReadRequest(val read: Boolean, val revision: String? = null)
    data class InfoRequest(val informationOnly: Boolean)

    @PutMapping("/{id}/read")
    fun markRead(@PathVariable id: UUID, @org.springframework.web.bind.annotation.RequestBody body: ReadRequest,
        @AuthenticationPrincipal user: org.springframework.security.core.userdetails.UserDetails) {
        inbox.markRead(id, user, body.read, body.revision)
    }

    @PutMapping("/{id}/classification")
    fun classify(@PathVariable id: UUID, @org.springframework.web.bind.annotation.RequestBody body: InfoRequest,
        @AuthenticationPrincipal user: org.springframework.security.core.userdetails.UserDetails) {
        inbox.classify(id, user, body.informationOnly)
    }

    @PutMapping("/{id}/undo-complete")
    fun undoComplete(@PathVariable id: UUID, @AuthenticationPrincipal user: org.springframework.security.core.userdetails.UserDetails) {
        inbox.undo(id, user)
    }

    data class MuteRequest(val muted: Boolean)

    @PutMapping("/{id}/mute")
    fun mute(
        @PathVariable id: UUID,
        @org.springframework.web.bind.annotation.RequestBody body: MuteRequest,
        @AuthenticationPrincipal user: org.springframework.security.core.userdetails.UserDetails,
    ): Map<String, Any> {
        val found = caseRepo.findById(id).orElseThrow { CaseNotFoundException(id) }
        val childId = found.childId
        if (childId != null && !access.canAccess(user, childId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "no access to this case")
        }
        val actor = Actor.MANUAL
        val saved = caseService.setMuted(found, body.muted, actor)
        return mapOf("status" to saved.status.name, "muted" to saved.muted)
    }

    private fun acknowledgmentsOf(
        actionId: UUID,
        userById: Map<UUID, de.schoolagent.domain.model.AppUser>,
    ): List<AcknowledgmentDto> =
        ackRepo.findByActionId(actionId).mapNotNull { ack ->
            userById[ack.userId]?.let { u -> AcknowledgmentDto(u.name, u.displayName, ack.acknowledgedAt) }
        }
}

@ResponseStatus(HttpStatus.NOT_FOUND)
class CaseNotFoundException(id: UUID) : RuntimeException("case $id not found")
