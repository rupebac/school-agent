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
 * Projects internal SchoolEvents into the shared "Jamie – Schule" calendar.
 * Upsert by googleEventId — never creates duplicates (spec §22/§38). Errors
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
        val event = attachChild(eventRaw)
        if (event.eventType == de.schoolagent.domain.enums.EventType.SCHOOL_REPORT || event.status == EventStatus.CANCELLED) {
            removeProjection(event)
            return
        }
        try {
            val json = toJson(event)
            val existingGoogleId = event.googleEventId
            if (existingGoogleId == null) {
                event.googleEventId = calendar.insertEvent(calendarId(event), json)
            } else {
                calendar.updateEvent(calendarId(event), existingGoogleId, json)
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
            log.warn("calendar sync failed for event {}: {}", event.id, e.message)
        } finally { (calendar as? de.schoolagent.connectors.calendar.GoogleCalendarRestClient)?.withChild(null) }
    }

    /** Removes the Google projection (event cancelled internally or case deleted). */
    @Transactional
    fun removeProjection(eventRaw: SchoolEvent) {
        val event = attachChild(eventRaw)
        val googleId = event.googleEventId ?: run {
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
            log.warn("calendar delete failed for event {}: {}", event.id, e.message)
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
        node.put("summary", event.title)
        if (event.allDay) {
            node.putObject("start").put("date", zdt(event.startTime).toLocalDate().toString())
            node.putObject("end").put(
                "date",
                (event.endTime?.let { zdt(it) } ?: zdt(event.startTime)).toLocalDate().plusDays(1).toString(),
            )
        } else {
            node.putObject("start").put("dateTime", zdt(event.startTime).toString())
            node.putObject("end").put(
                "dateTime",
                zdt(event.endTime ?: event.startTime.plusSeconds(3600)).toString(),
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
