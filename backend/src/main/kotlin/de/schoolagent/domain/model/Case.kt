package de.schoolagent.domain.model

import de.schoolagent.domain.enums.CaseCategory
import de.schoolagent.domain.enums.CaseStatus
import de.schoolagent.domain.enums.ChangeTargetType
import de.schoolagent.domain.enums.ChangeType
import de.schoolagent.domain.enums.Importance
import de.schoolagent.domain.enums.Actor
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

/** One real school matter, spec §7. The unit the whole system revolves around. */
@Entity
@Table(name = "school_case")
class SchoolCase(
    @Id val id: UUID = UUID.randomUUID(),
    var childId: UUID? = null,
    var title: String,
    var summary: String? = null,
    @Enumerated(EnumType.STRING) var category: CaseCategory = CaseCategory.OTHER,
    @Enumerated(EnumType.STRING) var status: CaseStatus = CaseStatus.NEW,
    @Enumerated(EnumType.STRING) var importance: Importance = Importance.DIGEST,
    var importanceScore: Int = 0,
    var confidence: BigDecimal? = null,
    var investigationComplete: Boolean = false,
    var investigationNotes: String? = null,
    var dedupKey: String? = null,
    var openedAt: Instant = Instant.EPOCH,
    var closedAt: Instant? = null,
    var muted: Boolean = false,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
    var informationOnly: Boolean = false,
    var infoOverride: Boolean? = null,
    var classificationReview: Boolean = false,
    var completionSnapshot: String? = null,
    var suppressInitialNotification: Boolean = false,
) {
    fun isInfo(): Boolean = infoOverride ?: informationOnly
    fun isClosed(): Boolean = status == CaseStatus.COMPLETED || status == CaseStatus.CANCELLED
}

/** Full case history, spec §8: state is never mutated without a record. */
@Entity
@Table(name = "case_status_entry")
class CaseStatusEntry(
    @Id val id: UUID = UUID.randomUUID(),
    var caseId: UUID,
    @Enumerated(EnumType.STRING) var fromStatus: CaseStatus? = null,
    @Enumerated(EnumType.STRING) var toStatus: CaseStatus,
    var reason: String? = null,
    @jakarta.persistence.Convert(converter = de.schoolagent.security.LegacyActorConverter::class) var actor: Actor = Actor.SYSTEM,
    var createdAt: Instant = Instant.EPOCH,
)

/** Mutation log: how new evidence changed case state, spec §23. */
@Entity
@Table(name = "case_change")
class CaseChange(
    @Id val id: UUID = UUID.randomUUID(),
    var caseId: UUID,
    @Enumerated(EnumType.STRING) var changeType: ChangeType,
    @Enumerated(EnumType.STRING) var targetType: ChangeTargetType,
    var targetId: UUID? = null,
    @JdbcTypeCode(SqlTypes.JSON) var beforeJson: String? = null,
    @JdbcTypeCode(SqlTypes.JSON) var afterJson: String? = null,
    var evidenceId: UUID? = null,
    var createdAt: Instant = Instant.EPOCH,
)
