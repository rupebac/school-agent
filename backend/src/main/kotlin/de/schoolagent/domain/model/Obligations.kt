package de.schoolagent.domain.model

import de.schoolagent.domain.enums.ActionStatus
import de.schoolagent.domain.enums.RequirementStatus
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** What a parent must do, spec §20. */
@Entity
@Table(name = "parent_action")
class ParentAction(
    @Id val id: UUID = UUID.randomUUID(),
    var caseId: UUID,
    var title: String,
    var description: String? = null,
    var dueAt: Instant? = null,
    var assignedTo: UUID? = null,
    @Enumerated(EnumType.STRING) var status: ActionStatus = ActionStatus.OPEN,
    var externalUrl: String? = null,
    var required: Boolean = true,
    @JdbcTypeCode(SqlTypes.JSON) var recommendedInput: String? = null,
    var confidence: BigDecimal? = null,
    /** Which piece of evidence produced this action — spec §29 provenance. */
    var evidenceId: UUID? = null,
    var dedupKey: String,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
    var completedAt: Instant? = null,
    /** Connector-provided navigation type, independent of translated task descriptions. */
    var portalProvider: String? = null,
)

/** What the child must do — deliberately separate from parent actions, spec §21. */
@Entity
@Table(name = "child_requirement")
class ChildRequirement(
    @Id val id: UUID = UUID.randomUUID(),
    var caseId: UUID,
    var description: String,
    var relevantOn: LocalDate? = null,
    var required: Boolean = true,
    @Enumerated(EnumType.STRING) var status: RequirementStatus = RequirementStatus.OPEN,
    var dedupKey: String,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)
