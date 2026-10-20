package de.schoolagent.domain.model

import de.schoolagent.domain.enums.EvidenceKind
import de.schoolagent.domain.enums.FetchStatus
import de.schoolagent.domain.enums.FailureCategory
import de.schoolagent.domain.enums.InvestigationStatus
import de.schoolagent.domain.enums.SourceType
import de.schoolagent.domain.enums.StepOutcome
import de.schoolagent.domain.enums.StepType
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Every acquired raw object, with provenance, spec §29. */
@Entity
@Table(name = "source")
class Source(
    @Id val id: UUID = UUID.randomUUID(),
    var caseId: UUID? = null,
    @Enumerated(EnumType.STRING) var sourceType: SourceType,
    var externalId: String,
    var dedupKey: String,
    var contentHash: String,
    var title: String? = null,
    var sender: String? = null,
    var forwardedBy: String? = null,
    var originalSentAt: Instant? = null,
    var receivedAt: Instant? = null,
    var canonicalUrl: String? = null,
    var storageRef: String? = null,
    var mimeType: String? = null,
    var byteSize: Long? = null,
    @Enumerated(EnumType.STRING) var fetchStatus: FetchStatus = FetchStatus.PENDING,
    @Enumerated(EnumType.STRING) var failureCategory: FailureCategory? = null,
    var failureMessage: String? = null,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
    var superseded: Boolean = false,
    var schoolItemKey: String? = null,
    var schoolVersion: Int? = null,
    var factHash: String? = null,
    var revisionIdentity: String? = null,
    var derivedFromSources: String? = null,
)

/** Distilled fact from one source, produced by the extraction model, spec §16. */
@Entity
@Table(name = "evidence")
class Evidence(
    @Id val id: UUID = UUID.randomUUID(),
    var caseId: UUID,
    var sourceId: UUID,
    @Enumerated(EnumType.STRING) var kind: EvidenceKind,
    var subject: String,
    @JdbcTypeCode(SqlTypes.JSON) var payload: String? = null,
    var confidence: BigDecimal? = null,
    var extractedBy: String? = null,
    var dedupKey: String,
    var createdAt: Instant = Instant.EPOCH,
)

/** One bounded crawl session for a case, spec §17/§18. */
@Entity
@Table(name = "investigation")
class Investigation(
    @Id val id: UUID = UUID.randomUUID(),
    var caseId: UUID,
    @Enumerated(EnumType.STRING) var status: InvestigationStatus = InvestigationStatus.RUNNING,
    var startedAt: Instant = Instant.EPOCH,
    var finishedAt: Instant? = null,
    var pagesVisited: Int = 0,
    var aiCalls: Int = 0,
    var tokensUsed: Int = 0,
    @JdbcTypeCode(SqlTypes.JSON) var limitsHit: String? = null,
    var notes: String? = null,
)

@Entity
@Table(name = "investigation_step")
class InvestigationStep(
    @Id val id: UUID = UUID.randomUUID(),
    var investigationId: UUID,
    @Enumerated(EnumType.STRING) var stepType: StepType,
    var target: String,
    @Enumerated(EnumType.STRING) var outcome: StepOutcome,
    @JdbcTypeCode(SqlTypes.JSON) var detail: String? = null,
    var createdAt: Instant = Instant.EPOCH,
)
