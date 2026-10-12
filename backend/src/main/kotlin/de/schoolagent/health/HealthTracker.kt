package de.schoolagent.health

import de.schoolagent.domain.enums.ConnectorType
import de.schoolagent.domain.enums.HealthStatus
import de.schoolagent.domain.model.ConnectorHealth
import de.schoolagent.domain.repo.ConnectorHealthRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Connector heartbeat (spec §30): distinguishes "no new messages" from
 * "haven't been able to check". DOWN after [downThreshold] consecutive failures.
 */
@Service
@Transactional
class HealthTracker(
    private val repo: ConnectorHealthRepository,
    private val clock: Clock,
) {

    fun success(connector: ConnectorType) {
        val row = repo.findById(connector).orElseGet { ConnectorHealth(connector = connector) }
        row.status = HealthStatus.OK
        row.lastSuccessAt = clock.instant()
        row.consecutiveFailures = 0
        row.lastError = null
        row.updatedAt = clock.instant()
        repo.save(row)
    }

    fun failure(connector: ConnectorType, error: String) {
        val row = repo.findById(connector).orElseGet { ConnectorHealth(connector = connector) }
        row.consecutiveFailures += 1
        row.lastFailureAt = clock.instant()
        row.lastError = error.take(500)
        row.status = if (row.consecutiveFailures >= DOWN_THRESHOLD) HealthStatus.DOWN else HealthStatus.DEGRADED
        row.updatedAt = clock.instant()
        repo.save(row)
    }

    companion object {
        const val DOWN_THRESHOLD = 3
    }
}
