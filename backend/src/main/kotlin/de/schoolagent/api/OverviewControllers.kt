package de.schoolagent.api

import de.schoolagent.actions.ActionService
import de.schoolagent.actions.DuplicateActionException
import de.schoolagent.domain.enums.ActionStatus
import de.schoolagent.domain.enums.Actor
import de.schoolagent.domain.enums.HealthStatus
import de.schoolagent.domain.model.ParentAction
import de.schoolagent.domain.model.SchoolEvent
import de.schoolagent.domain.repo.AppUserRepository
import de.schoolagent.domain.repo.AuditEntryRepository
import de.schoolagent.domain.repo.ConnectorHealthRepository
import de.schoolagent.domain.repo.ParentActionRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.repo.SchoolEventRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Read + ownership endpoints behind the remaining Settings/Actions/Calendar/Audit screens (spec §28). */

data class ActionDto(
    val id: UUID,
    val caseId: UUID,
    val caseTitle: String?,
    val title: String,
    val description: String?,
    val dueAt: Instant?,
    val assignedTo: UUID?,
    val assignedToName: String?,
    val status: String,
    val externalUrl: String?,
    val required: Boolean,
    val score: Int?,
    val createdAt: Instant? = null,
    val acknowledgedBy: List<AcknowledgmentDto> = emptyList(),
    val externalLink: de.schoolagent.actions.ActionLink? = null,
)

data class EventDto(
    val id: UUID,
    val caseId: UUID,
    val caseTitle: String?,
    val title: String,
    val eventType: String,
    val startTime: Instant,
    val endTime: Instant?,
    val allDay: Boolean,
    val location: String?,
    val status: String,
)

data class AuditDto(
    val id: UUID,
    val caseId: UUID?,
    val actor: String,
    val action: String,
    val detail: String?,
    val createdAt: Instant,
)

data class ConnectorHealthDto(
    val connector: String,
    val status: String,
    val lastSuccessAt: Instant?,
    val lastFailureAt: Instant?,
    val lastError: String?,
    val consecutiveFailures: Int,
)

data class UserDto(val id: UUID, val name: String, val displayName: String)

@RestController
@RequestMapping("/api/actions")
class ActionsController(
    private val actionRepo: ParentActionRepository,
    private val caseRepo: SchoolCaseRepository,
    private val userRepo: AppUserRepository,
    private val ackRepo: de.schoolagent.domain.repo.ActionAcknowledgmentRepository,
    private val actionService: ActionService,
    private val requirementRepo: de.schoolagent.domain.repo.ChildRequirementRepository,
    private val access: de.schoolagent.security.KidAccessService,
    private val titles: de.schoolagent.i18n.TitleEnglishService,
    private val actionLinks: de.schoolagent.actions.ActionLinkService,
) {

    @GetMapping
    fun list(@RequestParam(required=false) kid: UUID?, @AuthenticationPrincipal user: UserDetails): List<ActionDto> {
        access.requireAccess(user, kid)
        val allowed = access.accessibleKidIds(user)
        val cases = caseRepo.findAll().filter { (it.childId == null || it.childId in allowed) && (kid == null || it.childId == kid) }.associateBy { it.id }
        val users = userRepo.findAll().associateBy { it.id }
        val acks = ackRepo.findAll().groupBy { it.actionId }
        val visibleActions = actionRepo.findAll()
            .filter { cases.containsKey(it.caseId) && cases[it.caseId]?.isInfo() != true && cases[it.caseId]?.isClosed() != true }
        val links = actionLinks.resolveAll(visibleActions, cases)
        return visibleActions.sortedWith(
                compareByDescending<ParentAction> { it.required }
                    .thenByDescending { cases[it.caseId]?.importanceScore ?: 0 }
                    .thenBy(nullsLast()) { it.dueAt },
            )
            .map {
                val saved = it
                saved.toDto(
                    cases[it.caseId]?.title,
                    users[it.assignedTo]?.displayName,
                    cases[it.caseId]?.importanceScore,
                    acks[it.id].orEmpty().mapNotNull { ack ->
                        users[ack.userId]?.let { u -> AcknowledgmentDto(u.name, u.displayName, ack.acknowledgedAt) }
                    },
                    links[saved.id],
                )
            }
    }

  @PutMapping("/{id}/complete")
    fun complete(@PathVariable id: UUID, @AuthenticationPrincipal user: UserDetails): ActionDto {
        requireActionAccess(id, user)
        val saved = actionService.complete(id, user.toActor())
        return saved.toDto(
            caseRepo.findById(saved.caseId).orElse(null)?.title,
            saved.assignedTo?.let { userRepo.findById(it).orElse(null)?.displayName },
            caseRepo.findById(saved.caseId).orElse(null)?.importanceScore,
        )
    }

    @PutMapping("/{id}/reopen")
    fun reopen(@PathVariable id: UUID, @AuthenticationPrincipal user: UserDetails): ActionDto {
        requireActionAccess(id, user)
        val saved = actionService.reopen(id, user.toActor())
        return saved.toDto(
            caseRepo.findById(saved.caseId).orElse(null)?.title,
            saved.assignedTo?.let { userRepo.findById(it).orElse(null)?.displayName },
            caseRepo.findById(saved.caseId).orElse(null)?.importanceScore,
        )
    }

    // ---- manual CRUD ----

    data class CreateActionRequest(
        val title: String,
        val description: String? = null,
        val dueAt: java.time.Instant? = null,
        val externalUrl: String? = null,
    )

    @PostMapping("/case/{caseId}")
    fun create(
        @PathVariable caseId: UUID,
        @RequestBody request: CreateActionRequest,
        @AuthenticationPrincipal user: UserDetails,
    ): ActionDto {
        val caseEntity = caseRepo.findById(caseId).orElse(null)
            ?: throw org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "case not found")
        val childId = caseEntity.childId
        if (childId != null && !access.canAccess(user, childId)) {
            throw org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN, "no access to this case")
        }
        val action = try {
            actionService.createManual(caseId, request.title, request.description, request.dueAt, request.externalUrl, user.toActor())
        } catch (e: DuplicateActionException) {
            throw org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, e.message)
        }
        return action.toDto(caseEntity.title, null, caseEntity.importanceScore)
    }

    data class UpdateActionRequest(
        val title: String? = null,
        val description: String? = null,
        val dueAt: java.time.Instant? = null,
        val externalUrl: String? = null,
    )

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestBody request: UpdateActionRequest,
        @AuthenticationPrincipal user: UserDetails,
    ): ActionDto {
        requireActionAccess(id, user)
        val action = try {
            actionService.updateManual(id, request.title, request.description, request.dueAt, request.externalUrl, user.toActor())
        } catch (e: DuplicateActionException) {
            throw org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, e.message)
        } catch (e: IllegalArgumentException) {
            throw org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
        return action.toDto(
            caseRepo.findById(action.caseId).orElse(null)?.title,
            action.assignedTo?.let { userRepo.findById(it).orElse(null)?.displayName },
            caseRepo.findById(action.caseId).orElse(null)?.importanceScore,
        )
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID, @AuthenticationPrincipal user: UserDetails): ResponseEntity<Void> {
        requireActionAccess(id, user)
        try {
            actionService.remove(id, user.toActor())
        } catch (e: IllegalArgumentException) {
            throw org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
        return ResponseEntity.noContent().build()
    }

    data class SuggestionDto(val type: String, val title: String, val dueAt: java.time.Instant?, val relevantOn: String?)

    @GetMapping("/{id}/suggestions")
    fun suggestions(@PathVariable id: UUID, @AuthenticationPrincipal user: UserDetails): List<SuggestionDto> {
        requireActionAccess(id, user)
        val action = actionRepo.findById(id).orElse(null) ?: return emptyList()
        val siblings = actionRepo.findByCaseId(action.caseId)
            .filter { it.id != action.id && it.status in listOf(ActionStatus.OPEN, ActionStatus.ASSIGNED, ActionStatus.IN_PROGRESS) }
        val requirements = requirementRepo.findByCaseId(action.caseId)
            .filter { it.status == de.schoolagent.domain.enums.RequirementStatus.OPEN }
        return siblings.take(3).map { SuggestionDto("ACTION", it.title, it.dueAt, null) } +
            requirements.take(2).map { SuggestionDto("REQUIREMENT", it.description, null, it.relevantOn?.toString()) }
    }

    private fun requireActionAccess(id: UUID, user: UserDetails) {
        val a = actionRepo.findById(id).orElseThrow { org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "action not found") }
        val c = caseRepo.findById(a.caseId).orElseThrow { CaseNotFoundException(a.caseId) }
        access.requireAccess(user, c.childId)
    }

    private fun ParentAction.toDto(
        caseTitle: String?,
        assignedName: String?,
        score: Int?,
        acknowledgedBy: List<AcknowledgmentDto> = emptyList(),
        externalLink: de.schoolagent.actions.ActionLink? = actionLinks.resolve(this),
    ) = ActionDto(
        id = id,
        caseId = caseId,
        caseTitle = caseTitle,
        title = title,
        description = description,
        dueAt = dueAt,
        assignedTo = assignedTo,
        assignedToName = assignedName,
        status = status.name,
        externalUrl = externalUrl,
        required = required,
        score = score,
        createdAt = createdAt.takeIf { it != Instant.EPOCH },
        acknowledgedBy = acknowledgedBy,
        externalLink = externalLink,
    )
}

@RestController
@RequestMapping("/api/events")
class EventsController(
    private val access: de.schoolagent.security.KidAccessService,
    private val eventRepo: SchoolEventRepository,
    private val caseRepo: SchoolCaseRepository,
    private val titles: de.schoolagent.i18n.TitleEnglishService,
) {

    @GetMapping
    fun list(
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) kid: UUID?,
        @AuthenticationPrincipal user: UserDetails,
    ): List<EventDto> {
        access.requireAccess(user, kid)
        val allowed = access.accessibleKidIds(user)
        val cases = caseRepo.findAll().filter { (it.childId == null || it.childId in allowed) && (kid == null || it.childId == kid) }.associateBy { it.id }
        val events = eventRepo.findAll().filter { (from == null || !(it.endTime ?: it.startTime).isBefore(from)) && (to == null || it.startTime.isBefore(to)) }.sortedBy { it.startTime }
        return events.filter { cases.containsKey(it.caseId) }.map { it.toDto(cases[it.caseId]?.title) }
    }

    private fun SchoolEvent.toDto(caseTitle: String?) = EventDto(
        id = id,
        caseId = caseId,
        caseTitle = caseTitle,
        title = title,
        eventType = eventType.name,
        startTime = startTime,
        endTime = endTime,
        allDay = allDay,
        location = location,
        status = status.name,
    )
}

@RestController
@RequestMapping("/api/audit")
class AuditController(
    private val access: de.schoolagent.security.KidAccessService,
    private val cases: SchoolCaseRepository,
    private val auditRepo: AuditEntryRepository,
    private val clock: Clock,
) {

    @GetMapping
    fun list(@RequestParam(required = false) caseId: UUID?, @AuthenticationPrincipal user: UserDetails): List<AuditDto> {
        val allowed = access.accessibleKidIds(user)
        val visible = cases.findAll().filter { it.childId == null || it.childId in allowed }.map { it.id }.toSet()
        if (caseId != null && caseId !in visible) throw org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN, "no access")
        val entries = caseId
            ?.let { auditRepo.findByCaseIdOrderByCreatedAtDesc(it) }
            ?: auditRepo.findRecentHistory(org.springframework.data.domain.PageRequest.of(0, 200))
        return entries.filter { (it.caseId == null && access.isAdmin(user)) || it.caseId in visible }.map {
            AuditDto(
                id = it.id,
                caseId = it.caseId,
                actor = it.actor.name,
                action = it.action,
                detail = it.detail,
                createdAt = it.createdAt,
            )
        }
    }
}

@RestController
@RequestMapping("/api/health/connectors")
class ConnectorHealthController(
    private val repo: ConnectorHealthRepository,
    private val clock: Clock,
) {

    @GetMapping
    fun list(): List<ConnectorHealthDto> =
        repo.findAll().map {
            ConnectorHealthDto(
                connector = it.connector.name,
                status = it.status.name,
                lastSuccessAt = it.lastSuccessAt,
                lastFailureAt = it.lastFailureAt,
                lastError = it.lastError,
                consecutiveFailures = it.consecutiveFailures,
            )
        }
}

data class RequirementListDto(
    val id: UUID,
    val caseId: UUID,
    val caseTitle: String?,
    val description: String,
    val relevantOn: java.time.LocalDate?,
    val status: String,
    val required: Boolean,
)

@RestController
@RequestMapping("/api/requirements")
class RequirementsController(
    private val requirementRepo: de.schoolagent.domain.repo.ChildRequirementRepository,
    private val caseRepo: SchoolCaseRepository,
    private val clock: java.time.Clock,
    private val requirements: de.schoolagent.requirements.RequirementService,
    private val access: de.schoolagent.security.KidAccessService,
) {
    @GetMapping
    fun list(@RequestParam(required=false) kid: UUID?, @AuthenticationPrincipal user: UserDetails): List<RequirementListDto> {
        access.requireAccess(user, kid)
        val allowed = access.accessibleKidIds(user)
        val cases = caseRepo.findAll().filter { (it.childId == null || it.childId in allowed) && (kid == null || it.childId == kid) }.associateBy { it.id }
        return requirementRepo.findAll()
            .filter { it.status == de.schoolagent.domain.enums.RequirementStatus.OPEN && cases.containsKey(it.caseId) && cases[it.caseId]?.isInfo() != true && cases[it.caseId]?.isClosed() != true }
            .map { it.toDto(cases[it.caseId]?.title) }
    }

    @PutMapping("/{id}/complete")
    fun complete(@PathVariable id: UUID, @AuthenticationPrincipal user: UserDetails): RequirementListDto {
        requireRequirementAccess(id, user)
        val saved = requirements.complete(id, user.toActor())
        return saved.toDto(caseRepo.findById(saved.caseId).orElse(null)?.title)
    }

    data class UpsertRequirementRequest(
        val description: String? = null,
        val relevantOn: java.time.LocalDate? = null,
    )

    @PostMapping("/case/{caseId}")
    fun create(
        @PathVariable caseId: UUID,
        @RequestBody request: UpsertRequirementRequest,
        @AuthenticationPrincipal user: UserDetails,
    ): RequirementListDto {
        val caseEntity = caseRepo.findById(caseId).orElse(null)
            ?: throw org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "case not found")
        val childId = caseEntity.childId
        if (childId != null && !access.canAccess(user, childId)) {
            throw org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN, "no access to this case")
        }
        val saved = try {
            requirements.createManual(
                caseId,
                request.description ?: throw org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "description is required"),
                request.relevantOn,
                user.toActor(),
            )
        } catch (e: de.schoolagent.requirements.DuplicateRequirementException) {
            throw org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, e.message)
        }
        return saved.toDto(caseEntity.title)
    }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestBody request: UpsertRequirementRequest,
        @AuthenticationPrincipal user: UserDetails,
    ): RequirementListDto {
        requireRequirementAccess(id, user)
        val saved = try {
            requirements.updateManual(id, request.description, request.relevantOn, user.toActor())
        } catch (e: de.schoolagent.requirements.DuplicateRequirementException) {
            throw org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, e.message)
        } catch (e: IllegalArgumentException) {
            throw org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
        return saved.toDto(caseRepo.findById(saved.caseId).orElse(null)?.title)
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID, @AuthenticationPrincipal user: UserDetails): ResponseEntity<Void> {
        requireRequirementAccess(id, user)
        try {
            requirements.remove(id, user.toActor())
        } catch (e: IllegalArgumentException) {
            throw org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
        return ResponseEntity.noContent().build()
    }

    private fun requireRequirementAccess(id: UUID, user: UserDetails) {
        val r = requirementRepo.findById(id).orElseThrow { org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "requirement not found") }
        val c = caseRepo.findById(r.caseId).orElseThrow { CaseNotFoundException(r.caseId) }
        access.requireAccess(user, c.childId)
    }

    private fun de.schoolagent.domain.model.ChildRequirement.toDto(caseTitle: String?) = RequirementListDto(
        id = id,
        caseId = caseId,
        caseTitle = caseTitle,
        description = description,
        relevantOn = relevantOn,
        status = status.name,
        required = required,
    )
}

@RestController
@RequestMapping("/api/users")
class UsersController(private val userRepo: AppUserRepository) {

    @GetMapping
    fun list(): List<UserDto> =
        userRepo.findAll().map { UserDto(id = it.id, name = it.name, displayName = it.displayName) }
}

private fun UserDetails.toActor(): Actor = Actor.MANUAL
