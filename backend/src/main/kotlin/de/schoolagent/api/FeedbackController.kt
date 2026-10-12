package de.schoolagent.api

import de.schoolagent.domain.enums.Actor
import de.schoolagent.domain.enums.CaseStatus
import de.schoolagent.domain.enums.Importance
import de.schoolagent.domain.model.AuditEntry
import de.schoolagent.domain.model.CaseStatusEntry
import de.schoolagent.domain.model.FamilyRule
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.repo.AuditEntryRepository
import de.schoolagent.domain.repo.CaseStatusEntryRepository
import de.schoolagent.domain.repo.FamilyRuleRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

/**
 * Feedback loop (spec §35): parents correct the agent — explicit rules are
 * stored and fed into future reasoning; no model fine-tuning.
 */
@RestController
@RequestMapping("/api/cases/{id}/feedback")
class FeedbackController(
    private val caseRepo: SchoolCaseRepository,
    private val statusRepo: CaseStatusEntryRepository,
    private val ruleRepo: FamilyRuleRepository,
    private val auditRepo: AuditEntryRepository,
    private val clock: Clock,
    private val inbox: de.schoolagent.domain.service.FamilyInboxService,
    private val lifecycle: de.schoolagent.domain.service.CaseService,
) {

    enum class FeedbackType { IRRELEVANT, MISSING_ACTION, WRONG_DATE, WRONG_EVENT, ALWAYS_NOTIFY, NOT_IMPORTANT }

    data class FeedbackRequest(val type: FeedbackType, val note: String? = null)

    @PostMapping
    @org.springframework.transaction.annotation.Transactional
    fun feedback(
        @PathVariable id: UUID,
        @RequestBody request: FeedbackRequest,
        @AuthenticationPrincipal user: UserDetails,
    ): ResponseEntity<Map<String, Any>> {
        val caseEntity = inbox.accessible(id, user)
        val actor = Actor.MANUAL
        val now = clock.instant()

        // rule for future reasoning (spec §35: feedback becomes context)
        ruleRepo.save(
            FamilyRule(
                ruleKey = "feedback:${request.type.name.lowercase()}",
                ruleValue = """{"caseTitle":"${caseEntity.title.replace("\"", "")}","note":"${(request.note ?: "").replace("\"", "")}","by":"${user.username}","at":"$now"}""",
                createdAt = now,
                updatedAt = now,
            ),
        )

        when (request.type) {
            FeedbackType.IRRELEVANT -> {
                if (de.schoolagent.domain.statemachine.CaseStateMachine.canTransition(caseEntity.status, CaseStatus.CANCELLED))
                    lifecycle.transition(caseEntity, CaseStatus.CANCELLED, actor, "marked irrelevant by ${user.username}")
            }
            FeedbackType.NOT_IMPORTANT -> { caseEntity.muted = true }
            FeedbackType.ALWAYS_NOTIFY -> { caseEntity.muted = false; caseEntity.importance = Importance.IMMEDIATE; caseEntity.importanceScore = 5 }
            else -> Unit // MISSING_ACTION / WRONG_DATE / WRONG_EVENT: rule recorded, matter open for re-reasoning
        }
        caseEntity.updatedAt = now
        caseRepo.save(caseEntity)
        auditRepo.save(
            AuditEntry(
                caseId = id,
                actor = actor,
                action = "FEEDBACK_${request.type.name}",
                detail = request.note?.take(300),
                createdAt = now,
            ),
        )
        return ResponseEntity.ok(mapOf("ok" to true))
    }
}
