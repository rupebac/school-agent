package de.schoolagent.reasoning

import de.schoolagent.actions.ActionService
import de.schoolagent.ai.ModelRouter
import de.schoolagent.domain.enums.Actor
import de.schoolagent.domain.model.*
import de.schoolagent.domain.repo.*
import de.schoolagent.requirements.RequirementService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

class ObligationConsolidationServiceTest {
    private val actions = mockk<ParentActionRepository>()
    private val requirements = mockk<ChildRequirementRepository>()
    private val cases = mockk<SchoolCaseRepository>()
    private val actionService = mockk<ActionService>(relaxed = true)
    private val requirementService = mockk<RequirementService>(relaxed = true)
    private val model = mockk<ModelRouter>()
    private val transactions = mockk<TransactionTemplate>()
    private val service = ObligationConsolidationService(actions, requirements, cases, actionService, requirementService, model, transactions)
    private val caseId = UUID.randomUUID()
    private val action = ParentAction(caseId = caseId, title = "Return the permission form", dedupKey = "action")
    private val covered = ChildRequirement(caseId = caseId, description = "Hand the signed form to the teacher before class", dedupKey = "covered")
    private val separate = ChildRequirement(caseId = caseId, description = "Bring trainers for the trip", dedupKey = "separate")
    private fun setup() {
        every { actions.findByCaseId(caseId) } returns listOf(action)
        every { requirements.findByCaseId(caseId) } returns listOf(covered, separate)
        every { cases.lockById(caseId) } returns SchoolCase(id = caseId, title = "Trip")
        every { model.available(any()) } returns true
        every { transactions.execute(any<TransactionCallback<Int>>()) } answers { firstArg<TransactionCallback<Int>>().doInTransaction(mockk<TransactionStatus>()) }
    }
    @Test fun `general overlap keeps verbatim details and independent child work and ignores foreign ids`() {
        setup()
        every { model.complete(any(), any(), any(), any()) } returns """{"covered":[{"actionId":"${action.id}","requirementId":"${covered.id}"},{"actionId":"${UUID.randomUUID()}","requirementId":"${separate.id}"}]}"""
        assertThat(service.consolidate(caseId)).isEqualTo(1)
        verify { actionService.updateManual(action.id, action.title, covered.description, null, null, Actor.AI) }
        verify(exactly = 1) { requirementService.remove(covered.id, Actor.AI) }
        verify(exactly = 0) { requirementService.remove(separate.id, any()) }
    }
    @Test fun `an edit while the model works prevents applying a stale consolidation`() {
        setup()
        every { model.complete(any(), any(), any(), any()) } answers {
            action.title = "A changed parent instruction"
            """{"covered":[{"actionId":"${action.id}","requirementId":"${covered.id}"}]}"""
        }
        assertThat(service.consolidate(caseId)).isZero()
        verify(exactly = 0) { requirementService.remove(any(), any()) }
    }
}
