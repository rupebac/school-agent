package de.schoolagent.domain.model

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** Composite key: one translation per (source, language, content version). */
data class SourceTranslationId(
    val sourceId: UUID = UUID.randomUUID(),
    val language: String = "",
    val contentHash: String = "",
) : Serializable

@Entity
@Table(name = "source_translation")
@IdClass(SourceTranslationId::class)
class SourceTranslation(
    @Id var sourceId: UUID = UUID.randomUUID(),
    @Id var language: String = "",
    @Id var contentHash: String = "",
    var translatedText: String = "",
    var createdAt: Instant = Instant.EPOCH,
)
