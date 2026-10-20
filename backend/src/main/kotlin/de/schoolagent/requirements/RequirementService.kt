package de.schoolagent.requirements

import de.schoolagent.common.DedupKeys
import de.schoolagent.domain.enums.Actor
import de.schoolagent.domain.enums.ChangeTargetType
import de.schoolagent.domain.enums.ChangeType
import de.schoolagent.domain.enums.RequirementStatus
import de.schoolagent.domain.model.AuditEntry
import de.schoolagent.domain.model.CaseChange
import de.schoolagent.domain.model.ChildRequirement
import de.schoolagent.domain.repo.AuditEntryRepository
import de.schoolagent.domain.repo.CaseChangeRepository
import de.schoolagent.domain.repo.ChildRequirementRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@Service
@Transactional
class RequirementService(
    private val requirementRepo: ChildRequirementRepository,
    private val caseRepo: SchoolCaseRepository,
    private val changeRepo: CaseChangeRepository,
    private val auditRepo: AuditEntryRepository,
    private val clock: Clock,
    private val entityManager: jakarta.persistence.EntityManager? = null,
) {

    fun createManual(caseId: UUID, description: String, relevantOn: LocalDate?, actor: Actor): ChildRequirement {
        val trimmed = description.trim()
        if (trimmed.isBlank()) throw IllegalArgumentException("description is required")
        caseRepo.findById(caseId).orElseThrow { IllegalArgumentException("case $caseId not found") }
        val key = DedupKeys.requirement(caseId, trimmed, relevantOn?.toString())
        if (requirementRepo.existsByDedupKey(key)) throw DuplicateRequirementException()
        val now = clock.instant()
        val saved = requirementRepo.save(
            ChildRequirement(
                caseId = caseId,
                description = trimmed,
                relevantOn = relevantOn,
                dedupKey = key,
                createdAt = now,
                updatedAt = now,
            ),
        )
        recordChange(saved, ChangeType.ADD, mapOf("description" to trimmed))
        audit(caseId, actor, "REQUIREMENT_CREATED", """{"requirementId":"${saved.id}"}""")
        return saved
    }

    fun updateManual(id: UUID, description: String?, relevantOn: LocalDate?, actor: Actor): ChildRequirement {
        val found = load(id)
        val nextDesc = description?.trim()?.takeIf { it.isNotBlank() } ?: found.description
        val key = DedupKeys.requirement(found.caseId, nextDesc, relevantOn?.toString())
        val clash = requirementRepo.findByDedupKey(key)
        if (clash != null && clash.id != found.id) throw DuplicateRequirementException()
        found.description = nextDesc
        found.relevantOn = relevantOn
        found.dedupKey = key
        found.updatedAt = clock.instant()
        val saved = requirementRepo.save(found)
        recordChange(saved, ChangeType.CHANGE, mapOf("description" to saved.description))
        audit(saved.caseId, actor, "REQUIREMENT_UPDATED", """{"requirementId":"${saved.id}"}""")
        return saved
    }

    fun complete(id: UUID, actor: Actor): ChildRequirement {
        val caseId = load(id).caseId
        if (caseRepo.lockById(caseId)?.schoolUpdatePending == true) throw org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT, "The school updated this matter. Review the corrected information before completing it.")
        val current = requirementRepo.lockById(id) ?: throw IllegalArgumentException("Requirement not found")
        if (entityManager?.contains(current) == true) entityManager.refresh(current)
        if (current.status == RequirementStatus.DONE) return current
        require(current.status == RequirementStatus.OPEN) { "Only open requirements can be completed" }
        current.status = RequirementStatus.DONE
        current.updatedAt = clock.instant()
        val saved = requirementRepo.save(current)
        recordChange(saved, ChangeType.CHANGE, mapOf("status" to "DONE"), mapOf("status" to "OPEN"))
        audit(saved.caseId, actor, "REQUIREMENT_COMPLETED", """{"requirementId":"${saved.id}"}""")
        return saved
    }

    fun remove(id: UUID, actor: Actor): ChildRequirement {
        val found = load(id)
        found.status = RequirementStatus.CANCELLED
        found.updatedAt = clock.instant()
        val saved = requirementRepo.save(found)
        recordChange(saved, ChangeType.CANCEL, mapOf("status" to "CANCELLED"))
        audit(saved.caseId, actor, "REQUIREMENT_CANCELLED", """{"requirementId":"${saved.id}"}""")
        return saved
    }

    private fun load(id: UUID): ChildRequirement =
        requirementRepo.findById(id).orElseThrow { IllegalArgumentException("requirement $id not found") }

    private fun recordChange(req: ChildRequirement, type: ChangeType, after: Map<String, String>, before: Map<String, String> = emptyMap()) {
        changeRepo.save(
            CaseChange(
                caseId = req.caseId,
                changeType = type,
                targetType = ChangeTargetType.REQUIREMENT,
                targetId = req.id,
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

class DuplicateRequirementException : IllegalArgumentException("This item already exists on this case.")
