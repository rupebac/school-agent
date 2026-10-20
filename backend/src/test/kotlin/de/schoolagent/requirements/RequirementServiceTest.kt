package de.schoolagent.requirements

import de.schoolagent.domain.enums.Actor
import de.schoolagent.domain.enums.RequirementStatus
import de.schoolagent.domain.model.AuditEntry
import de.schoolagent.domain.model.CaseChange
import de.schoolagent.domain.model.ChildRequirement
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.repo.AuditEntryRepository
import de.schoolagent.domain.repo.CaseChangeRepository
import de.schoolagent.domain.repo.ChildRequirementRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class RequirementServiceTest {

    private val requirementRepo = mockk<ChildRequirementRepository>(relaxed = true)
    private val caseRepo = mockk<SchoolCaseRepository>(relaxed = true)
    private val changeRepo = mockk<CaseChangeRepository>(relaxed = true)
    private val auditRepo = mockk<AuditEntryRepository>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var service: RequirementService

    @BeforeEach
    fun setUp() {
        every { requirementRepo.save(any<ChildRequirement>()) } answers { firstArg() }
        every { changeRepo.save(any<CaseChange>()) } answers { firstArg() }
        every { auditRepo.save(any<AuditEntry>()) } answers { firstArg() }
        service = RequirementService(requirementRepo, caseRepo, changeRepo, auditRepo, clock)
    }

    @Test fun `completion is audited once and cancelled requirements cannot complete`() {
        val c = SchoolCase(title = "Gym")
        val requirement = ChildRequirement(caseId = c.id, description = "Indoor shoes", dedupKey = "synthetic")
        every { requirementRepo.lockById(requirement.id) } returns requirement
        every { requirementRepo.findById(requirement.id) } returns Optional.of(requirement)
        every { caseRepo.lockById(c.id) } returns c
        service.complete(requirement.id, Actor.MANUAL)
        service.complete(requirement.id, Actor.MANUAL)
        assertThat(requirement.status).isEqualTo(RequirementStatus.DONE)
        verify(exactly = 1) { auditRepo.save(match { it.action == "REQUIREMENT_COMPLETED" }) }
        verify(exactly = 1) { changeRepo.save(match { it.beforeJson?.contains("OPEN") == true && it.afterJson?.contains("DONE") == true }) }
        requirement.status = RequirementStatus.CANCELLED
        org.assertj.core.api.Assertions.assertThatThrownBy { service.complete(requirement.id, Actor.MANUAL) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `manual create records the kit item`() {
        val c = SchoolCase(title = "Gym")
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { requirementRepo.existsByDedupKey(any()) } returns false

        val saved = service.createManual(c.id, "Indoor shoes", LocalDate.of(2026, 9, 29), Actor.MANUAL)

        assertThat(saved.description).isEqualTo("Indoor shoes")
        assertThat(saved.relevantOn).isEqualTo(LocalDate.of(2026, 9, 29))
        verify { auditRepo.save(match { it.action == "REQUIREMENT_CREATED" }) }
    }

    @Test
    fun `duplicate description on the same day is rejected`() {
        val c = SchoolCase(title = "Gym")
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { requirementRepo.existsByDedupKey(any()) } returns true

        org.junit.jupiter.api.assertThrows<DuplicateRequirementException> {
            service.createManual(c.id, "Indoor shoes", LocalDate.of(2026, 9, 29), Actor.MANUAL)
        }
    }

    @Test
    fun `update changes the wording and date`() {
        val c = SchoolCase(title = "Gym")
        val req = ChildRequirement(
            caseId = c.id,
            description = "Shoes",
            relevantOn = LocalDate.of(2026, 9, 29),
            dedupKey = "req-old",
        )
        every { requirementRepo.findById(req.id) } returns Optional.of(req)
        every { requirementRepo.findByDedupKey(any()) } returns null

        val saved = service.updateManual(req.id, "Indoor shoes", LocalDate.of(2026, 9, 30), Actor.MANUAL)

        assertThat(saved.description).isEqualTo("Indoor shoes")
        assertThat(saved.relevantOn).isEqualTo(LocalDate.of(2026, 9, 30))
        verify { auditRepo.save(match { it.action == "REQUIREMENT_UPDATED" }) }
    }

    @Test
    fun `remove cancels the item so it leaves the list`() {
        val req = ChildRequirement(
            caseId = UUID.randomUUID(),
            description = "Shoes",
            dedupKey = "req-del",
        )
        every { requirementRepo.findById(req.id) } returns Optional.of(req)

        val saved = service.remove(req.id, Actor.MANUAL)

        assertThat(saved.status).isEqualTo(RequirementStatus.CANCELLED)
        verify { auditRepo.save(match { it.action == "REQUIREMENT_CANCELLED" }) }
    }
}
