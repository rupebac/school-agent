package de.schoolagent.domain.service

import de.schoolagent.common.DedupKeys
import de.schoolagent.domain.enums.Actor
import de.schoolagent.domain.enums.CaseCategory
import de.schoolagent.domain.enums.CaseStatus
import de.schoolagent.domain.enums.ChangeTargetType
import de.schoolagent.domain.enums.ChangeType
import de.schoolagent.domain.model.AuditEntry
import de.schoolagent.domain.model.CaseChange
import de.schoolagent.domain.model.CaseStatusEntry
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.model.Source
import de.schoolagent.domain.repo.AuditEntryRepository
import de.schoolagent.domain.repo.CaseChangeRepository
import de.schoolagent.domain.repo.CaseStatusEntryRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.repo.SourceRepository
import de.schoolagent.domain.statemachine.CaseStateMachine
import de.schoolagent.domain.statemachine.InvalidCaseTransitionException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Core case lifecycle service. Every mutation goes through here so that
 * history (CaseStatusEntry), audit (AuditEntry) and the change log
 * (CaseChange) are always written together with the state change.
 */
@Service
@Transactional
class CaseService(
    private val caseRepo: SchoolCaseRepository,
    private val statusRepo: CaseStatusEntryRepository,
    private val changeRepo: CaseChangeRepository,
    private val sourceRepo: SourceRepository,
    private val auditRepo: AuditEntryRepository,
    private val clock: Clock,
) {

    fun createCase(
        childId: UUID?,
        title: String,
        category: CaseCategory = CaseCategory.OTHER,
        dedupKey: String? = null,
        actor: Actor = Actor.SYSTEM,
    ): SchoolCase {
        val now = clock.instant()
        val newCase = SchoolCase(
            childId = childId,
            title = title,
            category = category,
            status = CaseStatus.NEW,
            dedupKey = dedupKey,
            openedAt = now,
            createdAt = now,
            updatedAt = now,
        )
        val saved = caseRepo.save(newCase)
        statusRepo.save(
            CaseStatusEntry(
                caseId = saved.id,
                fromStatus = null,
                toStatus = CaseStatus.NEW,
                reason = "case created",
                actor = actor,
                createdAt = now,
            ),
        )
        audit(saved.id, actor, "CASE_CREATED", """{"title":"${escape(title)}"}""")
        return saved
    }

    fun findByDedupKey(dedupKey: String): SchoolCase? = caseRepo.findByDedupKey(dedupKey)

    /**
     * Attach a source to its case, idempotently. Sources are deduplicated by
     * their dedupKey at ingestion time, so a second call for the same source
     * is a no-op (spec §38). A source already owned by another case is a
     * resolution error and is rejected rather than silently re-pointed.
     */
    fun attachSource(case: SchoolCase, source: Source, actor: Actor = Actor.SYSTEM): AttachResult {
        if (source.caseId == case.id) return AttachResult.ALREADY_ATTACHED
        require(source.caseId == null) {
            "source ${source.id} already belongs to case ${source.caseId}, refusing to re-attach to ${case.id}"
        }
        val wasReopened = maybeReopen(case, "new source attached: ${source.sourceType}", actor)
        source.caseId = case.id
        source.updatedAt = clock.instant()
        sourceRepo.save(source)
        audit(case.id, actor, "SOURCE_ATTACHED", """{"sourceId":"${source.id}"}""")
        return if (wasReopened) AttachResult.ATTACHED_AND_REOPENED else AttachResult.ATTACHED
    }

    /** Transition with guards, history and audit. Throws on illegal transitions. */
    fun transition(
        caseId: UUID,
        to: CaseStatus,
        actor: Actor = Actor.SYSTEM,
        reason: String? = null,
    ): SchoolCase {
        val found = caseRepo.findById(caseId).orElseThrow {
            IllegalArgumentException("case $caseId not found")
        }
        return transition(found, to, actor, reason)
    }

    fun transition(
        found: SchoolCase,
        to: CaseStatus,
        actor: Actor = Actor.SYSTEM,
        reason: String? = null,
    ): SchoolCase {
        val from = found.status
        CaseStateMachine.assertTransition(from, to)
        val now = clock.instant()
        found.status = to
        found.updatedAt = now
        if (to == CaseStatus.COMPLETED || to == CaseStatus.CANCELLED) {
            found.closedAt = now
        } else {
            found.closedAt = null
        }
        val saved = caseRepo.save(found)
        statusRepo.save(
            CaseStatusEntry(
                caseId = saved.id,
                fromStatus = from,
                toStatus = to,
                reason = reason,
                actor = actor,
                createdAt = now,
            ),
        )
        if (CaseStateMachine.isReopen(from, to)) {
            changeRepo.save(
                CaseChange(
                    caseId = saved.id,
                    changeType = ChangeType.REOPEN,
                    targetType = ChangeTargetType.CASE,
                    createdAt = now,
                ),
            )
        }
        audit(saved.id, actor, "CASE_STATUS_CHANGED", """{"from":"$from","to":"$to"}""")
        return saved
    }

    /** New evidence on an existing case reopens it into investigation, spec §7. */
    fun reopen(caseId: UUID, actor: Actor = Actor.SYSTEM, reason: String): SchoolCase =
        transition(caseId, CaseStatus.INVESTIGATING, actor, reason)

    /** Stop (or resume) WhatsApp pings for this letter without marking it done. */
    fun setMuted(case: SchoolCase, muted: Boolean, actor: Actor): SchoolCase {
        case.muted = muted
        case.updatedAt = clock.instant()
        val saved = caseRepo.save(case)
        audit(saved.id, actor, if (muted) "CASE_MUTED" else "CASE_UNMUTED", null)
        return saved
    }

    private fun maybeReopen(case: SchoolCase, reason: String, actor: Actor): Boolean {
        if (!CaseStateMachine.canTransition(case.status, CaseStatus.INVESTIGATING)) return false
        transition(case, CaseStatus.INVESTIGATING, actor, "reopen: $reason")
        // A reopened case must not inherit "investigation complete" from its
        // previous life — the new evidence has not been investigated yet.
        case.investigationComplete = false
        case.investigationNotes = null
        caseRepo.save(case)
        return true
    }

    /** Enrich derived wording/facts without resetting the family's lifecycle decision. */
    fun recordEnrichment(case: SchoolCase, detail: String) = audit(case.id, Actor.AI, "CASE_ENRICHED", detail)

    private fun audit(caseId: UUID?, actor: Actor, action: String, detail: String?) {
        auditRepo.save(
            AuditEntry(
                caseId = caseId,
                actor = actor,
                action = action,
                detail = detail,
                createdAt = clock.instant(),
            ),
        )
    }

    private fun escape(value: String): String =
        com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value).let { it.substring(1, it.length - 1) }

    enum class AttachResult { ATTACHED, ATTACHED_AND_REOPENED, ALREADY_ATTACHED }
}
