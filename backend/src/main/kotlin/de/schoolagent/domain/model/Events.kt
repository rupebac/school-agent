package de.schoolagent.domain.model

import de.schoolagent.domain.enums.EventStatus
import de.schoolagent.domain.enums.EventType
import de.schoolagent.domain.enums.GoogleSyncStatus
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Calendar-projected event, spec §22. Google Calendar is a projection:
 * the internal row is authoritative, [googleEventId] links the projection.
 */
@Entity
@Table(name = "school_event")
class SchoolEvent(
    @Id val id: UUID = UUID.randomUUID(),
    var caseId: UUID,
    var childId: UUID? = null,
    var title: String,
    @Enumerated(EnumType.STRING) var eventType: EventType,
    var startTime: Instant,
    var endTime: Instant? = null,
    var allDay: Boolean = false,
    var location: String? = null,
    @Enumerated(EnumType.STRING) var status: EventStatus = EventStatus.PLANNED,
    var googleEventId: String? = null,
    var googleCalendarId: String? = null,
    @Enumerated(EnumType.STRING) var googleSyncStatus: GoogleSyncStatus = GoogleSyncStatus.PENDING,
    var dedupKey: String,
    var supersededById: UUID? = null,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)
