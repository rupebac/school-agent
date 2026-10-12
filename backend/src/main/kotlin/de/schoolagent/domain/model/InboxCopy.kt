package de.schoolagent.domain.model

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "inbox_copy")
class StoredInboxCopy(
    @Id var caseId: UUID = UUID.randomUUID(),
    var contentHash: String = "",
    var payload: String = "",
    var createdAt: Instant = Instant.EPOCH,
)
