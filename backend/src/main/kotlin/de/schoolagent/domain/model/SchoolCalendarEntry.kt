package de.schoolagent.domain.model

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Bavarian school calendar entry: vacation, public holiday, bridge day. */
enum class CalendarEntryType { VACATION, HOLIDAY, BRIDGE_DAY, TEACHER_DAY }

@Entity
@Table(name = "school_calendar_entry")
class SchoolCalendarEntry(
    @Id val id: UUID = UUID.randomUUID(),
    var childId: UUID? = null,
    var name: String,
    var startDate: java.time.LocalDate,
    var endDate: java.time.LocalDate,
    @Enumerated(EnumType.STRING) var entryType: CalendarEntryType = CalendarEntryType.HOLIDAY,
    var createdAt: Instant = Instant.EPOCH,
)
