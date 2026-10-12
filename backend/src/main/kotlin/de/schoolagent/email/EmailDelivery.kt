package de.schoolagent.email

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID
import java.time.Instant

@Entity
@Table(name = "email_delivery")
class EmailDelivery(
    @Id val id: UUID = UUID.randomUUID(),
    val childId: UUID,
    val gmailId: String,
    val sourceId: UUID,
    val originalMessageId: String? = null,
    val fingerprint: String? = null,
    val gmailThreadId: String? = null,
    val referenceIds: String = "",
    val forwardedBy: String? = null,
    val receivedAt: Instant = Instant.now(),
)
interface EmailDeliveryRepository : JpaRepository<EmailDelivery, UUID> {
    fun findByChildId(childId: UUID): List<EmailDelivery>
    fun findBySourceIdIn(sourceIds: Collection<UUID>): List<EmailDelivery>
}
