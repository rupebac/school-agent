package de.schoolagent.domain.model

import de.schoolagent.domain.enums.Actor
import jakarta.persistence.Entity
import jakarta.persistence.JoinTable
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToMany
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "app_user")
class AppUser(
    @Id val id: UUID = UUID.randomUUID(),
    var name: String,
    var displayName: String,
    var passwordHash: String,
    var whatsappNumber: String? = null,
    var role: String = "PARENT",
    var language: String = "en",
    var briefingEnabled: Boolean = true,
    var briefingTime: String = "19:00",
    var weeklyEnabled: Boolean = true,
    var weeklyTime: String = "10:00",
    var instantEnabled: Boolean = true,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
) {
    @ManyToMany(mappedBy = "accounts", fetch = jakarta.persistence.FetchType.EAGER)
    val kids: MutableSet<Child> = mutableSetOf()
}

@Entity
@Table(name = "child")
class Child(
    @Id val id: UUID = UUID.randomUUID(),
    var name: String,
    var school: String? = null,
    var className: String? = null,
    var gmailAddress: String? = null,
    var googleCalendarId: String? = null,
    var color: String = "#2563eb",
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
) {
    @ManyToMany(fetch = jakarta.persistence.FetchType.EAGER)
    @JoinTable(
        name = "account_kid",
        joinColumns = [jakarta.persistence.JoinColumn(name = "child_id")],
        inverseJoinColumns = [jakarta.persistence.JoinColumn(name = "account_id")],
    )
    val accounts: MutableSet<AppUser> = mutableSetOf()
}

@Entity
@Table(name = "audit_entry")
class AuditEntry(
    @Id val id: UUID = UUID.randomUUID(),
    var caseId: UUID? = null,
    @jakarta.persistence.Convert(converter = de.schoolagent.security.LegacyActorConverter::class) var actor: Actor = Actor.SYSTEM,
    var action: String,
    @JdbcTypeCode(SqlTypes.JSON) var detail: String? = null,
    var createdAt: Instant = Instant.EPOCH,
)
