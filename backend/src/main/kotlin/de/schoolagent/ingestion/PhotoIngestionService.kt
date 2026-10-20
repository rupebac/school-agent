package de.schoolagent.ingestion

import de.schoolagent.common.DedupKeys
import de.schoolagent.documents.DocumentStore
import de.schoolagent.domain.enums.Actor
import de.schoolagent.domain.enums.CaseCategory
import de.schoolagent.domain.enums.CaseStatus
import de.schoolagent.domain.enums.FetchStatus
import de.schoolagent.domain.enums.SourceType
import de.schoolagent.domain.model.AppUser
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.model.Source
import de.schoolagent.domain.repo.ChildRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.repo.SourceRepository
import de.schoolagent.domain.service.CaseService
import de.schoolagent.reasoning.CaseReasoningService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Phone photo / PDF slip → case. WhatsApp inbound and the Inbox camera
 * both land here; investigation + reasoning run off the request thread.
 */
@Service
class PhotoIngestionService(
    private val documents: DocumentStore,
    private val caseService: CaseService,
    private val caseRepo: SchoolCaseRepository,
    private val sourceRepo: SourceRepository,
    private val kids: ChildRepository,
    private val investigation: InvestigationService,
    private val reasoning: CaseReasoningService,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class Ingested(val schoolCase: SchoolCase, val duplicate: Boolean)

    @Transactional
    fun ingest(
        bytes: ByteArray,
        mimeType: String,
        filename: String?,
        caption: String?,
        sender: AppUser,
        childId: UUID?,
    ): Ingested {
        require(bytes.isNotEmpty()) { "empty file" }
        require(bytes.size <= MAX_BYTES) { "file too large (max ${MAX_BYTES / (1024 * 1024)} MB)" }
        val mime = mimeType.substringBefore(';').trim().ifBlank { "application/octet-stream" }
        require(isAllowed(mime)) { "unsupported type $mime — send a photo or PDF" }

        val kid = childId ?: inferKid(sender, caption)
        val allowed = if (sender.role == "ADMIN") kids.findAll().map { it.id } else sender.kids.map { it.id }
        require(kid != null && kid in allowed) { "Choose a child you have access to before uploading." }
        val hash = documents.put(bytes)
        val dedupKey = DedupKeys.source(SourceType.MANUAL_UPLOAD, "$kid:$hash")
        sourceRepo.findByDedupKey(dedupKey)?.caseId?.let { id ->
            val existing = caseRepo.findById(id).orElse(null)
            if (existing != null) return Ingested(existing, duplicate = true)
        }

        val title = caption?.trim()?.takeIf { it.isNotBlank() }?.take(200) ?: "Photo slip"
        val actor = actorFor(sender)
        val schoolCase = caseService.createCase(
            childId = kid,
            title = title,
            category = CaseCategory.OTHER,
            actor = actor,
        )
        val source = sourceRepo.save(
            Source(
                caseId = schoolCase.id,
                sourceType = SourceType.MANUAL_UPLOAD,
                externalId = "photo:$hash",
                dedupKey = dedupKey,
                contentHash = hash,
                title = filename?.takeIf { it.isNotBlank() } ?: title,
                sender = sender.displayName,
                receivedAt = clock.instant(),
                storageRef = hash,
                mimeType = mime,
                byteSize = bytes.size.toLong(),
                fetchStatus = FetchStatus.FETCHED,
                createdAt = clock.instant(),
                updatedAt = clock.instant(),
            ),
        )
        caseService.attachSource(schoolCase, source, actor)
        caseService.transition(schoolCase, CaseStatus.INGESTING, actor, "photo uploaded")
        return Ingested(schoolCase, duplicate = false)
    }

    fun processAsync(caseId: UUID) {
        Thread.startVirtualThread {
            runCatching { investigation.investigate(caseId) }
                .onFailure { log.warn("photo investigation {}: {}", caseId, it.javaClass.simpleName) }
            runCatching { reasoning.reason(caseId) }
                .onFailure { log.warn("photo reasoning {}: {}", caseId, it.javaClass.simpleName) }
        }
    }

    private fun inferKid(sender: AppUser, caption: String?): UUID? {
        val all = kids.findAll().filter { sender.role == "ADMIN" || sender.kids.any { k -> k.id == it.id } }
        val named = caption?.let { text ->
            all.filter { kid -> Regex("(?i)\\b${Regex.escape(kid.name)}\\b").containsMatchIn(text) }.singleOrNull()?.id
        }
        if (named != null) return named
        val assigned = sender.kids.map { it.id }
        if (assigned.size == 1) return assigned.single()
        if (all.size == 1) return all.single().id
        return null
    }

    private fun actorFor(sender: AppUser): Actor = Actor.MANUAL

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        fun isAllowed(mime: String): Boolean =
            mime.startsWith("image/", ignoreCase = true) || mime.equals("application/pdf", ignoreCase = true)
    }
}
