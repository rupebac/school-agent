package de.schoolagent.actions

import de.schoolagent.domain.enums.ActionStatus
import de.schoolagent.domain.enums.Actor
import de.schoolagent.domain.enums.CaseStatus
import de.schoolagent.domain.enums.ChangeType
import de.schoolagent.domain.model.AuditEntry
import de.schoolagent.domain.model.CaseChange
import de.schoolagent.domain.model.CaseStatusEntry
import de.schoolagent.domain.model.ParentAction
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.repo.AuditEntryRepository
import de.schoolagent.domain.repo.CaseChangeRepository
import de.schoolagent.domain.repo.CaseStatusEntryRepository
import de.schoolagent.domain.repo.ParentActionRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.service.CaseService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class ActionServiceTest {

    private val actionRepo = mockk<ParentActionRepository>(relaxed = true)
    private val caseRepo = mockk<SchoolCaseRepository>(relaxed = true)
    private val statusRepo = mockk<CaseStatusEntryRepository>(relaxed = true)
    private val changeRepo = mockk<CaseChangeRepository>(relaxed = true)
    private val auditRepo = mockk<AuditEntryRepository>(relaxed = true)

    private val clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var caseService: CaseService
    private lateinit var service: ActionService

    private val otherParentId = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @BeforeEach
    fun setUp() {
        every { actionRepo.save(any<ParentAction>()) } answers { firstArg() }
        every { caseRepo.save(any<SchoolCase>()) } answers { firstArg() }
        every { changeRepo.save(any<CaseChange>()) } answers { firstArg() }
        every { auditRepo.save(any<AuditEntry>()) } answers { firstArg() }
        every { statusRepo.save(any<CaseStatusEntry>()) } answers { firstArg() }
        caseService = CaseService(caseRepo, statusRepo, changeRepo, mockk(relaxed = true), auditRepo, clock)
        service = ActionService(actionRepo, caseRepo, caseService, changeRepo, auditRepo, clock)
    }

    private fun case(status: CaseStatus) = SchoolCase(title = "Schullandheim", status = status)

    private fun action(c: SchoolCase, status: ActionStatus = ActionStatus.OPEN) = ParentAction(
        caseId = c.id,
        title = "Confirm participation",
        status = status,
        dedupKey = "act-test",
    )

    @Test
    fun `assign takes ownership and records change + audit`() {
        val c = case(CaseStatus.WAITING_FOR_PARENT)
        val a = action(c)
        every { actionRepo.findById(a.id) } returns Optional.of(a)

        val saved = service.assign(a.id, otherParentId, Actor.MANUAL)

        assertThat(saved.assignedTo).isEqualTo(otherParentId)
        assertThat(saved.status).isEqualTo(ActionStatus.ASSIGNED)
        verify(exactly = 1) { changeRepo.save(match { it.changeType == ChangeType.CHANGE && it.targetId == a.id }) }
        verify(exactly = 1) { auditRepo.save(match { it.action == "ACTION_ASSIGNED" }) }
    }

    @Test
    fun `completing the last open action completes an ACTION_REQUIRED case`() {
        val c = case(CaseStatus.ACTION_REQUIRED)
        val a = action(c)
        every { actionRepo.findById(a.id) } returns Optional.of(a)
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { actionRepo.findByCaseIdAndStatusIn(c.id, any()) } returns emptyList()

        service.complete(a.id, Actor.MANUAL)

        assertThat(a.status).isEqualTo(ActionStatus.COMPLETED)
        assertThat(c.status).isEqualTo(CaseStatus.COMPLETED)
    }

    @Test
    fun `completing the last open action completes the waiting case`() {
        val c = case(CaseStatus.WAITING_FOR_PARENT)
        val a = action(c)
        every { actionRepo.findById(a.id) } returns Optional.of(a)
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { actionRepo.findByCaseIdAndStatusIn(c.id, any()) } returns emptyList()

        service.complete(a.id, Actor.MANUAL)

        assertThat(a.status).isEqualTo(ActionStatus.COMPLETED)
        assertThat(c.status).isEqualTo(CaseStatus.COMPLETED)
        assertThat(c.closedAt).isEqualTo(clock.instant())
        verify(exactly = 1) {
            statusRepo.save(match { it.toStatus == CaseStatus.COMPLETED && it.reason == "all parent actions done" })
        }
    }

    @Test
    fun `completing one of several open actions leaves the case open`() {
        val c = case(CaseStatus.WAITING_FOR_PARENT)
        val a = action(c)
        every { actionRepo.findById(a.id) } returns Optional.of(a)
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { actionRepo.findByCaseIdAndStatusIn(c.id, any()) } returns listOf(action(c, ActionStatus.ASSIGNED))

        service.complete(a.id, Actor.MANUAL)

        assertThat(c.status).isEqualTo(CaseStatus.WAITING_FOR_PARENT)
    }

    @Test
    fun `reopening an action on a completed case reopens the case`() {
        val c = case(CaseStatus.COMPLETED)
        val a = action(c, ActionStatus.COMPLETED)
        every { actionRepo.findById(a.id) } returns Optional.of(a)
        every { caseRepo.findById(c.id) } returns Optional.of(c)

        service.reopen(a.id, Actor.MANUAL)

        assertThat(a.status).isEqualTo(ActionStatus.OPEN)
        assertThat(c.status).isEqualTo(CaseStatus.INVESTIGATING)
        assertThat(c.investigationComplete).isFalse()
    }

    @Test
    fun `manual create on an info case promotes it to ACTION_REQUIRED`() {
        val c = case(CaseStatus.INFORMATION_ONLY)
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { actionRepo.existsByDedupKey(any()) } returns false

        val saved = service.createManual(c.id, "Buy workbooks", null, null, null, Actor.MANUAL)

        assertThat(saved.title).isEqualTo("Buy workbooks")
        assertThat(c.status).isEqualTo(CaseStatus.ACTION_REQUIRED)
        verify(exactly = 1) { auditRepo.save(match { it.action == "ACTION_CREATED" }) }
    }

    @Test
    fun `duplicate manual title is rejected`() {
        val c = case(CaseStatus.ACTION_REQUIRED)
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { actionRepo.existsByDedupKey(any()) } returns true

        org.junit.jupiter.api.assertThrows<DuplicateActionException> {
            service.createManual(c.id, "Buy workbooks", null, null, null, Actor.MANUAL)
        }
    }

    @Test
    fun `deleting a manual action hard-deletes it`() {
        val c = case(CaseStatus.ACTION_REQUIRED)
        val a = action(c)
        every { actionRepo.findById(a.id) } returns Optional.of(a)
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { actionRepo.findByCaseIdAndStatusIn(c.id, any()) } returns emptyList()

        val remaining = service.remove(a.id, Actor.MANUAL)

        assertThat(remaining).isNull()
        verify(exactly = 1) { actionRepo.delete(a) }
        assertThat(c.status).isEqualTo(CaseStatus.COMPLETED)
    }

    @Test
    fun `deleting an AI-derived action only cancels it`() {
        val c = case(CaseStatus.ACTION_REQUIRED)
        val a = action(c)
        a.evidenceId = UUID.randomUUID()
        every { actionRepo.findById(a.id) } returns Optional.of(a)
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { actionRepo.findByCaseIdAndStatusIn(c.id, any()) } returns emptyList()

        val remaining = service.remove(a.id, Actor.MANUAL)

        assertThat(remaining?.status).isEqualTo(ActionStatus.CANCELLED)
        verify(exactly = 0) { actionRepo.delete(any<ParentAction>()) }
    }
}
