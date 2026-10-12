package de.schoolagent.domain

import de.schoolagent.common.DedupKeys
import de.schoolagent.domain.enums.ActionStatus
import de.schoolagent.domain.enums.CaseCategory
import de.schoolagent.domain.enums.CaseStatus
import de.schoolagent.domain.enums.EvidenceKind
import de.schoolagent.domain.enums.EventType
import de.schoolagent.domain.enums.SourceType
import de.schoolagent.domain.model.ChildRequirement
import de.schoolagent.domain.model.Evidence
import de.schoolagent.domain.model.ExternalForm
import de.schoolagent.domain.model.ParentAction
import de.schoolagent.domain.model.SchoolEvent
import de.schoolagent.domain.model.Source
import de.schoolagent.domain.repo.AppUserRepository
import de.schoolagent.domain.repo.ChildRequirementRepository
import de.schoolagent.domain.repo.EvidenceRepository
import de.schoolagent.domain.repo.ParentActionRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.repo.SchoolEventRepository
import de.schoolagent.domain.repo.SourceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.LocalDate

/**
 * Boots the full context against a real PostgreSQL (compose parity):
 * Flyway V1/V2 must apply cleanly and Hibernate `validate` must accept the
 * entity ↔ DDL mapping. Then exercises repository round-trips and the
 * uniqueness guarantees that make ingestion idempotent.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Transactional
class SchemaAndRepositoriesTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17")
    }

    @Autowired lateinit var caseRepo: SchoolCaseRepository
    @Autowired lateinit var sourceRepo: SourceRepository
    @Autowired lateinit var evidenceRepo: EvidenceRepository
    @Autowired lateinit var actionRepo: ParentActionRepository
    @Autowired lateinit var eventRepo: SchoolEventRepository
    @Autowired lateinit var requirementRepo: ChildRequirementRepository
    @Autowired lateinit var userRepo: AppUserRepository
    @Autowired lateinit var formRepo: de.schoolagent.domain.repo.ExternalFormRepository

    @Test
    fun `seeded family exists`() {
        assertThat(userRepo.findByName("morgan")?.displayName).isEqualTo("morgan")
        assertThat(userRepo.findByName("casey")?.displayName).isEqualTo("Casey")
    }

    @Test
    fun `case with sources evidence actions events requirements round-trips`() {
        val now = Instant.now()
        val schoolCase = caseRepo.save(
            de.schoolagent.domain.model.SchoolCase(
                title = "Schullandheim Oktober",
                category = CaseCategory.TRIP,
                status = CaseStatus.ACTION_REQUIRED,
                openedAt = now,
                createdAt = now,
                updatedAt = now,
            ),
        )

        val source = sourceRepo.save(
            Source(
                caseId = schoolCase.id,
                sourceType = SourceType.PORTAL_LETTER,
                externalId = "brief-4711",
                dedupKey = DedupKeys.source(SourceType.PORTAL_LETTER, "brief-4711"),
                contentHash = "hash-1",
                title = "Elternbrief Schullandheim",
                createdAt = now,
                updatedAt = now,
            ),
        )

        evidenceRepo.save(
            Evidence(
                caseId = schoolCase.id,
                sourceId = source.id,
                kind = EvidenceKind.DEADLINE,
                subject = "Anmeldung bis 29.09.2026",
                payload = """{"due":"2026-09-29"}""",
                dedupKey = DedupKeys.evidence(source.id, EvidenceKind.DEADLINE, "Anmeldung bis 29.09.2026"),
                createdAt = now,
            ),
        )

        actionRepo.save(
            ParentAction(
                caseId = schoolCase.id,
                title = "Teilnahme bestätigen",
                status = ActionStatus.OPEN,
                dueAt = Instant.parse("2026-09-29T23:59:59Z"),
                dedupKey = DedupKeys.action(schoolCase.id, "Teilnahme bestätigen"),
                createdAt = now,
                updatedAt = now,
            ),
        )

        eventRepo.save(
            SchoolEvent(
                caseId = schoolCase.id,
                title = "Schullandheim",
                eventType = EventType.SCHOOL_EVENT,
                startTime = Instant.parse("2026-10-12T08:00:00Z"),
                dedupKey = DedupKeys.event(schoolCase.id, EventType.SCHOOL_EVENT, "Schullandheim"),
                createdAt = now,
                updatedAt = now,
            ),
        )

        requirementRepo.save(
            ChildRequirement(
                caseId = schoolCase.id,
                description = "Sportschuhe mitbringen",
                relevantOn = LocalDate.of(2026, 10, 12),
                dedupKey = DedupKeys.requirement(schoolCase.id, "Sportschuhe mitbringen", "2026-10-12"),
                createdAt = now,
                updatedAt = now,
            ),
        )

        val loaded = caseRepo.findById(schoolCase.id).orElseThrow()
        assertThat(loaded.title).isEqualTo("Schullandheim Oktober")
        assertThat(sourceRepo.findByCaseId(loaded.id)).hasSize(1)
        assertThat(evidenceRepo.findByCaseId(loaded.id)).hasSize(1)
        val action = actionRepo.findByCaseId(loaded.id).single()
        assertThat(action.dueAt).isNotNull()
        action.dueAt!!.let { assertThat(it.toString()).startsWith("2026-09-29") }
        assertThat(eventRepo.findByCaseId(loaded.id)).hasSize(1)
        assertThat(requirementRepo.findByCaseId(loaded.id)).hasSize(1)
    }

    @Test
    fun `external form round-trips with access status and field descriptions`() {
        val now = Instant.now()
        val schoolCase = caseRepo.save(
            de.schoolagent.domain.model.SchoolCase(
                title = "Medienprojekt",
                category = CaseCategory.FORM,
                openedAt = now,
                createdAt = now,
                updatedAt = now,
            ),
        )
        formRepo.saveAndFlush(
            ExternalForm(
                caseId = schoolCase.id,
                url = "https://example.org/projekt/anmeldung",
                deadline = Instant.parse("2026-09-30T23:59:59Z"),
                requiresConsent = true,
                fieldDescriptions = """[{"label":"Teilnahme","type":"yes_no"}]""",
                accessStatus = de.schoolagent.domain.enums.FormAccessStatus.ACCESSIBLE,
                lastInspectedAt = now,
                createdAt = now,
                updatedAt = now,
            ),
        )

        val forms = formRepo.findByCaseId(schoolCase.id)
        assertThat(forms).hasSize(1)
        val form = forms.single()
        assertThat(form.requiresConsent).isTrue()
        assertThat(form.accessStatus).isEqualTo(de.schoolagent.domain.enums.FormAccessStatus.ACCESSIBLE)
        assertThat(form.deadline).isEqualTo("2026-09-30T23:59:59Z")
        assertThat(formRepo.findByUrlAndCaseId("https://example.org/projekt/anmeldung", schoolCase.id)).isNotNull
    }

    @Test
    fun `duplicate source dedup key is rejected — ingestion idempotency backbone`() {
        val now = Instant.now()
        val dedupKey = DedupKeys.source(SourceType.EMAIL, "msg-dup")
        val base = Source(
            sourceType = SourceType.EMAIL,
            externalId = "msg-dup",
            dedupKey = dedupKey,
            contentHash = "hash",
            createdAt = now,
            updatedAt = now,
        )
        sourceRepo.saveAndFlush(base)

        val duplicate = Source(
            sourceType = SourceType.EMAIL,
            externalId = "msg-dup",
            dedupKey = dedupKey,
            contentHash = "hash",
            createdAt = now,
            updatedAt = now,
        )
        assertThatThrownBy { sourceRepo.saveAndFlush(duplicate) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `duplicate action dedup key is rejected`() {
        val now = Instant.now()
        val schoolCase = caseRepo.save(
            de.schoolagent.domain.model.SchoolCase(
                title = "Medienprojekt",
                category = CaseCategory.FORM,
                openedAt = now,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val dedupKey = DedupKeys.action(schoolCase.id, "Anmeldung ausfüllen")
        val base = ParentAction(
            caseId = schoolCase.id,
            title = "Anmeldung ausfüllen",
            dedupKey = dedupKey,
            createdAt = now,
            updatedAt = now,
        )
        actionRepo.saveAndFlush(base)

        assertThatThrownBy {
            actionRepo.saveAndFlush(
                ParentAction(
                    caseId = schoolCase.id,
                    title = "Anmeldung ausfüllen",
                    dedupKey = dedupKey,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
