package de.schoolagent.scheduling

import de.schoolagent.connectors.google.GoogleOAuthService
import de.schoolagent.ingestion.GmailIngestionService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * M10 reliability sweeper (spec §40): periodically drains anything stuck in
 * the pipeline (INGESTING/READY_FOR_REASONING) — after crashes, failed
 * investigations, or AI outages. All steps are idempotent, so this is safe to
 * run unconditionally every 15 minutes.
 */
@Component
class PipelineSweepJob(
    private val investigation: de.schoolagent.ingestion.InvestigationService,
    private val reasoning: de.schoolagent.reasoning.CaseReasoningService,
    private val cases: de.schoolagent.domain.repo.SchoolCaseRepository,
    private val consolidation: de.schoolagent.reasoning.ObligationConsolidationService,
    private val notifications: de.schoolagent.whatsapp.NotificationService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onBoot() {
        runCatching { reasoning.recalibrateStoredScores() }
            .onFailure { log.warn("score recap on boot: {}", it.message) }
    }

    /** Repair older mixed lists in the background, reusing persisted AI decisions. */
    @Scheduled(initialDelay = 60000, fixedDelay = 86400000)
    fun consolidateObligations() {
        cases.findAll().forEach { case ->
            runCatching { consolidation.consolidate(case.id) }
                .onFailure { log.warn("obligation consolidation unavailable for {}", case.id) }
        }
    }

    @Scheduled(cron = "\${schoolagent.sweep-cron:0 */15 * * * *}")
    fun sweep() {
        runCatching {
            investigation.investigateAllPending()
            reasoning.reasonAllPending()
            reasoning.recalibrateStoredScores()
        }.onFailure { log.debug("pipeline sweep: {}", it.message) }
        runCatching { notifications.skipLeftoverDigestQueue() }
            .onFailure { log.debug("digest leftover cleanup: {}", it.message) }
    }
}
