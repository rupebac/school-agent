package de.schoolagent.ingestion

import de.schoolagent.ai.JsonCompletions
import de.schoolagent.common.DedupKeys
import de.schoolagent.crawler.HttpCrawler
import de.schoolagent.crawler.Links
import de.schoolagent.documents.DocumentStore
import de.schoolagent.documents.TextExtractor
import de.schoolagent.domain.enums.Actor
import de.schoolagent.domain.enums.CaseStatus
import de.schoolagent.domain.enums.EvidenceKind
import de.schoolagent.domain.enums.FetchStatus
import de.schoolagent.domain.enums.InvestigationStatus
import de.schoolagent.domain.enums.SourceType
import de.schoolagent.domain.enums.StepOutcome
import de.schoolagent.domain.enums.StepType
import de.schoolagent.domain.model.Evidence
import de.schoolagent.domain.model.Investigation
import de.schoolagent.domain.model.InvestigationStep
import de.schoolagent.domain.model.Source
import de.schoolagent.domain.repo.EvidenceRepository
import de.schoolagent.domain.repo.InvestigationRepository
import de.schoolagent.domain.repo.InvestigationStepRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.repo.SourceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Investigation (spec §9/§10/§16/§17): parse stored sources, crawl relevant
 * links, record deterministic dates/costs, then a cheap EXTRACTION pass
 * distills English facts for reasoning.
 */
@Service
class InvestigationService(
    private val crawler: HttpCrawler,
    private val documents: DocumentStore,
    private val caseRepo: SchoolCaseRepository,
    private val caseService: de.schoolagent.domain.service.CaseService,
    private val sourceRepo: SourceRepository,
    private val evidenceRepo: EvidenceRepository,
    private val investigationRepo: InvestigationRepository,
    private val stepRepo: InvestigationStepRepository,
    private val router: de.schoolagent.ai.ModelRouter,
    private val extraction: EvidenceExtractionService,
    private val worker: de.schoolagent.crawler.BrowserWorkerClient,
    private val formRepo: de.schoolagent.domain.repo.ExternalFormRepository,
    private val clock: Clock,
    private val transactions: org.springframework.transaction.support.TransactionTemplate? = null,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val VISION_PROMPT = """
        These are page images of a German school letter (Elternbrief).
        Transcribe what you can read, then extract. Answer ONLY as JSON:
        {"textSummary":"2-4 English sentences: what this is, when, who must do what",
         "facts":[{"kind":"FACT|DEADLINE|OBLIGATION_PARENT|OBLIGATION_CHILD|COST|CONSENT|LINK|SCHEDULE|CANCELLATION|REQUIREMENT","subject":"short English fact"}]}
        Dates as dd.MM.yyyy, times as HH:mm, money with €. Keep German proper nouns.
        Do not invent unread text.
    """.trimIndent()

    data class InvestigationResult(val status: InvestigationStatus, val pagesVisited: Int, val notes: String?)

    @Transactional
    fun investigate(caseId: UUID): InvestigationResult {
        val schoolCase = caseRepo.findById(caseId).orElse(null)
            ?: throw IllegalArgumentException("case $caseId not found")
        val investigation = investigationRepo.save(
            Investigation(
                caseId = caseId,
                status = InvestigationStatus.RUNNING,
                startedAt = clock.instant(),
            ),
        )
        var pages = 0
        val notes = mutableListOf<String>()

        val sources = sourceRepo.findByCaseId(caseId).filter { it.fetchStatus == FetchStatus.FETCHED }
        val rootSources = sources.filter { it.sourceType !in setOf(SourceType.WEBPAGE, SourceType.WEBPAGE_JS) }
        val generation = DedupKeys.sha256(rootSources.map { "${it.id}:${it.contentHash}" }.sorted().joinToString(","))
        val linkOrigins = mutableMapOf<String, MutableSet<String>>()
        val candidateLinks = mutableSetOf<String>()

        for (source in sources) {
            val bytes = source.storageRef?.let { documents.get(it) } ?: continue
            val mime = source.mimeType
            if (mime?.startsWith("image/", ignoreCase = true) == true) {
                visionExtractFromImages(caseId, source.id, listOf(TextExtractor.asPng(bytes)), investigation.id)
                continue
            }
            val extracted = TextExtractor.byMimeType(bytes, mime) ?: continue
            recordStep(investigation.id, StepType.PARSE_DOCUMENT, source.storageRef ?: source.id.toString(), StepOutcome.OK)
            val sourceLinks = extracted.links.mapNotNull(Links::canonicalize)
            candidateLinks += sourceLinks
            val origins = source.derivedFromSources?.split(",")?.filter(String::isNotBlank) ?: listOf(source.id.toString())
            sourceLinks.forEach { linkOrigins.getOrPut(it) { mutableSetOf() }.addAll(origins) }
            recordFacts(caseId, source.id, extracted.text)
            extraction.extract(caseId, source.id, extracted.text)

            // scanned PDFs have no text layer — let the vision model read the pages (spec §13)
            val isPdf = mime?.contains("pdf", ignoreCase = true) == true
            if (isPdf && extracted.text.trim().length < MIN_TEXT_FOR_PDF) {
                visionExtractFromImages(caseId, source.id, TextExtractor.renderPdfPages(bytes, maxPages = 3), investigation.id)
            }
        }

        val toCrawl = candidateLinks
            .filter { Links.isRelevant("", it) }
            .filter { url -> sources.none { it.canonicalUrl == url && it.sourceType in setOf(SourceType.WEBPAGE, SourceType.WEBPAGE_JS) } }
            .take(MAX_PAGES_PER_CASE)

        for (url in toCrawl) {
            try {
                val page = crawler.fetch(url)
                pages++
                val canonical = Links.canonicalize(page.finalUrl) ?: page.finalUrl
                val hash = documents.put(page.bytes)
                val webSource = sourceRepo.save(
                    Source(
                        caseId = caseId,
                        sourceType = SourceType.WEBPAGE,
                        externalId = canonical,
                        dedupKey = DedupKeys.source(SourceType.WEBPAGE, "$caseId:$canonical:$generation"),
                        derivedFromSources = linkOrigins[url]?.sorted()?.joinToString(","),
                        contentHash = hash,
                        title = page.finalUrl,
                        canonicalUrl = canonical,
                        storageRef = hash,
                        mimeType = page.contentType?.substringBefore(';'),
                        byteSize = page.bytes.size.toLong(),
                        fetchStatus = FetchStatus.FETCHED,
                        createdAt = clock.instant(),
                        updatedAt = clock.instant(),
                    ),
                )
                recordStep(investigation.id, StepType.FETCH_URL, canonical, StepOutcome.OK)
                if (page.isHtml) {
                    recordForms(caseId, page.bytes, page.finalUrl)
                    val pageDoc = TextExtractor.byMimeType(page.bytes, page.contentType)
                    if (pageDoc != null) {
                        recordFacts(caseId, webSource.id, pageDoc.text)
                        extraction.extract(caseId, webSource.id, pageDoc.text)
                    }
                }
            } catch (e: Exception) {
                // JS-only page? try the read-only browser worker (spec §11)
                val rendered = if (e is de.schoolagent.crawler.BlockedCrawlException) null
                    else runCatching { worker.render(url) }.getOrNull()
                if (rendered != null) {
                    pages++
                    val canonical = Links.canonicalize(rendered.finalUrl) ?: rendered.finalUrl
                    val stored = documents.put(rendered.text.toByteArray(Charsets.UTF_8))
                    val jsSource = sourceRepo.save(
                        Source(
                            caseId = caseId,
                            sourceType = SourceType.WEBPAGE_JS,
                            externalId = canonical,
                            dedupKey = DedupKeys.source(SourceType.WEBPAGE_JS, "$caseId:$canonical:$generation"),
                            derivedFromSources = linkOrigins[url]?.sorted()?.joinToString(","),
                            contentHash = stored,
                            title = rendered.title ?: canonical,
                            canonicalUrl = canonical,
                            storageRef = stored,
                            mimeType = "text/plain",
                            fetchStatus = FetchStatus.FETCHED,
                            createdAt = clock.instant(),
                            updatedAt = clock.instant(),
                        ),
                    )
                    recordStep(investigation.id, StepType.BROWSER_RENDER, canonical, StepOutcome.OK)
                    recordFacts(caseId, jsSource.id, rendered.text)
                    extraction.extract(caseId, jsSource.id, rendered.text)
                } else {
                    recordStep(investigation.id, StepType.FETCH_URL, url, StepOutcome.FAILED)
                    notes += "could not fetch $url: ${e.message}"
                    log.debug("external crawl failed: {}", e.javaClass.simpleName)
                }
            }
        }

        val blocked = candidateLinks.count { Links.isRelevant("", it) }
        if (toCrawl.size < blocked && pages == 0) notes += "page budget reached"

        val status = if (notes.isEmpty() || pages > 0 || candidateLinks.isEmpty()) {
            InvestigationStatus.COMPLETE
        } else {
            InvestigationStatus.INCOMPLETE
        }
        investigation.status = status
        investigation.finishedAt = clock.instant()
        investigation.pagesVisited = pages
        investigation.notes = notes.joinToString("; ").takeIf { it.isNotBlank() }
        investigationRepo.save(investigation)

        schoolCase.investigationComplete = status == InvestigationStatus.COMPLETE
        schoolCase.investigationNotes = investigation.notes
        schoolCase.updatedAt = clock.instant()
        caseRepo.save(schoolCase)
        if (schoolCase.status in listOf(CaseStatus.INGESTING, CaseStatus.INVESTIGATING)) {
            caseService.transition(schoolCase, CaseStatus.READY_FOR_REASONING, Actor.SYSTEM, "investigation finished (${status.name})")
        }

        return InvestigationResult(status, pages, investigation.notes)
    }

    @Synchronized
    fun investigateAllPending(): Int {
        val states = listOf(CaseStatus.INGESTING, CaseStatus.INVESTIGATING)
        val pending = caseRepo.findByStatusIn(states)
        var investigated = 0
        for (c in pending) {
            runCatching {
                if (transactions == null) { investigate(c.id); true } else transactions.execute {
                    val current = caseRepo.lockById(c.id)
                    if (current == null || current.status !in states) false
                    else { investigate(c.id); true }
                } == true
            }.onSuccess { if (it) investigated++ }
                .onFailure { log.warn("investigate failed for case {}: {}", c.id, it.javaClass.simpleName) }
        }
        return investigated
    }

    /** Deterministic fact extraction: German dates and euro amounts (feeds M5). */
    private fun recordFacts(caseId: UUID, sourceId: UUID, text: String) {
        val dates = Regex("\\b(0?[1-9]|[12]\\d|3[01])\\.(0?[1-9]|1[0-2])\\.(20\\d{2})\\b")
            .findAll(text)
            .map { it.value }
            .distinct()
            .take(8)
        for (date in dates) {
            saveFact(caseId, sourceId, EvidenceKind.FACT, "Datum: $date", """{"date":"$date"}""")
        }
        val euros = Regex(
            """(?i)(?:€\s*)(\d{1,5}(?:[.,]\d{1,2})?)|(\d{1,5}(?:[.,]\d{1,2})?)\s*(?:€|EUR|Euro)\b""",
        ).findAll(text).mapNotNull { m ->
            m.groupValues[1].ifBlank { m.groupValues[2] }.takeIf { it.isNotBlank() }
        }.distinct().take(6)
        for (amount in euros) {
            saveFact(caseId, sourceId, EvidenceKind.COST, "Kosten: $amount €", """{"amount":"$amount"}""")
        }
    }

    private fun saveFact(caseId: UUID, sourceId: UUID, kind: EvidenceKind, subject: String, payload: String? = null) {
        val key = DedupKeys.evidence(sourceId, kind, subject)
        if (evidenceRepo.existsByDedupKey(key)) return
        evidenceRepo.save(
            Evidence(
                caseId = caseId,
                sourceId = sourceId,
                kind = kind,
                subject = subject,
                payload = payload,
                extractedBy = "deterministic",
                dedupKey = key,
                createdAt = clock.instant(),
            ),
        )
    }

    /** Detects <form> elements on crawled pages → ExternalForm inventory (spec §12: inspect, never submit). */
    private fun recordForms(caseId: java.util.UUID, html: ByteArray, pageUrl: String) {
        try {
            val doc = org.jsoup.Jsoup.parse(String(html, Charsets.UTF_8), pageUrl)
            for (form in doc.select("form")) {
                val action = form.absUrl("action").ifBlank { form.attr("action") }
                if (action.isBlank()) continue
                val existing = formRepo.findByUrlAndCaseId(action, caseId)
                if (existing != null) {
                    existing.pageUrl = pageUrl; existing.lastInspectedAt = clock.instant(); existing.updatedAt = clock.instant()
                    formRepo.save(existing)
                    continue
                }
                val fields = form.select("input,select,textarea").map { el ->
                    mapOf(
                        "tag" to el.tagName(),
                        "type" to el.attr("type").ifBlank { el.tagName() },
                        "name" to el.attr("name"),
                        "label" to (el.attr("aria-label").ifBlank { el.attr("placeholder") }),
                        "required" to el.hasAttr("required"),
                    )
                }
                formRepo.save(
                    de.schoolagent.domain.model.ExternalForm(
                        caseId = caseId,
                        url = action,
                        pageUrl = pageUrl,
                        fieldDescriptions = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(fields),
                        accessStatus = de.schoolagent.domain.enums.FormAccessStatus.ACCESSIBLE,
                        lastInspectedAt = clock.instant(),
                        createdAt = clock.instant(),
                        updatedAt = clock.instant(),
                    ),
                )
            }
        } catch (e: Exception) {
            log.debug("form detection failed: {}", e.javaClass.simpleName)
        }
    }

    private fun recordStep(investigationId: UUID, type: StepType, target: String, outcome: StepOutcome) {
        stepRepo.save(
            InvestigationStep(
                investigationId = investigationId,
                stepType = type,
                target = target.take(500),
                outcome = outcome,
                createdAt = clock.instant(),
            ),
        )
    }

    /** Vision fallback for scanned letters and paper photos (spec §13 vision role). */
    private fun visionExtractFromImages(
        caseId: java.util.UUID,
        sourceId: java.util.UUID,
        imagesPng: List<ByteArray>,
        investigationId: java.util.UUID,
    ) {
        if (imagesPng.isEmpty()) return
        if (!router.available(de.schoolagent.domain.enums.ModelRole.VISION)) {
            log.debug("vision model unavailable — image/pdf left without text evidence")
            return
        }
        try {
            val raw = router.completeWithImages(
                de.schoolagent.domain.enums.ModelRole.VISION,
                VISION_PROMPT,
                "Extract the content of this school letter or handwritten slip.",
                imagesPng,
            )
            val parsed = JsonCompletions.tree(raw)
            val summary = parsed.path("textSummary").asText("").take(600)
            if (summary.isNotBlank()) {
                recordEvidence(caseId, sourceId, EvidenceKind.FACT, "Summary: $summary")
            }
            parsed.path("facts").take(15).forEach { node ->
                val subject = when {
                    node.isTextual -> node.asText()
                    else -> node.path("subject").asText("")
                }.trim()
                if (subject.isBlank()) return@forEach
                val kind = if (node.isObject) {
                    runCatching { EvidenceKind.valueOf(node.path("kind").asText("FACT").uppercase()) }
                        .getOrDefault(EvidenceKind.FACT)
                } else {
                    EvidenceKind.FACT
                }
                recordEvidence(caseId, sourceId, kind, subject.take(240))
            }
            recordStep(investigationId, StepType.EXTRACT, "vision:${sourceId}", StepOutcome.OK)
        } catch (e: Exception) {
            log.warn("vision extraction failed for source {}: {}", sourceId, e.javaClass.simpleName)
            recordStep(investigationId, StepType.EXTRACT, "vision:${sourceId}", StepOutcome.FAILED)
        }
    }

    private fun recordEvidence(
        caseId: java.util.UUID,
        sourceId: java.util.UUID,
        kind: EvidenceKind,
        subject: String,
    ) {
        val key = DedupKeys.evidence(sourceId, kind, subject)
        if (evidenceRepo.existsByDedupKey(key)) return
        evidenceRepo.save(
            Evidence(
                caseId = caseId,
                sourceId = sourceId,
                kind = kind,
                subject = subject,
                extractedBy = "vision",
                dedupKey = key,
                createdAt = clock.instant(),
            ),
        )
    }

    companion object {
        const val MAX_PAGES_PER_CASE = 5
        const val MIN_TEXT_FOR_PDF = 50
    }
}
