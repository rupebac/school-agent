package de.schoolagent.ingestion

import de.schoolagent.ai.AiUnavailableException
import de.schoolagent.ai.JsonCompletions
import de.schoolagent.ai.ModelRouter
import de.schoolagent.common.DedupKeys
import de.schoolagent.domain.enums.EvidenceKind
import de.schoolagent.domain.enums.ModelRole
import de.schoolagent.domain.model.Evidence
import de.schoolagent.domain.repo.EvidenceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/**
 * Cheap extraction pass (spec §16): turn a source's text into typed English
 * facts so reasoning does not have to rediscover deadlines, costs and duties
 * from a raw letter.
 */
@Service
class EvidenceExtractionService(
    private val router: ModelRouter,
    private val evidenceRepo: EvidenceRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun extract(caseId: UUID, sourceId: UUID, text: String) {
        val clipped = text.replace(Regex("\\s+"), " ").trim()
        if (clipped.length < MIN_CHARS) return
        if (!router.available(ModelRole.TEXT)) return
        try {
            val raw = router.complete(
                ModelRole.TEXT,
                SYSTEM,
                "School correspondence:\n${clipped.take(MAX_CHARS)}",
            )
            val facts = JsonCompletions.tree(raw).path("facts")
            var stored = 0
            for (node in facts) {
                if (stored >= MAX_FACTS) break
                val subject = when {
                    node.isTextual -> node.asText()
                    else -> node.path("subject").asText("")
                }.trim()
                if (subject.isBlank()) continue
                val kind = if (node.isObject) {
                    runCatching { EvidenceKind.valueOf(node.path("kind").asText("FACT").uppercase()) }
                        .getOrDefault(EvidenceKind.FACT)
                } else {
                    EvidenceKind.FACT
                }
                val key = DedupKeys.evidence(sourceId, kind, subject.take(240))
                if (evidenceRepo.existsByDedupKey(key)) continue
                evidenceRepo.save(
                    Evidence(
                        caseId = caseId,
                        sourceId = sourceId,
                        kind = kind,
                        subject = subject.take(240),
                        extractedBy = "extraction",
                        dedupKey = key,
                        createdAt = clock.instant(),
                    ),
                )
                stored++
            }
            log.debug("extraction stored {} facts for source {}", stored, sourceId)
        } catch (e: AiUnavailableException) {
            log.debug("extraction model unavailable: {}", e.message)
        } catch (e: Exception) {
            log.warn("extraction failed for source {}: {}", sourceId, e.message)
        }
    }

    companion object {
        const val MIN_CHARS = 40
        const val MAX_CHARS = 8_000
        const val MAX_FACTS = 20
        private val SYSTEM = """
You extract structured facts from Bavarian school correspondence (Elternbrief, email, portal page).
Return JSON only:
{"facts":[{"kind":"FACT|DEADLINE|OBLIGATION_PARENT|OBLIGATION_CHILD|COST|CONSENT|LINK|SCHEDULE|CANCELLATION|REQUIREMENT","subject":"short English fact"}]}
Rules:
- Never invent. Skip greetings, signatures, legal footers and empty thanks.
- subject MUST be English; keep German proper nouns (Schullandheim, school/class names).
- Dates as dd.MM.yyyy, times as HH:mm, money with €.
- DEADLINE = a date by which someone must act; COST = amounts; OBLIGATION_PARENT = what parents must do;
  OBLIGATION_CHILD = what the child brings or does; CONSENT = signature/photo/permission;
  LINK = http(s) URLs; SCHEDULE = timetable changes; CANCELLATION = cancelled items.
- Example: "Anmeldung bis 29.09.2026. Schlafsack mitbringen. 95 €."
  → DEADLINE "Confirm participation by 29.09.2026", OBLIGATION_CHILD "Bring a sleeping bag", COST "95 €"
- Max 20 facts. Prefer concrete over vague.
""".trimIndent()
    }
}
