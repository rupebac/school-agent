package de.schoolagent.scheduling

import de.schoolagent.connectors.google.GoogleOAuthService
import de.schoolagent.connectors.google.GoogleProperties
import de.schoolagent.ingestion.GmailIngestionService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Background polling (spec §39): MVP polling is acceptable; push/webhooks later.
 * Only runs when explicitly enabled AND the OAuth connection exists — a
 * configured-but-disconnected system must not spam connector health.
 */
@Component
class GmailPollJob(
    private val ingestion: GmailIngestionService,
    private val investigation: de.schoolagent.ingestion.InvestigationService,
    private val reasoning: de.schoolagent.reasoning.CaseReasoningService,
    private val oauth: GoogleOAuthService,
    private val props: GoogleProperties,
    private val kids: de.schoolagent.domain.repo.ChildRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${schoolagent.google.polling-cron:0 */10 * * * *}")
    fun poll() {
        if (!props.pollingEnabled) return
        val connected = kids.findAll().map { it.id }.filter { oauth.isConfigured(it) }
        val scopes: List<java.util.UUID?> = connected.ifEmpty { if (oauth.isConfigured()) listOf(null) else emptyList() }
        if (scopes.isEmpty()) return
        try {
            for (kid in scopes) {
                runCatching { ingestion.pollOnce(kid) }
                    .onFailure { log.warn("Gmail poll failed for {}: {}", kid, it.javaClass.simpleName) }
            }
            val investigated = investigation.investigateAllPending()
            val reasoned = reasoning.reasonAllPending()
            log.info("pipeline: {} cases investigated, {} reasoned", investigated, reasoned)
        } catch (e: Exception) {
            log.warn("scheduled gmail poll failed: {}", e.javaClass.simpleName)
        }
    }
}
