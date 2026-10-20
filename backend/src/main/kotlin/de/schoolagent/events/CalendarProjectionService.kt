package de.schoolagent.events

import com.fasterxml.jackson.databind.ObjectMapper
import de.schoolagent.connectors.calendar.CalendarApiException
import de.schoolagent.connectors.calendar.GoogleCalendarClient
import de.schoolagent.connectors.google.GoogleCalendarSetupService
import de.schoolagent.domain.enums.ConnectorType
import de.schoolagent.domain.enums.EventStatus
import de.schoolagent.domain.enums.GoogleSyncStatus
import de.schoolagent.domain.model.SchoolEvent
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.repo.SchoolEventRepository
import de.schoolagent.health.HealthTracker
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZoneId

/**
 * Projects internal SchoolEvents into the child's dedicated school calendar.
 * Updates use the stored googleEventId; row locks serialize local inserts. An
 * ambiguous remote inserts are reconciled using stable client event IDs. Errors
 * are recorded on the event (googleSyncStatus=ERROR) and in connector health;
 * they never break case processing.
 */
@Service
class CalendarProjectionService(
    private val calendar: GoogleCalendarClient,
    private val calendarSetup: GoogleCalendarSetupService,
    private val eventRepo: SchoolEventRepository,
    private val caseRepo: SchoolCaseRepository,
    private val health: HealthTracker,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

    private fun zdt(instant: java.time.Instant) = instant.atZone(berlin)

    /** The effective school calendar for the event's kid (stored choice over env property). */
    private fun calendarId(event: SchoolEvent): String {
        (calendar as? de.schoolagent.connectors.calendar.GoogleCalendarRestClient)?.withChild(event.childId)
        return calendarSetup.resolveCalendarId(event.childId)
    }

    @Transactional
    fun syncEvent(eventRaw: SchoolEvent) {
        val event = attachChild(eventRepo.lockForCalendarProjection(eventRaw.id).orElse(eventRaw))
        if (event.eventType == de.schoolagent.domain.enums.EventType.SCHOOL_REPORT || event.status == EventStatus.CANCELLED) {
            removeProjection(event)
            return
        }
        // Consent may finish before dedicated calendar creation; defer instead of leaking into primary.
        val target = calendarSetup.resolveCalendarId(event.childId)
        if (target == "primary") return
        if (event.googleCalendarId != null && event.googleCalendarId != target) event.googleEventId = null
        event.googleCalendarId = target
        try {
            val json = toJson(event)
            val existingGoogleId = event.googleEventId
            if (existingGoogleId == null) {
                event.googleEventId = calendar.insertEvent(calendarId(event), json)
            } else {
                try { calendar.updateEvent(calendarId(event), existingGoogleId, json) }
                catch (e: CalendarApiException) {
                    if (e.statusCode !in setOf(404, 410)) throw e
                    event.googleEventId = calendar.insertEvent(calendarId(event), json)
                }
            }
            event.googleSyncStatus = GoogleSyncStatus.SYNCED
            event.updatedAt = clock.instant()
            eventRepo.save(event)
            health.success(ConnectorType.CALENDAR)
        } catch (e: CalendarApiException) {
            event.googleSyncStatus = GoogleSyncStatus.ERROR
            event.updatedAt = clock.instant()
            eventRepo.save(event)
            health.failure(ConnectorType.CALENDAR, e.message ?: "calendar failure")
            log.warn("Calendar projection failed; retry status saved ({})", e.javaClass.simpleName)
        } finally { (calendar as? de.schoolagent.connectors.calendar.GoogleCalendarRestClient)?.withChild(null) }
    }

    /** Recheck under the same row lock used by ingestion, avoiding a second insert on racing work. */
    @Transactional
    fun syncPendingEvent(id: java.util.UUID): Boolean {
        val event = eventRepo.lockForCalendarProjection(id).orElse(null) ?: return false
        if (event.googleSyncStatus !in setOf(GoogleSyncStatus.PENDING, GoogleSyncStatus.ERROR)) return false
        if (event.status == EventStatus.CANCELLED || event.eventType == de.schoolagent.domain.enums.EventType.SCHOOL_REPORT) {
            if (event.googleEventId == null) return false
            removeProjection(event)
            return event.googleSyncStatus == GoogleSyncStatus.NOT_APPLICABLE
        }
        syncEvent(event)
        return event.googleSyncStatus == GoogleSyncStatus.SYNCED
    }

    /** Removes the Google projection (event cancelled internally or case deleted). */
    @Transactional
    fun removeProjection(eventRaw: SchoolEvent) {
        val event = attachChild(eventRepo.lockForCalendarProjection(eventRaw.id).orElse(eventRaw))
        val googleId = event.googleEventId ?: run {
            event.googleSyncStatus = GoogleSyncStatus.NOT_APPLICABLE
            eventRepo.save(event)
            return
        }
        val target = calendarSetup.resolveCalendarId(event.childId)
        if (target == "primary") return
        if (event.googleCalendarId != null && event.googleCalendarId != target) {
            // A disconnected account is retained as history; never delete through different credentials.
            event.googleEventId = null
            event.googleSyncStatus = GoogleSyncStatus.NOT_APPLICABLE
            eventRepo.save(event)
            return
        }
        try {
            calendar.deleteEvent(calendarId(event), googleId)
            event.googleEventId = null
            event.googleSyncStatus = GoogleSyncStatus.NOT_APPLICABLE
            event.updatedAt = clock.instant()
            eventRepo.save(event)
            health.success(ConnectorType.CALENDAR)
        } catch (e: CalendarApiException) {
            event.googleSyncStatus = GoogleSyncStatus.ERROR
            event.updatedAt = clock.instant()
            eventRepo.save(event)
            health.failure(ConnectorType.CALENDAR, e.message ?: "calendar failure")
            log.warn("Calendar removal failed; retry status saved ({})", e.javaClass.simpleName)
        } finally { (calendar as? de.schoolagent.connectors.calendar.GoogleCalendarRestClient)?.withChild(null) }
    }

    /** Event rows carry only caseId — resolve the kid once per projection. */
    private fun attachChild(event: SchoolEvent): SchoolEvent {
        if (event.childId == null) {
            event.childId = caseRepo.findById(event.caseId).orElse(null)?.childId
        }
        return event
    }

    private fun toJson(event: SchoolEvent): String {
        val node = mapper.createObjectNode()
        node.put("id", "sa" + event.id.toString().replace("-", ""))
        node.putObject("extendedProperties").putObject("private").put("schoolAgentEvent", event.id.toString())
        node.put("summary", event.title)
        if (event.allDay) {
            node.putObject("start").put("date", zdt(event.startTime).toLocalDate().toString())
            node.putObject("end").put(
                "date",
                (event.endTime?.let { zdt(it) } ?: zdt(event.startTime)).toLocalDate().plusDays(1).toString(),
            )
        } else {
            node.putObject("start").put("dateTime", java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zdt(event.startTime)))
            node.putObject("end").put(
                "dateTime",
                java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zdt(event.endTime ?: event.startTime.plusSeconds(3600))),
            )
        }
        if (event.location != null) node.put("location", event.location)
        node.put(
            "status",
            when (event.status) {
                EventStatus.CANCELLED -> "cancelled"
                else -> "confirmed"
            },
        )
        return node.toString()
    }
}
