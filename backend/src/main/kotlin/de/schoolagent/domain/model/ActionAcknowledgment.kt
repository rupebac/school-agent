package de.schoolagent.domain.model

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

data class ActionAcknowledgmentId(
    val actionId: UUID = UUID.randomUUID(),
    val userId: UUID = UUID.randomUUID(),
) : Serializable

/** Tracks which parent has seen an action in the web UI. */
@Entity
@Table(name = "action_acknowledgment")
@IdClass(ActionAcknowledgmentId::class)
class ActionAcknowledgment(
    @Id var actionId: UUID = UUID.randomUUID(),
    @Id var userId: UUID = UUID.randomUUID(),
    var acknowledgedAt: Instant = Instant.EPOCH,
)
