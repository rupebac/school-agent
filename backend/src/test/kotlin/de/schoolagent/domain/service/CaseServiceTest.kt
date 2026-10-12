package de.schoolagent.domain.service

import de.schoolagent.common.DedupKeys
import de.schoolagent.domain.enums.Actor
import de.schoolagent.domain.enums.CaseCategory
import de.schoolagent.domain.enums.CaseStatus
import de.schoolagent.domain.enums.SourceType
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
import de.schoolagent.domain.service.CaseService.AttachResult
import de.schoolagent.domain.statemachine.InvalidCaseTransitionException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class CaseServiceTest {

    private val caseRepo = mockk<SchoolCaseRepository>(relaxed = true)
    private val statusRepo = mockk<CaseStatusEntryRepository>(relaxed = true)
    private val changeRepo = mockk<CaseChangeRepository>(relaxed = true)
    private val sourceRepo = mockk<SourceRepository>(relaxed = true)
    private val auditRepo = mockk<AuditEntryRepository>(relaxed = true)

    private val fixedInstant = Instant.parse("2026-09-23T10:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    private lateinit var service: CaseService

    @BeforeEach
    fun setUp() {
        every { caseRepo.save(any<SchoolCase>()) } answers { firstArg() }
        every { statusRepo.save(any<CaseStatusEntry>()) } answers { firstArg() }
        every { changeRepo.save(any<CaseChange>()) } answers { firstArg() }
        every { sourceRepo.save(any<Source>()) } answers { firstArg() }
        every { auditRepo.save(any<AuditEntry>()) } answers { firstArg() }
        service = CaseService(caseRepo, statusRepo, changeRepo, sourceRepo, auditRepo, clock)
    }

    private fun newCase(status: CaseStatus = CaseStatus.NEW) = SchoolCase(
        title = "Schullandheim Oktober",
        category = CaseCategory.TRIP,
        status = status,
        openedAt = fixedInstant,
        createdAt = fixedInstant,
        updatedAt = fixedInstant,
    )

    @Test
    fun `createCase starts at NEW with history and audit`() {
        val created = service.createCase(null, "Schullandheim Oktober", CaseCategory.TRIP)

        assertThat(created.status).isEqualTo(CaseStatus.NEW)
        assertThat(created.openedAt).isEqualTo(fixedInstant)
        verify(exactly = 1) {
            statusRepo.save(match { it.fromStatus == null && it.toStatus == CaseStatus.NEW })
        }
        verify(exactly = 1) { auditRepo.save(match { it.action == "CASE_CREATED" }) }
    }

    @Test
    fun `valid transition updates status and records history`() {
        val case = newCase()
        service.transition(case, CaseStatus.INGESTING, Actor.SYSTEM, "source stored")

        assertThat(case.status).isEqualTo(CaseStatus.INGESTING)
        verify(exactly = 1) {
            statusRepo.save(
                match {
                    it.fromStatus == CaseStatus.NEW &&
                        it.toStatus == CaseStatus.INGESTING &&
                        it.reason == "source stored"
                },
            )
        }
        verify(exactly = 1) { auditRepo.save(match { it.action == "CASE_STATUS_CHANGED" }) }
    }

    @Test
    fun `illegal transition throws and writes nothing`() {
        val case = newCase()

        assertThrows<InvalidCaseTransitionException> {
            service.transition(case, CaseStatus.COMPLETED)
        }
        verify(exactly = 0) { caseRepo.save(any<SchoolCase>()) }
        verify(exactly = 0) { statusRepo.save(any()) }
    }

    @Test
    fun `entering COMPLETED stamps closedAt`() {
        val case = newCase(CaseStatus.WAITING_FOR_PARENT)
        service.transition(case, CaseStatus.COMPLETED)

        assertThat(case.closedAt).isEqualTo(fixedInstant)
    }

    @Test
    fun `attachSource on a COMPLETED case reopens it and logs a REOPEN change`() {
        val case = newCase(CaseStatus.COMPLETED)
        val source = Source(
            sourceType = SourceType.PORTAL_LETTER,
            externalId = "brief-4711",
            dedupKey = DedupKeys.source(SourceType.PORTAL_LETTER, "brief-4711"),
            contentHash = "abc",
        )

        val result = service.attachSource(case, source)

        assertThat(result).isEqualTo(AttachResult.ATTACHED_AND_REOPENED)
        assertThat(case.status).isEqualTo(CaseStatus.INVESTIGATING)
        assertThat(source.caseId).isEqualTo(case.id)
        verify(exactly = 1) {
            changeRepo.save(match { it.changeType.name == "REOPEN" })
        }
    }

    @Test
    fun `reopening resets investigation state from the previous case life`() {
        val case = newCase(CaseStatus.COMPLETED).apply {
            investigationComplete = true
            investigationNotes = "old notes"
        }
        val source = Source(
            sourceType = SourceType.EMAIL,
            externalId = "msg-reopen",
            dedupKey = DedupKeys.source(SourceType.EMAIL, "msg-reopen"),
            contentHash = "abc",
        )

        service.attachSource(case, source)

        assertThat(case.investigationComplete).isFalse()
        assertThat(case.investigationNotes).isNull()
    }

    @Test
    fun `attachSource refuses to steal a source from another case`() {
        val caseA = newCase(CaseStatus.INVESTIGATING)
        val caseB = newCase(CaseStatus.INVESTIGATING)
        val source = Source(
            sourceType = SourceType.EMAIL,
            externalId = "msg-owned",
            dedupKey = DedupKeys.source(SourceType.EMAIL, "msg-owned"),
            contentHash = "abc",
        )
        source.caseId = caseA.id

        val thrown = org.assertj.core.api.Assertions.catchThrowable { service.attachSource(caseB, source) }

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(source.caseId).isEqualTo(caseA.id)
        verify(exactly = 0) { sourceRepo.save(any()) }
    }

    @Test
    fun `attachSource is idempotent for an already attached source`() {
        val case = newCase(CaseStatus.INVESTIGATING)
        val source = Source(
            sourceType = SourceType.EMAIL,
            externalId = "msg-1",
            dedupKey = DedupKeys.source(SourceType.EMAIL, "msg-1"),
            contentHash = "abc",
        )
        source.caseId = case.id

        val result = service.attachSource(case, source)

        assertThat(result).isEqualTo(AttachResult.ALREADY_ATTACHED)
        verify(exactly = 0) { sourceRepo.save(any()) }
        verify(exactly = 0) { auditRepo.save(any()) }
    }

    @Test
    fun `reopen on FAILED retries into investigation`() {
        val case = newCase(CaseStatus.FAILED)
        every { caseRepo.findById(case.id) } returns java.util.Optional.of(case)

        service.reopen(case.id, reason = "retry after transient failure")

        assertThat(case.status).isEqualTo(CaseStatus.INVESTIGATING)
    }

    @Test
    fun `dedup keys are stable and normalize input`() {
        val a = DedupKeys.source(SourceType.EMAIL, " MSG-1 ")
        val b = DedupKeys.source(SourceType.EMAIL, "msg-1")
        assertThat(a).isEqualTo(b)

        val c = DedupKeys.action(UUID.fromString("00000000-0000-0000-0000-000000000009"), "Fill   Out   The FORM")
        val d = DedupKeys.action(UUID.fromString("00000000-0000-0000-0000-000000000009"), "fill out the form")
        assertThat(c).isEqualTo(d)

        val other = DedupKeys.action(UUID.fromString("00000000-0000-0000-0000-000000000010"), "fill out the form")
        assertThat(c).isNotEqualTo(other)
    }
}
