package de.schoolagent.domain.model

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "case_relevance")
class StoredCaseRelevance(
    @Id var caseId: UUID = UUID.randomUUID(),
    var contentHash: String = "",
    var expiresAt: Instant = Instant.EPOCH,
    var assessment: String = "UNKNOWN",
    var confidence: Double? = null,
    var reason: String = "",
    var createdAt: Instant = Instant.EPOCH,
)
