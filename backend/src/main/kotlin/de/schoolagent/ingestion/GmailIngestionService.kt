package de.schoolagent.ingestion

import de.schoolagent.common.DedupKeys
import de.schoolagent.connectors.gmail.AttachmentData
import de.schoolagent.connectors.gmail.GmailApiException
import de.schoolagent.connectors.gmail.GmailClient
import de.schoolagent.connectors.google.GoogleProperties
import de.schoolagent.documents.DocumentStore
import de.schoolagent.domain.enums.Actor
import de.schoolagent.domain.enums.CaseStatus
import de.schoolagent.domain.enums.ConnectorType
import de.schoolagent.domain.enums.SourceType
import de.schoolagent.domain.model.Setting
import de.schoolagent.domain.model.Source
import de.schoolagent.domain.repo.ChildRepository
import de.schoolagent.domain.repo.SettingRepository
import de.schoolagent.domain.repo.SourceRepository
import de.schoolagent.domain.service.CaseService
import de.schoolagent.health.HealthTracker
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * Deterministic Gmail ingestion (spec §5, §38): poll → dedup by
 * child + Gmail delivery ID → original-message dedup → stored MIME/attachments.
 * References and Gmail threads attach replies to their existing case.
 * Idempotent: re-polling skips known message ids without any writes.
 */
@Service
class GmailIngestionService(
    private val gmail: GmailClient,
    private val props: GoogleProperties,
    private val documents: DocumentStore,
    private val caseService: CaseService,
    private val sourceRepo: SourceRepository,
    private val childRepo: ChildRepository,
    private val settings: SettingRepository,
    private val health: HealthTracker,
    private val clock: Clock,
    private val deliveries: de.schoolagent.email.EmailDeliveryRepository,
    private val emailSettings: de.schoolagent.email.SchoolEmailSettings,
    private val cases: de.schoolagent.domain.repo.SchoolCaseRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class PollResult(val inspected: Int, val ingested: Int, val attachments: Int)

    @Transactional
    fun pollOnce(childId: java.util.UUID? = null): PollResult {
        try {
            (gmail as? de.schoolagent.connectors.gmail.GmailRestClient)?.withChild(childId)
            val result = doPoll(childId)
            health.success(ConnectorType.GMAIL)
            return result
        } catch (e: GmailApiException) {
            health.failure(ConnectorType.GMAIL, e.message ?: "gmail failure")
            throw e
        } catch (e: Exception) {
            health.failure(ConnectorType.GMAIL, "unexpected: ${e.message}")
            throw e
        } finally {
            (gmail as? de.schoolagent.connectors.gmail.GmailRestClient)?.withChild(null)
        }
    }

    private fun doPoll(childId: java.util.UUID?): PollResult {
        childId?.let { childRepo.lockById(it) }
        val knownDeliveries = childId?.let { deliveries.findByChildId(it).toMutableList() } ?: mutableListOf()
        val ids = gmail.listMessageIds(MAX_PER_POLL)
        val cursorKey = childId?.let { "gmail.$it.cursor.internalDate" } ?: CURSOR_KEY
        val cursor = settings.findById(cursorKey).orElse(null)?.valueJson?.toLongOrNull() ?: 0L
        var ingested = 0
        var attachmentCount = 0
        var newest = cursor

        for (id in ids) {
            val scopedId = childId?.let { "$it:$id" } ?: id
            val dedupKey = DedupKeys.source(SourceType.EMAIL, scopedId)
            if (knownDeliveries.any { it.gmailId == id }) continue
            if (sourceRepo.existsByDedupKey(dedupKey)) continue
            if (childId != null) {
                val legacy = sourceRepo.findByDedupKey(DedupKeys.source(SourceType.EMAIL, id))
                if (legacy?.caseId != null && cases.findById(legacy.caseId!!).orElse(null)?.childId == childId) {
                    legacy.dedupKey = dedupKey; sourceRepo.save(legacy); continue
                }
            }
            val meta = gmail.fetchMetadata(id)
            if (meta.internalDateMs > newest) newest = meta.internalDateMs

            val raw = gmail.fetchRaw(id)
            val mail = de.schoolagent.email.EmailContent.read(raw)
            if (childId != null && !emailSettings.accepts(childId, mail)) continue
            val contentHash = documents.put(raw)
            val attachments = gmail.fetchAttachments(id)
            val attachmentsStored = attachments.map { it to documents.put(it.bytes) }

            val fingerprint = mail.fingerprint(attachmentsStored.map { it.second })
            val duplicate = knownDeliveries.firstOrNull {
                (mail.messageId != null && it.originalMessageId == mail.messageId) ||
                    (fingerprint != null && it.fingerprint == fingerprint)
            }
            fun recordDelivery(sourceId: java.util.UUID) {
                if (childId == null) return
                val delivery = de.schoolagent.email.EmailDelivery(childId = childId, gmailId = id, sourceId = sourceId,
                    originalMessageId = mail.messageId, fingerprint = fingerprint,
                    gmailThreadId = if (mail.forwarded) null else meta.threadId,
                    referenceIds = mail.references.joinToString("\n"),
                    forwardedBy = if (mail.forwarded) meta.from else null,
                    receivedAt = Instant.ofEpochMilli(meta.internalDateMs.coerceAtLeast(0)))
                deliveries.save(delivery); knownDeliveries.add(delivery)
            }
            if (duplicate != null) { recordDelivery(duplicate.sourceId); continue }
            // Message-ID references work even when a reply arrives before its parent.
            // Never group unrelated notices merely because they share a subject.
            val related = knownDeliveries.firstOrNull {
                it.originalMessageId in mail.references ||
                    (mail.messageId != null && mail.messageId in it.referenceIds.lines()) ||
                    (!mail.forwarded && meta.threadId != null && it.gmailThreadId == meta.threadId)
            }
            val existing = related?.sourceId?.let { sourceRepo.findById(it).orElse(null)?.caseId }
                ?.let { cases.lockById(it) }?.takeIf { it.childId == childId }
            val schoolCase = existing ?: caseService.createCase(
                childId = childId, title = (mail.subject ?: meta.subject)?.take(200) ?: "(no subject)")
            val source = sourceRepo.save(
                Source(

                    sourceType = SourceType.EMAIL,
                    externalId = id,
                    dedupKey = dedupKey,
                    contentHash = contentHash,
                    title = mail.subject ?: meta.subject,
                    sender = mail.sender ?: meta.from,
                    forwardedBy = if (mail.forwarded) meta.from else null,
                    originalSentAt = mail.sentAt,
                    receivedAt = if (meta.internalDateMs > 0) Instant.ofEpochMilli(meta.internalDateMs) else null,
                    storageRef = contentHash,
                    mimeType = "message/rfc822",
                    byteSize = raw.size.toLong(),
                    fetchStatus = de.schoolagent.domain.enums.FetchStatus.FETCHED,
                    createdAt = clock.instant(),
                    updatedAt = clock.instant(),
                ),
            )
            caseService.attachSource(schoolCase, source)
            if (existing == null) caseService.transition(schoolCase, CaseStatus.INGESTING, Actor.SYSTEM, "gmail ingested")
            else { schoolCase.investigationComplete = false; cases.save(schoolCase) }
            recordDelivery(source.id)

            attachmentCount += storeAttachmentSources(schoolCase.id, scopedId, attachmentsStored)
            ingested++
        }

        if (newest > cursor) {
            settings.save(Setting(key = cursorKey, valueJson = newest.toString(), updatedAt = clock.instant()))
        }
        log.info("gmail poll: {} inspected, {} ingested, {} attachments", ids.size, ingested, attachmentCount)
        return PollResult(inspected = ids.size, ingested = ingested, attachments = attachmentCount)
    }

    private fun storeAttachmentSources(
        caseId: java.util.UUID,
        messageId: String,
        attachments: List<Pair<AttachmentData, String>>,
    ): Int {
        var count = 0
        for ((index, pair) in attachments.withIndex()) {
            val (attachment, ref) = pair
            val externalId = "$messageId:$index:${attachment.filename}:$ref"
            val dedupKey = DedupKeys.source(SourceType.ATTACHMENT, externalId)
            if (sourceRepo.existsByDedupKey(dedupKey)) continue
            sourceRepo.save(
                Source(
                    caseId = caseId,
                    sourceType = SourceType.ATTACHMENT,
                    externalId = externalId,
                    dedupKey = dedupKey,
                    contentHash = ref,
                    title = attachment.filename,
                    mimeType = attachment.mimeType,
                    byteSize = attachment.bytes.size.toLong(),
                    storageRef = ref,
                    fetchStatus = de.schoolagent.domain.enums.FetchStatus.FETCHED,
                    createdAt = clock.instant(),
                    updatedAt = clock.instant(),
                ),
            )
            count++
        }
        return count
    }

    private fun cursorValue(): Long =
        settings.findById(CURSOR_KEY).orElse(null)?.valueJson?.trim()?.toLongOrNull() ?: 0L

    companion object {
        const val CURSOR_KEY = "gmail.cursor.internalDate"
        const val MAX_PER_POLL = 25
    }
}
