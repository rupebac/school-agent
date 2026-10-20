package de.schoolagent.ingestion

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.schoolagent.connectors.school.*
import de.schoolagent.domain.enums.*
import de.schoolagent.domain.model.*
import de.schoolagent.domain.repo.*
import de.schoolagent.domain.service.CaseService
import org.springframework.stereotype.Service
import java.time.Clock

/** Receipt, submitted answers and local family completion are independent observations.
 * Only a task explicitly linked to this native form can be resolved by its school status.
 */
@Service
class SchoolResponseReconciliation(
    private val actions: ParentActionRepository,
    private val requirements: ChildRequirementRepository,
    private val changes: CaseChangeRepository,
    private val audit: AuditEntryRepository,
    private val lifecycle: CaseService,
    private val cases: SchoolCaseRepository,
    private val clock: Clock,
) {
    private val json = jacksonObjectMapper()

    fun reconcile(c: SchoolCase, key: String, previous: String?, response: SchoolResponse?,
                  provider: SchoolProvider, url: String?) {
        if (response == null || response.response == "UNKNOWN") return
        val old = previous?.let { runCatching { json.readTree(it) }.getOrNull() }
        val priorQuestions = old?.path("questionIds")?.map { it.asText() }?.toSet()
        val newQuestions = priorQuestions != null && response.questionIds.toSet() != priorQuestions
        val pending = response.response in setOf("PENDING", "PARTIAL", "CLOSED")
        var action = actions.findByDedupKey("school-response:$key")
        if (action == null && (!pending || response.questionIds.isEmpty())) return
        val created = action == null
        if (action == null) action = ParentAction(caseId = c.id, title = "Respond to the school form",
            dedupKey = "school-response:$key", createdAt = clock.instant())
        require(action.caseId == c.id) { "School form belongs to a different case" }
        val before = snapshot(action)
        action.dueAt = response.deadline
        action.externalUrl = url
        action.portalProvider = provider.name
        action.required = true
        action.title = if (response.response == "CLOSED") "Contact the school about the unanswered form" else "Respond to the school form"
        action.description = "Submit the school's response form in the portal. Downloading its document does not submit answers."
        when {
            response.response in setOf("SUBMITTED", "NOT_REQUIRED") -> {
                action.status = if (response.response == "SUBMITTED") ActionStatus.COMPLETED else ActionStatus.CANCELLED
                if (action.status == ActionStatus.COMPLETED && action.completedAt == null) action.completedAt = clock.instant()
            }
            pending && (newQuestions || old?.path("response")?.asText() == "SUBMITTED") -> {
                action.status = ActionStatus.OPEN
                action.completedAt = null
            }
        }
        val after = snapshot(action)
        if (!created && before == after) return
        action.updatedAt = clock.instant()
        actions.save(action)
        changes.save(CaseChange(caseId = c.id, changeType = if (created) ChangeType.ADD else ChangeType.CHANGE,
            targetType = ChangeTargetType.ACTION, targetId = action.id, beforeJson = if (created) null else before,
            afterJson = after, createdAt = clock.instant()))
        audit.save(AuditEntry(caseId = c.id, actor = Actor.SYSTEM, action = "SCHOOL_RESPONSE_OBSERVED",
            detail = json.writeValueAsString(mapOf("actionId" to action.id, "response" to response.response)), createdAt = clock.instant()))
        if (!c.schoolUpdatePending) {
            val outstanding = actions.findByCaseId(c.id).any { it.required && it.status !in setOf(ActionStatus.COMPLETED, ActionStatus.CANCELLED) } ||
                requirements.findByCaseId(c.id).any { it.required && it.status == RequirementStatus.OPEN }
            if (outstanding) {
                c.informationOnly = false
                c.classificationReview = c.infoOverride == true
                if (action.required && action.status !in setOf(ActionStatus.COMPLETED, ActionStatus.CANCELLED)) {
                    c.importanceScore = maxOf(c.importanceScore, 4)
                    c.importance = importanceFromScore(c.importanceScore)
                }
                if (c.status == CaseStatus.COMPLETED || c.status == CaseStatus.CANCELLED) lifecycle.reopen(c.id, Actor.SYSTEM, "School requires a renewed response")
                else if (c.status == CaseStatus.INFORMATION_ONLY) lifecycle.transition(c, CaseStatus.ACTION_REQUIRED, Actor.SYSTEM, "School response required")
            } else if (c.status in setOf(CaseStatus.ACTION_REQUIRED, CaseStatus.WAITING_FOR_PARENT))
                lifecycle.transition(c, CaseStatus.COMPLETED, Actor.SYSTEM, "School confirmed the linked response")
            // First observation establishes school status; it must not replay old forms.
            c.schoolNotificationPending = c.schoolNotificationPending || (previous != null && !c.suppressInitialNotification)
            cases.save(c)
        }
    }

    private fun snapshot(a: ParentAction) = json.writeValueAsString(mapOf("status" to a.status.name, "title" to a.title,
        "dueAt" to a.dueAt?.toString(), "url" to a.externalUrl, "required" to a.required))
}
