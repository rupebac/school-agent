package de.schoolagent.ingestion

import de.schoolagent.crawler.FetchedPage
import de.schoolagent.crawler.HttpCrawler
import de.schoolagent.documents.DocumentStore
import de.schoolagent.domain.enums.CaseStatus
import de.schoolagent.domain.enums.EvidenceKind
import de.schoolagent.domain.enums.FetchStatus
import de.schoolagent.domain.enums.InvestigationStatus
import de.schoolagent.domain.enums.SourceType
import de.schoolagent.domain.model.Evidence
import de.schoolagent.domain.model.Investigation
import de.schoolagent.domain.model.InvestigationStep
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.model.Source
import de.schoolagent.domain.repo.EvidenceRepository
import de.schoolagent.domain.repo.InvestigationRepository
import de.schoolagent.domain.repo.InvestigationStepRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.repo.SourceRepository
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

class InvestigationServiceTest {

    private val crawler = mockk<HttpCrawler>()
    private val caseRepo = mockk<SchoolCaseRepository>(relaxed = true)
    private val caseService = mockk<CaseService>(relaxed = true)
    private val sourceRepo = mockk<SourceRepository>(relaxed = true)
    private val evidenceRepo = mockk<EvidenceRepository>(relaxed = true)
    private val investigationRepo = mockk<InvestigationRepository>(relaxed = true)
    private val stepRepo = mockk<InvestigationStepRepository>(relaxed = true)
    private val router = mockk<de.schoolagent.ai.ModelRouter>(relaxed = true)
    private val worker = mockk<de.schoolagent.crawler.BrowserWorkerClient>(relaxed = true)
    private val formRepo = mockk<de.schoolagent.domain.repo.ExternalFormRepository>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC)

    private lateinit var service: InvestigationService

    private val storedDocs = mutableMapOf<String, ByteArray>()
    private val knownDedupKeys = mutableSetOf<String>()
    private val savedSources = mutableListOf<Source>()
    private val savedEvidence = mutableListOf<Evidence>()

    private class MapStore : DocumentStore {
        val map = mutableMapOf<String, ByteArray>()
        private var n = 0
        override fun put(content: ByteArray): String = "doc${n++}".also { map[it] = content }
        override fun get(ref: String): ByteArray? = map[ref]
        override fun exists(ref: String): Boolean = map.containsKey(ref)
    }

    private val store = MapStore()

    @BeforeEach
    fun setUp() {
        storedDocs.clear()
        knownDedupKeys.clear()
        savedSources.clear()
        savedEvidence.clear()

        every { caseRepo.findById(any()) } answers {
            Optional.of(SchoolCase(id = firstArg<UUID>(), title = "Schullandheim", status = CaseStatus.INVESTIGATING))
        }
        every { caseRepo.save(any<SchoolCase>()) } answers { firstArg() }
        every { caseRepo.findByStatusIn(any()) } returns emptyList()
        every { sourceRepo.findByCaseId(any()) } answers { savedSources.filter { it.caseId == firstArg<UUID>() }
            .ifEmpty { listOfNotNull(mailSourceFor(firstArg<UUID>())) } }
        every { sourceRepo.save(any<Source>()) } answers {
            val s = firstArg<Source>()
            savedSources.add(s)
            knownDedupKeys.add(s.dedupKey)
            s
        }
        every { sourceRepo.existsByDedupKey(any()) } answers { knownDedupKeys.contains(firstArg<String>()) }
        every { evidenceRepo.existsByDedupKey(any()) } answers { savedEvidence.any { it.dedupKey == firstArg<String>() } }
        every { evidenceRepo.save(any<Evidence>()) } answers {
            val e = firstArg<Evidence>()
            savedEvidence.add(e)
            e
        }
        every { investigationRepo.save(any<Investigation>()) } answers { firstArg() }
        every { stepRepo.save(any<InvestigationStep>()) } answers { firstArg() }

        every { worker.render(any()) } throws de.schoolagent.crawler.CrawlException("worker down")
        service = InvestigationService(
            crawler, store, caseRepo, caseService, sourceRepo, evidenceRepo,
            investigationRepo, stepRepo, router,
            EvidenceExtractionService(router, evidenceRepo, clock),
            worker, formRepo, clock,
        )
    }

    private val caseId = UUID.randomUUID()

    private fun mailSourceFor(caseId: UUID): Source? {
        val ref = storedDocs.keys.singleOrNull() ?: return null
        return Source(
            id = UUID.nameUUIDFromBytes("mail".toByteArray()),
            caseId = caseId,
            sourceType = SourceType.EMAIL,
            externalId = "m1",
            dedupKey = "src:EMAIL:m1",
            contentHash = ref,
            storageRef = ref,
            mimeType = "message/rfc822",
            fetchStatus = FetchStatus.FETCHED,
        )
    }

    @Test
    fun `extracts text, records dates as evidence, crawls relevant links and completes the case`() {
        val mail = """
            From: school@example.org
            Subject: Elternbrief
            Content-Type: text/html; charset=utf-8

            <html><body>
            <p>Anmeldung bis 29.09.2026 erforderlich. Kosten 95 Euro.</p>
            <a href="https://school.example/anmeldung?utm_source=news">Zur Anmeldung</a>
            <a href="https://school.example/impressum">Impressum</a>
            </body></html>
        """.trimIndent().let { it.replace("\n", "\r\n") }
        val ref = store.put(mail.toByteArray())
        storedDocs[ref] = mail.toByteArray()

        val html = """<html><body><p>Registrierung schließt am 30.09.2026.</p></body></html>"""
        every { crawler.fetch("https://school.example/anmeldung") } returns FetchedPage(
            requestedUrl = "https://school.example/anmeldung",
            finalUrl = "https://school.example/anmeldung",
            contentType = "text/html",
            bytes = html.toByteArray(),
        )

        val result = service.investigate(caseId)

        assertThat(result.status).isEqualTo(InvestigationStatus.COMPLETE)
        assertThat(result.pagesVisited).isEqualTo(1)
        // utm stripped, only the Anmeldung link followed (Impressum is not relevant);
        // the EMAIL source pre-exists, so only the new WEBPAGE source is saved
        assertThat(savedSources.map { it.sourceType }).containsExactly(SourceType.WEBPAGE)
        assertThat(savedSources.last().canonicalUrl).isEqualTo("https://school.example/anmeldung")
        // deterministic date + euro facts from mail + crawled page
        assertThat(savedEvidence.map { it.subject }).contains("Datum: 29.09.2026", "Datum: 30.09.2026", "Kosten: 95 €")
        assertThat(caseService).isNotNull
        verify(atLeast = 1) {
            caseService.transition(any<SchoolCase>(), eq(CaseStatus.READY_FOR_REASONING), any(), any())
        }
    }

    @Test
    fun `failed crawls are recorded and make the investigation incomplete`() {
        val mail = """
            From: school@example.org
            Subject: Brief
            Content-Type: text/plain; charset=utf-8

            Infos unter https://school.example/anmeldung — Deadline 02.10.2026.
        """.trimIndent().let { it.replace("\n", "\r\n") }
        val ref = store.put(mail.toByteArray())
        storedDocs[ref] = mail.toByteArray()
        every { crawler.fetch(any()) } throws de.schoolagent.crawler.CrawlException("HTTP 500")

        val result = service.investigate(caseId)

        assertThat(result.status).isEqualTo(InvestigationStatus.INCOMPLETE)
        assertThat(result.notes).contains("could not fetch")
    }

    @Test
    fun `links canonicalize away tracking parameters`() {
        val canonical = de.schoolagent.crawler.Links.canonicalize(
            "https://example.org/projekt?utm_source=mail&fbclid=x#top",
        )
        assertThat(canonical).isEqualTo("https://example.org/projekt")

        assertThat(de.schoolagent.crawler.Links.canonicalize("mailto:x@y.example")).isNull()
        assertThat(de.schoolagent.crawler.Links.isRelevant("Zur Anmeldung", "https://x.example/anmeldung")).isTrue()
        assertThat(de.schoolagent.crawler.Links.isRelevant("Impressum", "https://x.example/impressum")).isFalse()
    }

    @Test fun `discovered forms preserve their containing page instead of navigating to submission handlers`() {
        every { formRepo.findByUrlAndCaseId(any(), any()) } returns null
        every { formRepo.save(any<de.schoolagent.domain.model.ExternalForm>()) } answers { firstArg() }
        val mail = "From: school@example.org\r\nContent-Type: text/html; charset=utf-8\r\n\r\n<a href='https://school.example/anmeldung'>Zur Anmeldung</a>"
        val ref = store.put(mail.toByteArray()); storedDocs[ref] = mail.toByteArray()
        every { crawler.fetch(any()) } returns FetchedPage("https://school.example/anmeldung", "https://school.example/forms/registration", "text/html",
            "<form action='/submit'><input name='name'></form>".toByteArray())
        service.investigate(caseId)
        verify { formRepo.save(match { it.url == "https://school.example/submit" && it.pageUrl == "https://school.example/forms/registration" }) }
    }
}
