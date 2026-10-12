package de.schoolagent.domain.model

import jakarta.persistence.*
import java.io.Serializable
import java.time.Instant
import java.util.UUID

data class CaseReadId(val caseId: UUID = UUID.randomUUID(), val userId: UUID = UUID.randomUUID()) : Serializable

@Entity @Table(name = "case_read") @IdClass(CaseReadId::class)
class CaseRead(
    @Id var caseId: UUID = UUID.randomUUID(),
    @Id var userId: UUID = UUID.randomUUID(),
    var revision: String = "",
    var readAt: Instant = Instant.EPOCH,
)

data class CaseSummaryId(val caseId: UUID = UUID.randomUUID(), val language: String = "", val revision: String = "") : Serializable

@Entity @Table(name = "case_summary") @IdClass(CaseSummaryId::class)
class StoredCaseSummary(
    @Id var caseId: UUID = UUID.randomUUID(),
    @Id var language: String = "",
    @Id var revision: String = "",
    var body: String = "",
    var createdAt: Instant = Instant.EPOCH,
)
