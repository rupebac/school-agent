package de.schoolagent.scheduling

import de.schoolagent.connectors.google.GoogleCalendarSetupService
import de.schoolagent.connectors.google.GoogleOAuthService
import de.schoolagent.diagnostics.*
import de.schoolagent.domain.repo.ChildRepository
import de.schoolagent.domain.repo.SchoolEventRepository
import de.schoolagent.events.CalendarProjectionService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Imports the existing agenda after consent, and retries failures without reprocessing school feeds. */
@Component
class CalendarSyncJob(
    private val kids: ChildRepository,
    private val oauth: GoogleOAuthService,
    private val setup: GoogleCalendarSetupService,
    private val events: SchoolEventRepository,
    private val projection: CalendarProjectionService,
    private val clock: Clock,
    private val diagnostics: IntegrationDiagnostics,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${schoolagent.google.calendar-sync-delay-ms:60000}", initialDelayString = "\${schoolagent.google.calendar-sync-initial-delay-ms:15000}")
    fun sync() {
        val today = LocalDate.now(clock.withZone(ZoneId.of("Europe/Berlin"))).atStartOfDay(ZoneId.of("Europe/Berlin")).toInstant()
        for (kid in kids.findAll()) {
            try {
                if (!oauth.isConfigured(kid.id) || setup.resolveCalendarId(kid.id) == "primary") continue
                val pending = events.findPendingCalendarProjections(kid.id, today, clock.instant().minusSeconds(300), PageRequest.of(0, 50))
                if (pending.isEmpty()) continue
                val run = UUID.randomUUID()
                diagnostics.record(DiagnosticTrace(DiagnosticStage.CALENDAR, DiagnosticOutcome.STARTED, run = run, childId = kid.id, count = pending.size))
                var synced = 0
                var failed = 0
                for (id in pending) {
                    try {
                        if (projection.syncPendingEvent(id)) synced++ else failed++
                    } catch (e: Exception) {
                        failed++
                        log.warn("Calendar backfill attempt failed ({})", e.javaClass.simpleName)
                    }
                }
                diagnostics.record(DiagnosticTrace(DiagnosticStage.CALENDAR, DiagnosticOutcome.COMPLETED, run = run, childId = kid.id,
                    count = pending.size, changed = synced, failed = failed))
            } catch (e: Exception) {
                diagnostics.record(DiagnosticTrace(DiagnosticStage.CALENDAR, DiagnosticOutcome.FAILED, childId = kid.id))
                log.warn("Calendar backfill will retry ({})", e.javaClass.simpleName)
            }
        }
    }
}
