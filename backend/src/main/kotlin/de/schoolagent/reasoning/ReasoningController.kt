package de.schoolagent.reasoning

import de.schoolagent.domain.enums.Actor
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Manual pipeline endpoints (admin/recovery, e.g. after enabling the AI or vision model). */
@RestController
@RequestMapping("/api/reason")
class ReasoningController(
    private val consolidation: ObligationConsolidationService,
    private val reasoning: CaseReasoningService,
    private val inbox: de.schoolagent.domain.service.FamilyInboxService,
    private val caseRepo: de.schoolagent.domain.repo.SchoolCaseRepository,
    private val investigation: de.schoolagent.ingestion.InvestigationService,
) {

    @PostMapping("/consolidate")
    fun consolidate(@RequestBody request: ReprocessRequest, @org.springframework.security.core.annotation.AuthenticationPrincipal user: org.springframework.security.core.userdetails.UserDetails): Map<String, Int> {
        inbox.accessible(request.caseId, user)
        return mapOf("consolidated" to consolidation.consolidate(request.caseId))
    }

    @PostMapping("/reprocess")
    fun reprocess(@RequestBody request: ReprocessRequest, @org.springframework.security.core.annotation.AuthenticationPrincipal user: org.springframework.security.core.userdetails.UserDetails): ResponseEntity<Map<String, Any>> {
        inbox.accessible(request.caseId, user)
        return try {
            reasoning.reopenForReprocessing(request.caseId)
            investigation.investigate(request.caseId)
            reasoning.reason(request.caseId)
            ResponseEntity.ok(mapOf("ok" to true))
        } catch (e: Exception) {
            ResponseEntity.ok(mapOf("ok" to false, "error" to (e.message ?: "failed")))
        }
    }

    @PostMapping("/pending")
    fun reasonPending(): ResponseEntity<Map<String, Any>> = try {
        val n = reasoning.reasonAllPending()
        ResponseEntity.ok(mapOf("ok" to true, "reasoned" to n))
    } catch (e: Exception) {
        ResponseEntity.ok(mapOf("ok" to false, "error" to (e.message ?: "failed")))
    }

    /** Re-runs the model over cases the deterministic fallback classified. */
    @PostMapping("/fallback-cases")
    fun reReasonFallback(): ResponseEntity<Map<String, Any>> = try {
        val n = reasoning.reReasonFallbackCases()
        ResponseEntity.ok(mapOf("ok" to true, "reReasoned" to n))
    } catch (e: Exception) {
        ResponseEntity.ok(mapOf("ok" to false, "error" to (e.message ?: "failed")))
    }

    /** Drop leftover FYI scores that parked at 3. No model, no WhatsApp. */
    @PostMapping("/scores")
    fun recalibrateScores(): ResponseEntity<Map<String, Any>> = try {
        val n = reasoning.recalibrateStoredScores()
        ResponseEntity.ok(mapOf("ok" to true, "updated" to n))
    } catch (e: Exception) {
        ResponseEntity.ok(mapOf("ok" to false, "error" to (e.message ?: "failed")))
    }

    data class ReprocessRequest(val caseId: java.util.UUID)
}
