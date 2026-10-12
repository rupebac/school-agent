package de.schoolagent.common

import de.schoolagent.domain.enums.EvidenceKind
import de.schoolagent.domain.enums.EventType
import de.schoolagent.domain.enums.SourceType
import java.security.MessageDigest
import java.util.UUID

/** Deterministic dedup keys. Spec §38: idempotency via stable keys, not hope. */
object DedupKeys {

    fun source(sourceType: SourceType, externalId: String): String =
        "src:$sourceType:${externalId.lowercase().trim()}"

    fun case(connectorThreadKey: String): String =
        "case:${sha256(connectorThreadKey.trim().lowercase())}"

    fun evidence(sourceId: UUID, kind: EvidenceKind, subject: String): String =
        "ev:${sha256("$sourceId|$kind|${normalize(subject)}")}"

    fun action(caseId: UUID, title: String): String =
        "act:${sha256("$caseId|${normalize(title)}")}"

    fun requirement(caseId: UUID, description: String, relevantOn: String?): String =
        "req:${sha256("$caseId|${normalize(description)}|${relevantOn ?: ""}")}"

    fun event(caseId: UUID, eventType: EventType, title: String): String =
        "evt:${sha256("$caseId|$eventType|${normalize(title)}")}"

    fun notification(caseId: UUID, fingerprint: String): String =
        "ntf:${sha256("$caseId|${normalize(fingerprint)}")}"

    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun normalize(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase()
}
