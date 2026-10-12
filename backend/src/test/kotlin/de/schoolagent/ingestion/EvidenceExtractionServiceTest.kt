package de.schoolagent.ingestion

import de.schoolagent.ai.ModelRouter
import de.schoolagent.domain.enums.EvidenceKind
import de.schoolagent.domain.enums.ModelRole
import de.schoolagent.domain.model.Evidence
import de.schoolagent.domain.repo.EvidenceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class EvidenceExtractionServiceTest {

    @Test
    fun `stores typed English facts from the extraction model`() {
        val evidenceRepo = mockk<EvidenceRepository>()
        val saved = mutableListOf<Evidence>()
        every { evidenceRepo.existsByDedupKey(any()) } returns false
        every { evidenceRepo.save(any<Evidence>()) } answers {
            firstArg<Evidence>().also { saved.add(it) }
        }
        val router = mockk<ModelRouter>()
        every { router.available(ModelRole.TEXT) } returns true
        every { router.complete(ModelRole.TEXT, any(), any()) } returns
            """{"facts":[
                {"kind":"DEADLINE","subject":"Confirm participation by 29.09.2026"},
                {"kind":"OBLIGATION_CHILD","subject":"Bring a sleeping bag"},
                {"kind":"COST","subject":"95 €"}
            ]}"""

        EvidenceExtractionService(router, evidenceRepo, Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC))
            .extract(UUID.randomUUID(), UUID.randomUUID(), "Anmeldung bis 29.09.2026. Schlafsack mitbringen. Kosten 95 Euro.")

        assertThat(saved.map { it.kind to it.subject }).containsExactly(
            EvidenceKind.DEADLINE to "Confirm participation by 29.09.2026",
            EvidenceKind.OBLIGATION_CHILD to "Bring a sleeping bag",
            EvidenceKind.COST to "95 €",
        )
        assertThat(saved.map { it.extractedBy }.distinct()).containsExactly("extraction")
    }

    @Test
    fun `skips the model when extraction is unavailable`() {
        val evidenceRepo = mockk<EvidenceRepository>(relaxed = true)
        val router = mockk<ModelRouter>()
        every { router.available(any()) } returns false

        EvidenceExtractionService(router, evidenceRepo, Clock.systemUTC())
            .extract(UUID.randomUUID(), UUID.randomUUID(), "Anmeldung bis 29.09.2026 erforderlich.")

        verify(exactly = 0) { router.complete(any(), any(), any()) }
        verify(exactly = 0) { evidenceRepo.save(any<Evidence>()) }
    }
}
