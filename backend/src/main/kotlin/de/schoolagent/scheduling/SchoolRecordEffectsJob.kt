package de.schoolagent.scheduling

import de.schoolagent.ingestion.SchoolRecordIngestionService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Retry native calendar updates/deletions and notification preparation independently of portal availability. */
@Component
class SchoolRecordEffectsJob(private val records: SchoolRecordIngestionService) {
    @Scheduled(cron = "\${schoolagent.native-effects-cron:0 */5 * * * *}")
    fun retry() = records.retryPendingEffects()
}
