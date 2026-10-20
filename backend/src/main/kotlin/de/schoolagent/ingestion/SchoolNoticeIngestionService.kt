package de.schoolagent.ingestion

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.schoolagent.common.DedupKeys
import de.schoolagent.connectors.school.*
import de.schoolagent.documents.DocumentStore
import de.schoolagent.documents.FileSystemDocumentStore
import de.schoolagent.domain.enums.*
import de.schoolagent.domain.model.*
import de.schoolagent.domain.repo.*
import de.schoolagent.domain.service.CaseService
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Complete manifests are acquired before committing any version. Original Source rows remain
 * readable; current-source repository queries exclude superseded parts and their evidence.
 */
@Service
class SchoolNoticeIngestionService(
    private val items: SchoolItemRepository,
    private val revisions: SchoolItemRevisionRepository,
    private val sources: SourceRepository,
    private val cases: SchoolCaseRepository,
    private val children: ChildRepository,
    private val changes: CaseChangeRepository,
    private val lifecycle: CaseService,
    private val config: SchoolConnectionService,
    private val documents: DocumentStore,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    private val responses: SchoolResponseReconciliation,
) {
    private val json = jacksonObjectMapper().findAndRegisterModules()
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
    data class Result(val seen: Int, val changed: Int, val failed: Int, val pending: Int = 0, val newPending: Int = 0)
    private data class Part(val document: SchoolDocument, val hash: String, val facts: String)

    fun sync(childId: UUID, credentials: SchoolCredentials, session: SchoolSession,
             notices: List<SchoolNotice>, initial: Boolean): Result {
        var changed = 0
        var failed = 0
        var refreshes = 0
        var acquisitions = 0
        var pending = 0
        var newPending = 0
        val started = System.nanoTime()
        val namespace = namespace(childId, credentials)
        val known = items.findByChildId(childId).associateBy { it.itemKey }
        // Rotate oldest content checks first. New letters are never starved by the refresh queue.
        val ordered = notices.sortedBy { known[key(namespace, it.id)]?.checkedAt ?: java.time.Instant.MIN }
        for (notice in ordered) {
            if (Duration.ofNanos(System.nanoTime() - started) > Duration.ofSeconds(120)) {
                pending++
                if (known[key(namespace, notice.id)] == null) newPending++
                continue
            }
            if (!session.canAcquire(notice)) { failed++; continue }
            val key = key(namespace, notice.id)
            val existing = try { known[key] ?: adopt(childId, credentials, namespace, key, notice) }
            catch (e: Exception) {
                failed++
                log.warn("School notice adoption failed reference={} errorType={}", key.take(12), e.javaClass.simpleName)
                log.debug("School check failure location={}", e.stackTrace.take(6).joinToString())
                continue
            }
            val markerChanged = existing != null && notice.changeMarker != null && existing.changeMarker != notice.changeMarker
            val titleChanged = existing?.title?.let { it != notice.title } ?: false
            if (existing != null && !session.canRefresh(notice)) {
                if (markerChanged || titleChanged) fail(existing, "READ_BEHAVIOR_UNVERIFIED", pending = true)
                continue
            }
            if (existing != null && !markerChanged && !titleChanged && existing.failureCode == null &&
                Duration.between(existing.checkedAt, clock.instant()) < Duration.ofMinutes(30)) continue
            if (existing != null && refreshes++ >= 10) { pending++; continue }
            if (existing == null && acquisitions++ >= 30) { pending++; newPending++; continue }
            try {
                val detail = session.details(notice, credentials.studentId)
                require(detail.documents.isNotEmpty() && detail.documents.size <= 40) { "Invalid school document manifest" }
                require(detail.documents.sumOf { it.bytes.size.toLong() } <= 32L * 1024 * 1024) { "School manifest exceeds size limit" }
                require(detail.documents.map { it.id }.distinct().size == detail.documents.size) { "Duplicate document identity" }
                val parts = detail.documents.map { Part(it, FileSystemDocumentStore.sha256(it.bytes), SchoolDocumentFingerprint.of(it.bytes, it.mimeType)) }
                // Complete bytes are persisted outside the database transaction. A failed commit leaves only
                // content-addressed orphan blobs, never a partial manifest or a half-updated case.
                parts.forEach { documents.put(it.document.bytes) }
                tx.executeWithoutResult {
                    children.lockById(childId) ?: error("Child no longer exists")
                    check(config.credentials(childId) == credentials) { "School connection changed during sync" }
                    if (apply(childId, credentials, namespace, key, notice, parts, detail.response, initial)) changed++
                }
            } catch (e: Exception) {
                failed++
                log.warn("School notice content check failed reference={} errorType={}", key.take(12), e.javaClass.simpleName)
                log.debug("School check failure location={}", e.stackTrace.take(6).joinToString())
                existing?.let { fail(it, "CONTENT_CHECK_FAILED", pending = markerChanged || titleChanged) }
            }
        }
        return Result(notices.size, changed, failed, pending, newPending)
    }

    private fun apply(childId: UUID, credentials: SchoolCredentials, namespace: String, key: String,
                      notice: SchoolNotice, parts: List<Part>, response: SchoolResponse?, initial: Boolean): Boolean {
        val now = clock.instant()
        val item = items.findById(key).orElse(null)
        val raw = digest(parts.map { "${it.document.id}:${it.document.mimeType}:${it.hash}" })
        val facts = digest(parts.map { "${it.document.id}:${it.document.mimeType}:${it.facts}" } + "title:${notice.title}")
        val c = item?.let { sources.findById(it.sourceId).orElseThrow().caseId }?.let { cases.lockById(it) }
            ?: lifecycle.createCase(childId, notice.title.take(200))
        if (item != null && item.manifestHash == raw && item.factHash == facts) {
            if (c.schoolUpdatePending && c.schoolPreviousStatus == c.status.name) {
                c.schoolUpdatePending = false; c.schoolPreviousStatus = null; cases.save(c)
            }
            responses.reconcile(c, key, item.schoolStatus, response, credentials.provider, notice.url)
            item.checkedAt = now; item.lastSuccessAt = now; item.failureCode = null; item.changeMarker = notice.changeMarker
            item.schoolStatus = response?.let(json::writeValueAsString)
            items.save(item)
            return false
        }
        val previous = item?.let { sources.findBySchoolItemKey(key).filterNot { s -> s.superseded } }.orEmpty()
        val material = item == null || item.factHash != facts
        val version = (item?.version ?: 0) + 1
        if (item == null) { c.suppressInitialNotification = initial; cases.save(c) }
        else if (material) {
            if (!c.schoolUpdatePending) c.schoolPreviousStatus = c.status.name
            c.schoolUpdatePending = true
            c.suppressInitialNotification = false
            cases.save(c)
        }
        val selected = parts.map { part ->
            val nativePart = "${notice.id}:${part.document.id}"
            val old = previous.find { it.externalId == nativePart }
            if (old?.contentHash == part.hash && (part.document.id != "letter" || item?.title == notice.title)) old
            else {
                val s = sources.save(Source(
                    sourceType = if (part.document.id == "letter") credentials.provider.sourceType else SourceType.ATTACHMENT,
                    externalId = nativePart, dedupKey = "portal:$key:$version:${DedupKeys.sha256(part.document.id)}",
                    contentHash = part.hash, title = if (part.document.id == "letter") notice.title else part.document.title,
                    receivedAt = notice.receivedAt, originalSentAt = part.document.sentAt, canonicalUrl = notice.url,
                    storageRef = part.hash, mimeType = part.document.mimeType, byteSize = part.document.bytes.size.toLong(),
                    fetchStatus = FetchStatus.FETCHED, createdAt = now, updatedAt = now,
                    schoolItemKey = key, schoolVersion = version,
                    factHash = if (!material && old != null) old.factHash ?: old.contentHash
                        else if (part.document.id == "letter") DedupKeys.sha256("${part.facts}|${notice.title}") else part.facts,
                    revisionIdentity = old?.revisionIdentity ?: old?.id?.toString() ?: "$key:${part.document.id}",
                ))
                if (material) lifecycle.attachSource(c, s)
                else { s.caseId = c.id; sources.save(s) }
                s
            }
        }
        previous.filter { old -> selected.none { it.id == old.id } }.forEach { it.superseded = true; sources.save(it) }
        // Crawled evidence is derived from the previous letter, and must be re-investigated.
        val replacedIds = previous.filter { it.superseded }.map { it.id.toString() }.toSet()
        if (material && item != null) sources.findByCaseId(c.id).filter {
            it.derivedFromSources?.split(",")?.any(replacedIds::contains) == true
        }.forEach { it.superseded = true; sources.save(it) }
        val saved = item ?: SchoolItem(key, childId, namespace, notice.id, selected.first().id,
            manifestHash = raw, factHash = facts, checkedAt = now)
        saved.version = version; saved.manifestHash = raw; saved.factHash = facts
        saved.sourceId = selected.first().id; saved.checkedAt = now; saved.lastSuccessAt = now
        saved.failureCode = null; saved.changeMarker = notice.changeMarker
        saved.title = notice.title
        responses.reconcile(c, key, saved.schoolStatus, response, credentials.provider, notice.url)
        saved.schoolStatus = response?.let(json::writeValueAsString)
        items.save(saved)
        revisions.save(SchoolItemRevision(itemKey = key, version = version, manifestHash = raw, factHash = facts,
            parts = json.writeValueAsString(selected.map { it.id }), observedAt = now, material = material))
        if (item == null) lifecycle.transition(c, CaseStatus.INGESTING, Actor.SYSTEM, "School notice imported")
        else if (material) {
            if (c.status != CaseStatus.INVESTIGATING) lifecycle.reopen(c.id, Actor.SYSTEM, "School notice corrected")
            changes.save(CaseChange(caseId = c.id, changeType = ChangeType.CHANGE, targetType = ChangeTargetType.CASE,
                beforeJson = """{"schoolVersion":${version - 1}}""", afterJson = """{"schoolVersion":$version}""", createdAt = now))
        }
        return material
    }

    private fun adopt(childId: UUID, credentials: SchoolCredentials, namespace: String, key: String, notice: SchoolNotice): SchoolItem? =
        tx.execute {
            if (notice.kind != SchoolNoticeKind.LETTER) return@execute null
            children.lockById(childId) ?: return@execute null
            check(config.credentials(childId) == credentials) { "School connection changed during adoption" }
            items.findById(key).orElse(null)?.let { return@execute it }
            val legacyKey = DedupKeys.source(credentials.provider.sourceType,
                if (credentials.provider == SchoolProvider.ELTERNPORTAL) "$childId:${notice.id}"
                else "$childId:${credentials.institutionId}:${credentials.studentId}:${notice.id}")
            val first = sources.findByDedupKey(legacyKey)
                ?: if (credentials.provider == SchoolProvider.ELTERNPORTAL)
                    sources.findByDedupKey(DedupKeys.source(SourceType.PORTAL_LETTER, notice.id)) else null
            if (first?.fetchStatus != FetchStatus.FETCHED || first.caseId == null ||
                cases.findById(first.caseId!!).orElse(null)?.childId != childId) return@execute null
            if (first.schoolItemKey != null && first.schoolItemKey != key) return@execute null
            if (credentials.provider == SchoolProvider.ELTERNPORTAL && first.canonicalUrl != null &&
                runCatching { java.net.URI(first.canonicalUrl).host != java.net.URI(credentials.url).host }.getOrDefault(true)) return@execute null
            val parts = sources.findByCaseId(first.caseId!!).filter {
                it.id == first.id || (it.sourceType == SourceType.ATTACHMENT && it.externalId.startsWith("${notice.id}:"))
            }
            if (parts.isEmpty()) return@execute null
            // Seed crawl provenance only for an unambiguous single-notice legacy case.
            val all = sources.findByCaseId(first.caseId!!)
            if (all.all { it in parts || it.sourceType in setOf(SourceType.WEBPAGE, SourceType.WEBPAGE_JS) })
                all.filter { it.sourceType in setOf(SourceType.WEBPAGE, SourceType.WEBPAGE_JS) && it.derivedFromSources == null }
                    .forEach { it.derivedFromSources = parts.map { s -> s.id.toString() }.sorted().joinToString(","); sources.save(it) }
            val normalized = parts.map { s ->
                val partId = if (s.id == first.id) "letter" else s.externalId.removePrefix("${notice.id}:")
                s.schoolItemKey = key; s.schoolVersion = 1
                s.revisionIdentity = s.id.toString(); s.factHash = s.contentHash
                s.externalId = "${notice.id}:$partId"; sources.save(s)
                val bytes = s.storageRef?.let(documents::get)
                Triple(partId, s, bytes?.let { SchoolDocumentFingerprint.of(it, s.mimeType.orEmpty()) } ?: s.contentHash)
            }
            val raw = digest(normalized.map { "${it.first}:${it.second.mimeType}:${it.second.contentHash}" })
            val facts = digest(normalized.map { "${it.first}:${it.second.mimeType}:${it.third}" } + "title:${notice.title}")
            val item = items.save(SchoolItem(key, childId, namespace, notice.id, first.id, manifestHash = raw, factHash = facts,
                checkedAt = java.time.Instant.EPOCH, title = notice.title))
            revisions.save(SchoolItemRevision(itemKey = key, version = 1, manifestHash = raw, factHash = facts,
                parts = json.writeValueAsString(parts.map { it.id }), observedAt = clock.instant(), material = false))
            item
        }

    private fun fail(item: SchoolItem, code: String, pending: Boolean) = tx.executeWithoutResult {
        children.lockById(item.childId) ?: return@executeWithoutResult
        val current = items.findById(item.itemKey).orElse(null) ?: return@executeWithoutResult
        current.checkedAt = clock.instant(); current.failureCode = code; items.save(current)
        if (pending) sources.findById(current.sourceId).orElse(null)?.caseId?.let { id ->
            cases.lockById(id)?.let { c ->
                if (!c.schoolUpdatePending) c.schoolPreviousStatus = c.status.name
                c.schoolUpdatePending = true; cases.save(c)
            }
        }
    }

    private fun digest(values: List<String>) = DedupKeys.sha256(json.writeValueAsString(values.sorted()))
    private fun key(namespace: String, id: String) = DedupKeys.sha256("$namespace|$id")
    private fun namespace(childId: UUID, c: SchoolCredentials) = DedupKeys.sha256(
        "$childId|${c.provider}|${c.url.trimEnd('/')}|${c.institutionId}|${c.studentId}|${c.username.trim().lowercase()}")
}
