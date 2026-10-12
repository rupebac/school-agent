package de.schoolagent.health

import de.schoolagent.domain.enums.ConnectorType
import de.schoolagent.domain.enums.HealthStatus
import de.schoolagent.domain.model.AuditEntry
import de.schoolagent.domain.repo.AuditEntryRepository
import de.schoolagent.domain.repo.ConnectorHealthRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Staleness detection (spec §30): "no new mail" is fine, "haven't been able
 * to check for hours" is not. Flips a healthy-looking connector to DEGRADED
 * when its last success is older than its expected cadence.
 */
@Component
class StalenessJob(
    private val repo: ConnectorHealthRepository,
    private val auditRepo: AuditEntryRepository,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val thresholds: Map<ConnectorType, Duration> = mapOf(
        ConnectorType.GMAIL to Duration.ofHours(1),
        ConnectorType.ELTERNPORTAL to Duration.ofHours(2),
        ConnectorType.SCHULMANAGER to Duration.ofHours(2),
        ConnectorType.CALENDAR to Duration.ofHours(25),
        ConnectorType.AI to Duration.ofHours(25),
        ConnectorType.WHATSAPP to Duration.ofHours(25),
        ConnectorType.PLAYWRIGHT to Duration.ofDays(8),
    )

    @Scheduled(cron = "\${schoolagent.staleness-cron:0 */5 * * * *}")
    @Transactional
    fun check() {
        val now = clock.instant()
        for (row in repo.findAll()) {
            val threshold = thresholds[row.connector] ?: continue

            // Auto-recovery: if a connector is DOWN/DEGRADED but its last failure
            // is well past the failure threshold AND there was a recent success
            // (or no failure at all in the last hour), flip back to OK — the
            // "test connection" button or a webhook hit may have cleared it.
            if (row.status in listOf(HealthStatus.DOWN, HealthStatus.DEGRADED)) {
                val lastFailure = row.lastFailureAt
                val lastSuccess = row.lastSuccessAt
                val failureIsOld = lastFailure == null || lastFailure.isBefore(now.minus(Duration.ofHours(1)))
                val hasRecentSuccess = lastSuccess != null && lastSuccess.isAfter(now.minus(Duration.ofHours(1)))
                if (failureIsOld || hasRecentSuccess) {
                    row.status = HealthStatus.OK
                    row.consecutiveFailures = 0
                    row.lastError = null
                    row.updatedAt = now
                    repo.save(row)
                    log.info("connector {} auto-recovered to OK", row.connector)
                    continue
                }
            }

            val last = row.lastSuccessAt ?: continue
            if (row.status == HealthStatus.OK && last.plus(threshold).isBefore(now)) {
                row.status = HealthStatus.DEGRADED
                row.updatedAt = now
                row.lastError = "stale: last success ${Duration.between(last, now).toMinutes()} min ago"
                repo.save(row)
                auditRepo.save(
                    AuditEntry(
                        actor = de.schoolagent.domain.enums.Actor.SYSTEM,
                        action = "CONNECTOR_STALE",
                        detail = """{"connector":"${row.connector}","lastSuccess":"$last"}""",
                        createdAt = now,
                    ),
                )
                log.warn("connector {} stale (last success {})", row.connector, last)
            }
        }
    }
}
