package de.schoolagent.scheduling

import de.schoolagent.connectors.school.SchoolConnectionService
import de.schoolagent.ingestion.SchoolIngestionService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Elternportal polling (spec §39) — independent of Google: runs whenever the
 * portal is configured. Every 30 min by default; repo-id dedup makes each
 * run a no-op unless something actually changed.
 */
@Component
class SchoolPollJob(
    private val ingestion: SchoolIngestionService,
    private val config: SchoolConnectionService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${schoolagent.elternportal.polling-cron:0 */30 * * * *}")
    fun poll() {
        for (kid in config.configuredKids()) {
            try {
                val result = ingestion.syncAndProcess(kid.id)
                log.info("school portal poll: {}", result)
            } catch (e: Exception) {
                log.warn("school portal poll failed: {}", e.javaClass.simpleName)
            }
        }
    }
}
