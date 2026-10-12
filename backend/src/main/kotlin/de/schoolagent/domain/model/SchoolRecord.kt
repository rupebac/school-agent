package de.schoolagent.domain.model

import de.schoolagent.connectors.school.SchoolRecordKind
import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "school_record")
class StoredSchoolRecord(
    @Id var recordKey: String,
    var namespace: String,
    var childId: UUID,
    @Enumerated(EnumType.STRING) var kind: SchoolRecordKind,
    var nativeId: String,
    var recordDate: LocalDate?,
    var contentHash: String,
    var payload: String,
    var caseId: UUID,
    var sourceId: UUID,
    var active: Boolean = true,
    var notificationPending: Boolean = true,
    var projectionPending: Boolean = true,
    var wordingPending: Boolean = false,
    var wordingVersion: Int = 0,
    var updatedAt: Instant,
)
