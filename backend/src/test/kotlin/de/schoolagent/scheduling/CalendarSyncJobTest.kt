package de.schoolagent.scheduling

import de.schoolagent.connectors.google.GoogleCalendarSetupService
import de.schoolagent.connectors.google.GoogleOAuthService
import de.schoolagent.diagnostics.*
import de.schoolagent.domain.model.Child
import de.schoolagent.domain.repo.ChildRepository
import de.schoolagent.domain.repo.SchoolEventRepository
import de.schoolagent.events.CalendarProjectionService
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class CalendarSyncJobTest {
    private val kids = mockk<ChildRepository>()
    private val oauth = mockk<GoogleOAuthService>()
    private val setup = mockk<GoogleCalendarSetupService>()
    private val events = mockk<SchoolEventRepository>()
    private val projection = mockk<CalendarProjectionService>()
    private val diagnostics = mockk<IntegrationDiagnostics>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC)
    private val child = Child(name = "Example Child")
    private val id = UUID.randomUUID()
    private val job = CalendarSyncJob(kids, oauth, setup, events, projection, clock, diagnostics)

    @BeforeEach
    fun setup() {
        every { kids.findAll() } returns listOf(child)
        every { oauth.isConfigured(child.id) } returns true
        every { setup.resolveCalendarId(child.id) } returns "school-calendar"
        every { events.findPendingCalendarProjections(child.id, any(), any(), any()) } returns listOf(id)
        every { projection.syncPendingEvent(id) } returns true
    }

    @Test
    fun `connected child receives existing pending events with bounded batches and Berlin day boundary`() {
        job.sync()
        verify { events.findPendingCalendarProjections(child.id, Instant.parse("2026-10-19T22:00:00Z"),
            Instant.parse("2026-10-20T11:55:00Z"), match { it.pageSize == 50 }) }
        verify(exactly = 1) { projection.syncPendingEvent(id) }
        verify { diagnostics.record(match { it.stage == DiagnosticStage.CALENDAR && it.outcome == DiagnosticOutcome.COMPLETED && it.changed == 1 && it.failed == 0 }) }
    }

    @Test
    fun `unconnected child does not receive another child's Google events`() {
        every { oauth.isConfigured(child.id) } returns false
        job.sync()
        verify(exactly = 0) { events.findPendingCalendarProjections(any(), any(), any(), any()) }
        verify(exactly = 0) { projection.syncPendingEvent(any()) }
    }

    @Test
    fun `no projection is written into a fallback primary calendar`() {
        every { setup.resolveCalendarId(child.id) } returns "primary"
        job.sync()
        verify(exactly = 0) { projection.syncPendingEvent(any()) }
    }

    @Test
    fun `one failed event does not prevent the rest and records only typed metadata`() {
        val other = UUID.randomUUID()
        every { events.findPendingCalendarProjections(child.id, any(), any(), any()) } returns listOf(id, other)
        every { projection.syncPendingEvent(id) } throws IllegalStateException("synthetic-private-error")
        every { projection.syncPendingEvent(other) } returns true
        job.sync()
        verify { projection.syncPendingEvent(other) }
        verify { diagnostics.record(match { it.outcome == DiagnosticOutcome.COMPLETED && it.failed == 1 && it.changed == 1 }) }
    }

    @Test
    fun `successful events leave the pending query and are not submitted again`() {
        every { events.findPendingCalendarProjections(child.id, any(), any(), any()) } returnsMany listOf(listOf(id), emptyList())
        job.sync(); job.sync()
        verify(exactly = 1) { projection.syncPendingEvent(id) }
    }
}
