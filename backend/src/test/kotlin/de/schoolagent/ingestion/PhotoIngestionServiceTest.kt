package de.schoolagent.ingestion

import de.schoolagent.domain.enums.CaseStatus
import de.schoolagent.domain.enums.SourceType
import de.schoolagent.domain.model.AppUser
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.model.Source
import de.schoolagent.domain.repo.ChildRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.repo.SourceRepository
import de.schoolagent.domain.service.CaseService
import de.schoolagent.reasoning.CaseReasoningService
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

class PhotoIngestionServiceTest {

    private val documents = mockk<de.schoolagent.documents.DocumentStore>()
    private val caseService = mockk<CaseService>(relaxed = true)
    private val caseRepo = mockk<SchoolCaseRepository>()
    private val sourceRepo = mockk<SourceRepository>(relaxed = true)
    private val kids = mockk<ChildRepository>(relaxed = true)
    private val investigation = mockk<InvestigationService>(relaxed = true)
    private val reasoning = mockk<CaseReasoningService>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var photos: PhotoIngestionService

    private val sender = AppUser(name = "morgan", displayName = "Morgan", passwordHash = "x", role = "ADMIN")
    private val kid = de.schoolagent.domain.model.Child(name = "Jamie")
    private val caseId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

    @BeforeEach
    fun setUp() {
        photos = PhotoIngestionService(documents, caseService, caseRepo, sourceRepo, kids, investigation, reasoning, clock)
        every { documents.put(any()) } returns "abc123hash"
        every { sourceRepo.findByDedupKey(any()) } returns null
        every { kids.findAll() } returns listOf(kid)
        every { caseService.createCase(any(), any(), any(), any(), any()) } returns SchoolCase(id = caseId, title = "Photo slip")
        every { sourceRepo.save(any<Source>()) } answers { firstArg() }
        every { caseService.transition(any<SchoolCase>(), any(), any(), any()) } answers { firstArg() }
    }

    @Test
    fun `photo becomes an ingesting case`() {
        val result = photos.ingest(
            bytes = byteArrayOf(1, 2, 3, 4),
            mimeType = "image/jpeg",
            filename = "slip.jpg",
            caption = "Gym kit Friday",
            sender = sender,
            childId = null,
        )

        assertThat(result.duplicate).isFalse()
        assertThat(result.schoolCase.id).isEqualTo(caseId)
        verify { caseService.createCase(kid.id, "Gym kit Friday", any(), any(), any()) }
        verify { caseService.transition(any<SchoolCase>(), CaseStatus.INGESTING, any(), any()) }
        verify { sourceRepo.save(match<Source> { it.sourceType == SourceType.MANUAL_UPLOAD && it.mimeType == "image/jpeg" }) }
    }

    @Test
    fun `same bytes are not ingested twice`() {
        val existing = Source(
            caseId = caseId,
            sourceType = SourceType.MANUAL_UPLOAD,
            externalId = "photo:abc",
            dedupKey = "x",
            contentHash = "abc123hash",
        )
        every { sourceRepo.findByDedupKey(any()) } returns existing
        every { caseRepo.findById(caseId) } returns Optional.of(SchoolCase(id = caseId, title = "Photo slip"))

        val result = photos.ingest(byteArrayOf(1, 2, 3, 4), "image/jpeg", "slip.jpg", null, sender, null)

        assertThat(result.duplicate).isTrue()
        verify(exactly = 0) { caseService.createCase(any(), any(), any(), any(), any()) }
    }
}
