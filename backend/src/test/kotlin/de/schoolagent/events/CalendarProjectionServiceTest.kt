package de.schoolagent.events

import com.fasterxml.jackson.databind.ObjectMapper
import de.schoolagent.connectors.calendar.CalendarApiException
import de.schoolagent.connectors.calendar.GoogleCalendarClient
import de.schoolagent.connectors.google.GoogleProperties
import de.schoolagent.domain.enums.ConnectorType
import de.schoolagent.domain.enums.EventType
import de.schoolagent.domain.enums.GoogleSyncStatus
import de.schoolagent.domain.model.SchoolEvent
import de.schoolagent.domain.repo.SchoolEventRepository
import de.schoolagent.health.HealthTracker
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class CalendarProjectionServiceTest {

    private val eventRepo = mockk<SchoolEventRepository>(relaxed = true)
    private val health = mockk<HealthTracker>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC)
    private val calendarSetup = mockk<de.schoolagent.connectors.google.GoogleCalendarSetupService>()

    private class FakeCalendar : GoogleCalendarClient {
        val inserted = mutableListOf<String>()
        val updated = mutableListOf<Pair<String, String>>()
        val deleted = mutableListOf<String>()
        var failInsert = false
        var failDelete = false

        override fun insertEvent(calendarId: String, eventJson: String): String {
            if (failInsert) throw CalendarApiException("calendar unreachable", authRelated = false)
            inserted.add(eventJson)
            return "google-${inserted.size}"
        }

        override fun updateEvent(calendarId: String, googleEventId: String, eventJson: String) {
            updated.add(googleEventId to eventJson)
        }

        override fun deleteEvent(calendarId: String, googleEventId: String) {
            if (failDelete) throw CalendarApiException("calendar unreachable", authRelated = false)
            deleted.add(googleEventId)
        }
    }

    private lateinit var calendar: FakeCalendar
    private lateinit var service: CalendarProjectionService

    @BeforeEach
    fun setUp() {
        calendar = FakeCalendar()
        every { eventRepo.lockForCalendarProjection(any()) } returns java.util.Optional.empty()
        every { eventRepo.save(any<SchoolEvent>()) } answers { firstArg() }
        every { calendarSetup.resolveCalendarId() } returns "jamie-schule"
        val caseRepoMock = io.mockk.mockk<de.schoolagent.domain.repo.SchoolCaseRepository>()
        io.mockk.every { caseRepoMock.findById(any()) } returns java.util.Optional.empty()
        service = CalendarProjectionService(
            calendar = calendar,
            calendarSetup = calendarSetup,
            caseRepo = caseRepoMock,
            eventRepo = eventRepo,
            health = health,
            clock = clock,
        )
    }

    private fun event(googleId: String? = null) = SchoolEvent(
        caseId = UUID.randomUUID(),
        title = "Schullandheim",
        eventType = EventType.SCHOOL_EVENT,
        startTime = Instant.parse("2026-10-12T06:00:00Z"), // 08:00 Berlin
        endTime = Instant.parse("2026-10-14T13:00:00Z"),
        location = "Jugendhaus Alpenrand",
        googleEventId = googleId,
        dedupKey = "evt-test",
    )

    @Test fun `switching targets inserts into the new calendar without changing the old calendar`() {
        val e = event("old-id").apply { googleCalendarId = "previous-calendar" }
        service.syncEvent(e)
        assertThat(calendar.inserted).hasSize(1)
        assertThat(calendar.updated).isEmpty()
        assertThat(calendar.deleted).isEmpty()
        assertThat(e.googleCalendarId).isEqualTo("jamie-schule")
    }

    @Test fun `same target reconnect keeps the existing event identity`() {
        val e = event("old-id").apply { googleCalendarId = "jamie-schule" }
        service.syncEvent(e)
        assertThat(calendar.inserted).isEmpty()
        assertThat(calendar.updated.single().first).isEqualTo("old-id")
    }

    @Test fun `ambiguous insert retries reuse exactly the same client event id`() {
        val e = event()
        calendar.failInsert = true
        service.syncEvent(e)
        calendar.failInsert = false
        service.syncEvent(e)
        val payload = ObjectMapper().readTree(calendar.inserted.single())
        assertThat(payload.path("id").asText()).isEqualTo("sa" + e.id.toString().replace("-", ""))
        assertThat(payload.path("extendedProperties").path("private").path("schoolAgentEvent").asText()).isEqualTo(e.id.toString())
    }

    @Test
    fun `new event is inserted with Berlin timezone and marked SYNCED`() {
        val e = event()

        service.syncEvent(e)

        assertThat(e.googleEventId).isEqualTo("google-1")
        assertThat(e.googleSyncStatus).isEqualTo(GoogleSyncStatus.SYNCED)
        val json = calendar.inserted.single()
        assertThat(json).contains("\"summary\":\"Schullandheim\"")
        val payload = ObjectMapper().readTree(json)
        assertThat(payload.path("start").path("dateTime").asText()).isEqualTo("2026-10-12T08:00:00+02:00")
        java.time.OffsetDateTime.parse(payload.path("start").path("dateTime").asText())
        java.time.OffsetDateTime.parse(payload.path("end").path("dateTime").asText())
        assertThat(json).contains("+02:00") // Europe/Berlin offset in October 2026
        assertThat(json).contains("Jugendhaus Alpenrand")
        verify(exactly = 1) { health.success(ConnectorType.CALENDAR) }
    }

    @Test
    fun `existing event is updated, never duplicated`() {
        val e = event(googleId = "g-42")

        service.syncEvent(e)

        assertThat(calendar.inserted).isEmpty()
        assertThat(calendar.updated).hasSize(1)
        assertThat(calendar.updated.single().first).isEqualTo("g-42")
        assertThat(e.googleSyncStatus).isEqualTo(GoogleSyncStatus.SYNCED)
    }

    @Test
    fun `calendar failure marks the event ERROR and records health, but does not throw`() {
        calendar.failInsert = true
        val e = event()

        service.syncEvent(e) // must not throw

        assertThat(e.googleEventId).isNull()
        assertThat(e.googleSyncStatus).isEqualTo(GoogleSyncStatus.ERROR)
        verify(exactly = 1) { health.failure(ConnectorType.CALENDAR, any()) }
    }

    @Test
    fun `removeProjection deletes the Google event and detaches the id`() {
        val e = event(googleId = "g-7")

        service.removeProjection(e)

        assertThat(calendar.deleted).containsExactly("g-7")
        assertThat(e.googleEventId).isNull()
        assertThat(e.googleSyncStatus).isEqualTo(GoogleSyncStatus.NOT_APPLICABLE)
    }
    @Test fun `failed removal retains the Google id and records a retryable error`() {
        val e = event("g-7"); calendar.failDelete = true
        service.removeProjection(e)
        assertThat(e.googleEventId).isEqualTo("g-7")
        assertThat(e.googleSyncStatus).isEqualTo(GoogleSyncStatus.ERROR)
        verify { eventRepo.save(e) }
        calendar.failDelete = false
        service.removeProjection(e)
        assertThat(e.googleEventId).isNull()
        assertThat(e.googleSyncStatus).isEqualTo(GoogleSyncStatus.NOT_APPLICABLE)
    }
    @Test fun `historical school reports never create Google appointments even through another caller`() {
        val e = event().apply { eventType = EventType.SCHOOL_REPORT }
        service.syncEvent(e)
        assertThat(calendar.inserted).isEmpty()
        assertThat(e.googleSyncStatus).isEqualTo(GoogleSyncStatus.NOT_APPLICABLE)
    }
    @Test
    fun `backfill rechecks the locked event and never inserts an already synced event twice`() {
        val e = event()
        every { eventRepo.lockForCalendarProjection(e.id) } returns java.util.Optional.of(e)
        assertThat(service.syncPendingEvent(e.id)).isTrue()
        assertThat(service.syncPendingEvent(e.id)).isFalse()
        assertThat(calendar.inserted).hasSize(1)
        assertThat(calendar.updated).isEmpty()
    }

    @Test
    fun `backfill retries a failed insert and excludes cancelled events`() {
        val e = event()
        every { eventRepo.lockForCalendarProjection(e.id) } returns java.util.Optional.of(e)
        calendar.failInsert = true
        assertThat(service.syncPendingEvent(e.id)).isFalse()
        assertThat(e.googleSyncStatus).isEqualTo(GoogleSyncStatus.ERROR)
        calendar.failInsert = false
        assertThat(service.syncPendingEvent(e.id)).isTrue()
        e.googleEventId = null
        e.googleSyncStatus = GoogleSyncStatus.PENDING
        e.status = de.schoolagent.domain.enums.EventStatus.CANCELLED
        assertThat(service.syncPendingEvent(e.id)).isFalse()
        assertThat(calendar.inserted).hasSize(1)
    }

    @Test fun `failed cancellations are retried without inserting a replacement`() {
        val e = event("g-7").apply { status = de.schoolagent.domain.enums.EventStatus.CANCELLED }
        every { eventRepo.lockForCalendarProjection(e.id) } returns java.util.Optional.of(e)
        calendar.failDelete = true
        assertThat(service.syncPendingEvent(e.id)).isFalse()
        calendar.failDelete = false
        assertThat(service.syncPendingEvent(e.id)).isTrue()
        assertThat(calendar.deleted).containsExactly("g-7")
        assertThat(calendar.inserted).isEmpty()
    }

    @Test fun `direct ingestion defers until a dedicated calendar is available`() {
        every { calendarSetup.resolveCalendarId() } returns "primary"
        val e = event()
        service.syncEvent(e)
        assertThat(calendar.inserted).isEmpty()
        assertThat(e.googleSyncStatus).isEqualTo(GoogleSyncStatus.PENDING)
    }

    @Test fun `timetable change notices are removed instead of creating calendar appointments`() {
        val e = event("existing-google-event").apply { eventType = EventType.TIMETABLE_CHANGE; allDay = true }
        service.syncEvent(e)
        assertThat(calendar.inserted).isEmpty()
        assertThat(calendar.updated).isEmpty()
        assertThat(calendar.deleted).containsExactly("existing-google-event")
        assertThat(e.googleEventId).isNull()
        assertThat(e.googleSyncStatus).isEqualTo(GoogleSyncStatus.NOT_APPLICABLE)
        service.syncEvent(e)
        assertThat(calendar.deleted).hasSize(1)
        assertThat(calendar.inserted).isEmpty()
    }
}
