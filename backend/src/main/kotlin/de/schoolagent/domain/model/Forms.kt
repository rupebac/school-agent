package de.schoolagent.domain.model

import de.schoolagent.domain.enums.FormAccessStatus
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * An external form the agent inspected (never submits in v1), spec §12:
 * what the form asks, its deadline, and whether it was reachable at all.
 */
@Entity
@Table(name = "external_form")
class ExternalForm(
    @Id val id: UUID = UUID.randomUUID(),
    var caseId: UUID,
    var url: String,
    var pageUrl: String? = null,
    var deadline: Instant? = null,
    var requiresConsent: Boolean = false,
    var requiresPayment: Boolean = false,
    @JdbcTypeCode(SqlTypes.JSON) var fieldDescriptions: String? = null,
    @Enumerated(EnumType.STRING) var accessStatus: FormAccessStatus = FormAccessStatus.ACCESSIBLE,
    var lastInspectedAt: Instant? = null,
    var notes: String? = null,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)
