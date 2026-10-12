package de.schoolagent.domain.model

import de.schoolagent.domain.enums.ConnectorType
import de.schoolagent.domain.enums.HealthStatus
import de.schoolagent.domain.enums.ModelRole
import de.schoolagent.domain.enums.NotificationChannel
import de.schoolagent.domain.enums.NotificationLevel
import de.schoolagent.domain.enums.NotificationStatus
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

/** Every outbound message attempt, spec §25/§26. */
@Entity
@Table(name = "notification")
class Notification(
    @Id val id: UUID = UUID.randomUUID(),
    var caseId: UUID? = null,
    @Enumerated(EnumType.STRING) var level: NotificationLevel,
    @Enumerated(EnumType.STRING) var channel: NotificationChannel,
    var recipientId: UUID? = null,
    var message: String,
    @Enumerated(EnumType.STRING) var status: NotificationStatus = NotificationStatus.QUEUED,
    var sentAt: Instant? = null,
    var dedupKey: String,
    var createdAt: Instant = Instant.EPOCH,
    var templatePayload: String? = null,
    var messageId: String? = null,
    var attemptCount: Int = 0,
    var nextAttemptAt: Instant? = null,
    var expiresAt: Instant? = null,
    var lastError: String? = null,
)

@Entity
@Table(name = "whatsapp_receipt")
class WhatsappReceipt(
    @Id var messageId: String = "",
    var status: String = "",
    var errorCode: Int? = null,
    var error: String? = null,
    var updatedAt: Instant = Instant.EPOCH,
)

/** Connector heartbeat, spec §30: "no new mail" vs "can't check mail". */
@Entity
@Table(name = "connector_health")
class ConnectorHealth(
    @Id @Enumerated(EnumType.STRING) var connector: ConnectorType,
    @Enumerated(EnumType.STRING) var status: HealthStatus = HealthStatus.UNKNOWN,
    var lastSuccessAt: Instant? = null,
    var lastFailureAt: Instant? = null,
    var lastError: String? = null,
    var consecutiveFailures: Int = 0,
    var updatedAt: Instant = Instant.EPOCH,
)

/** Role → model routing, frontend-editable, spec §14. */
@Entity
@Table(name = "model_config")
class ModelConfig(
    @Id val id: UUID = UUID.randomUUID(),
    @Enumerated(EnumType.STRING) var role: ModelRole,
    var provider: String,
    var modelName: String,
    var reasoningEffort: String? = null,
    var temperature: BigDecimal? = null,
    var enabled: Boolean = true,
    var updatedAt: Instant = Instant.EPOCH,
)

/** Cost/usage tracking per AI call batch, spec §41. */
@Entity
@Table(name = "ai_usage")
class AiUsage(
    @Id val id: UUID = UUID.randomUUID(),
    var caseId: UUID? = null,
    var role: String,
    var model: String,
    var purpose: String? = null,
    var inputTokens: Int = 0,
    var outputTokens: Int = 0,
    var calls: Int = 1,
    var estimatedCost: BigDecimal? = null,
    var createdAt: Instant = Instant.EPOCH,
)

/** Explicit family policy rule, spec §35: feedback becomes rules, not fine-tuning. */
@Entity
@Table(name = "family_rule")
class FamilyRule(
    @Id val id: UUID = UUID.randomUUID(),
    var ruleKey: String,
    @JdbcTypeCode(SqlTypes.JSON) var ruleValue: String? = null,
    var enabled: Boolean = true,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "setting")
class Setting(
    @Id var key: String,
    var valueJson: String? = null,
    var updatedAt: Instant = Instant.EPOCH,
)

/** Encrypted secret reference; plaintext never leaves the vault, spec §31. */
@Entity
@Table(name = "secret_ref")
class SecretRef(
    @Id var key: String,
    var cipherText: String,
    var updatedAt: Instant = Instant.EPOCH,
)
