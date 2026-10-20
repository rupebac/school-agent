package de.schoolagent.actions

import de.schoolagent.common.DedupKeys
import de.schoolagent.domain.enums.ActionStatus
import de.schoolagent.domain.enums.Actor
import de.schoolagent.domain.enums.CaseStatus
import de.schoolagent.domain.enums.ChangeTargetType
import de.schoolagent.domain.enums.ChangeType
import de.schoolagent.domain.model.AuditEntry
import de.schoolagent.domain.model.CaseChange
import de.schoolagent.domain.model.ParentAction
import de.schoolagent.domain.repo.AuditEntryRepository
import de.schoolagent.domain.repo.CaseChangeRepository
import de.schoolagent.domain.repo.ParentActionRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.service.CaseService
import de.schoolagent.domain.statemachine.CaseStateMachine
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Parent ownership flow (spec §24): assign, mark done, reopen — always with
 * change log + audit, and case status follows the actions: when the last open
 * action completes, a WAITING_FOR_PARENT/ACTION_REQUIRED case completes.
 */
@Service
@Transactional
class ActionService(
    private val actionRepo: ParentActionRepository,
    private val caseRepo: SchoolCaseRepository,
    private val caseService: CaseService,
    private val changeRepo: CaseChangeRepository,
    private val auditRepo: AuditEntryRepository,
    private val clock: Clock,
    private val entityManager: jakarta.persistence.EntityManager? = null,
) {

    private val openStates = listOf(ActionStatus.OPEN, ActionStatus.ASSIGNED, ActionStatus.IN_PROGRESS, ActionStatus.OVERDUE)

    fun assign(actionId: UUID, userId: UUID?, actor: Actor): ParentAction {
        val action = load(actionId)
        action.assignedTo = userId
        if (action.status == ActionStatus.OPEN) action.status = ActionStatus.ASSIGNED
        action.updatedAt = clock.instant()
        val saved = actionRepo.save(action)
        recordChange(saved, ChangeType.CHANGE, before = mapOf("assignedTo" to "null"), after = mapOf("assignedTo" to (userId?.toString() ?: "null")))
        audit(saved.caseId, actor, "ACTION_ASSIGNED", """{"actionId":"${saved.id}","to":"${userId ?: "nobody"}"}""")
        return saved
    }

    fun complete(actionId: UUID, actor: Actor): ParentAction {
        val action = load(actionId)
        if (caseRepo.lockById(action.caseId)?.schoolUpdatePending == true) throw org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT, "The school updated this matter. Review the corrected information before completing it.")
        if (entityManager?.contains(action) == true) entityManager.refresh(action)
        if (action.status == ActionStatus.COMPLETED) return action
        require(action.status != ActionStatus.CANCELLED) { "A cancelled action cannot be completed" }
        action.status = ActionStatus.COMPLETED
        action.completedAt = clock.instant()
        action.updatedAt = clock.instant()
        val saved = actionRepo.save(action)
        recordChange(saved, ChangeType.CHANGE, before = mapOf("status" to "open"), after = mapOf("status" to "COMPLETED"))
        audit(saved.caseId, actor, "ACTION_COMPLETED", """{"actionId":"${saved.id}"}""")

        maybeCompleteCase(saved.caseId, actor)
        return saved
    }

    fun reopen(actionId: UUID, actor: Actor): ParentAction {
        val action = load(actionId)
        action.status = ActionStatus.OPEN
        action.completedAt = null
        action.updatedAt = clock.instant()
        val saved = actionRepo.save(action)
        recordChange(saved, ChangeType.CHANGE, before = mapOf("status" to "COMPLETED"), after = mapOf("status" to "OPEN"))
        audit(saved.caseId, actor, "ACTION_REOPENED", """{"actionId":"${saved.id}"}""")

        val caseEntity = caseRepo.findById(saved.caseId).orElse(null)
        if (caseEntity != null && caseEntity.status == CaseStatus.COMPLETED) {
            caseService.reopen(saved.caseId, actor, "action reopened")
        }
        return saved
    }

    fun createManual(
        caseId: UUID,
        title: String,
        description: String?,
        dueAt: Instant?,
        externalUrl: String?,
        actor: Actor,
    ): ParentAction {
        val trimmed = title.trim()
        if (trimmed.isBlank()) throw IllegalArgumentException("title is required")
        val caseEntity = caseRepo.findById(caseId).orElseThrow { IllegalArgumentException("case $caseId not found") }
        val key = DedupKeys.action(caseId, trimmed)
        if (actionRepo.existsByDedupKey(key)) throw DuplicateActionException()
        val now = clock.instant()
        val saved = actionRepo.save(
            ParentAction(
                caseId = caseId,
                title = trimmed,
                description = description,
                dueAt = dueAt,
                externalUrl = externalUrl,
                dedupKey = key,
                createdAt = now,
                updatedAt = now,
            ),
        )
        recordChange(saved, ChangeType.ADD, before = emptyMap(), after = mapOf("title" to trimmed))
        audit(caseId, actor, "ACTION_CREATED", com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
            mapOf("actionId" to saved.id.toString(), "title" to trimmed)))
        if (caseEntity.status == CaseStatus.INFORMATION_ONLY &&
            CaseStateMachine.canTransition(caseEntity.status, CaseStatus.ACTION_REQUIRED)
        ) {
            caseService.transition(caseEntity, CaseStatus.ACTION_REQUIRED, actor, "manual action added")
        }
        return saved
    }

    fun updateManual(
        actionId: UUID,
        title: String?,
        description: String?,
        dueAt: Instant?,
        externalUrl: String?,
        actor: Actor,
    ): ParentAction {
        val action = load(actionId)
        title?.trim()?.takeIf { it.isNotBlank() }?.let { next ->
            if (next != action.title) {
                val key = DedupKeys.action(action.caseId, next)
                val clash = actionRepo.findByDedupKey(key)
                if (clash != null && clash.id != action.id) throw DuplicateActionException()
                action.title = next
                action.dedupKey = key
            }
        }
        description?.let { action.description = it }
        dueAt?.let { action.dueAt = it }
        externalUrl?.let { if (it != action.externalUrl) action.portalProvider = null; action.externalUrl = it }
        action.updatedAt = clock.instant()
        val saved = actionRepo.save(action)
        recordChange(saved, ChangeType.CHANGE, before = mapOf("title" to "old"), after = mapOf("title" to saved.title))
        audit(saved.caseId, actor, "ACTION_UPDATED", """{"actionId":"${saved.id}"}""")
        return saved
    }

    /** Hard-deletes a manual action; AI-derived actions are cancelled so provenance stays. */
    fun remove(actionId: UUID, actor: Actor): ParentAction? {
        val action = load(actionId)
        return if (action.evidenceId != null) {
            action.status = ActionStatus.CANCELLED
            action.updatedAt = clock.instant()
            val saved = actionRepo.save(action)
            audit(saved.caseId, actor, "ACTION_CANCELLED", """{"actionId":"${saved.id}"}""")
            maybeCompleteCase(saved.caseId, actor)
            saved
        } else {
            audit(action.caseId, actor, "ACTION_DELETED", """{"actionId":"${action.id}"}""")
            actionRepo.delete(action)
            maybeCompleteCase(action.caseId, actor)
            null
        }
    }

    private fun maybeCompleteCase(caseId: UUID, actor: Actor) {
        val caseEntity = caseRepo.findById(caseId).orElse(null) ?: return
        if (caseEntity.status !in listOf(CaseStatus.WAITING_FOR_PARENT, CaseStatus.ACTION_REQUIRED)) return
        val stillOpen = actionRepo.findByCaseIdAndStatusIn(caseId, openStates)
        if (stillOpen.isEmpty()) {
            caseService.transition(caseEntity, CaseStatus.COMPLETED, actor, "all parent actions done")
        }
    }

    private fun load(actionId: UUID): ParentAction =
        actionRepo.findById(actionId).orElseThrow { IllegalArgumentException("action $actionId not found") }

    private fun recordChange(action: ParentAction, type: ChangeType, before: Map<String, String>, after: Map<String, String>) {
        changeRepo.save(
            CaseChange(
                caseId = action.caseId,
                changeType = type,
                targetType = ChangeTargetType.ACTION,
                targetId = action.id,
                beforeJson = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(before),
                afterJson = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(after),
                createdAt = clock.instant(),
            ),
        )
    }

    private fun audit(caseId: UUID?, actor: Actor, action: String, detail: String?) {
        auditRepo.save(AuditEntry(caseId = caseId, actor = actor, action = action, detail = detail, createdAt = clock.instant()))
    }
}

class DuplicateActionException : IllegalArgumentException("An action with this title already exists on this case.")
