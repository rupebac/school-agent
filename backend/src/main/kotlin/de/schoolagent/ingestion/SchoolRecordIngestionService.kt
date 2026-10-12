package de.schoolagent.ingestion

import com.fasterxml.jackson.databind.ObjectMapper
import de.schoolagent.common.DedupKeys
import de.schoolagent.connectors.google.GoogleOAuthService
import de.schoolagent.connectors.school.*
import de.schoolagent.documents.DocumentStore
import de.schoolagent.domain.enums.*
import de.schoolagent.domain.model.*
import de.schoolagent.domain.repo.*
import de.schoolagent.domain.service.CaseService
import de.schoolagent.events.CalendarProjectionService
import de.schoolagent.whatsapp.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Native facts bypass crawling and model date extraction. Language is cached; mutations are audited. */
@Service
class SchoolRecordIngestionService(
    private val records: SchoolRecordRepository,
    private val sources: SourceRepository,
    private val cases: SchoolCaseRepository,
    private val events: SchoolEventRepository,
    private val actions: ParentActionRepository,
    private val changes: CaseChangeRepository,
    private val lifecycle: CaseService,
    private val kids: ChildRepository,
    private val config: SchoolConnectionService,
    private val documents: DocumentStore,
    private val interpreter: SchoolRecordInterpreter,
    private val notifications: NotificationService,
    private val calendar: CalendarProjectionService,
    private val oauth: GoogleOAuthService,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
) {
    private val json = ObjectMapper()
    private val berlin = ZoneId.of("Europe/Berlin")
    private val log = LoggerFactory.getLogger(javaClass)
    data class Result(val seen: Int, val changed: Int, val failed: Int, val feeds: List<SchoolRecordFeed> = emptyList())
    private val effectLocks = Array(64) { Any() }

    fun sync(childId: UUID, credentials: SchoolCredentials, scope: SchoolStudentScope,
             feeds: List<SchoolRecordFeed>, from: LocalDate, to: LocalDate, initialImport: Boolean): Result {
        val namespace = namespace(childId, credentials, scope)
        var seen = 0; var changed = 0; var failed = 0
        val statuses = feeds.toMutableList()
        for ((index, feed) in feeds.withIndex()) {
            if (feed.status != SchoolFeedStatus.AVAILABLE) continue
            if (scope.studentId.isNullOrBlank() || feed.records.any { it.kind != feed.kind || (it.kind == SchoolRecordKind.EXAM && it.date == null) } ||
                feed.records.map { it.id }.distinct().size != feed.records.size) {
                failed++
                statuses[index] = feed.copy(status = SchoolFeedStatus.FAILED, complete = false,
                    message = "Invalid child binding, dates or duplicate record identifiers; this section has been kept unchanged and will retry")
                continue
            }
            val firstSnapshot = !config.hasRecordBaseline(childId, namespace, feed.kind)
            val keys = mutableSetOf<String>()
            var feedFailed = false
            for (record in feed.records) {
                if (record.kind == SchoolRecordKind.EXAM && (record.date == null || record.date !in from..to)) continue
                seen++
                val key = key(namespace, record)
                keys += key
                try {
                    val payload = payload(record)
                    val hash = DedupKeys.sha256(payload)
                    val previous = records.findById(key).orElse(null)
                    if (previous?.contentHash == hash && previous.active && !previous.wordingPending &&
                        previous.wordingVersion == SchoolRecordInterpreter.WORDING_VERSION) {
                        finish(previous)
                        continue
                    }
                    val copy = interpreter.interpret(record)
                    val stored = transactions.execute {
                        guard(childId, credentials, scope)
                        val latest = records.findById(key).orElse(null)
                        if (latest?.contentHash == hash && latest.active) {
                            if (latest.wordingVersion != SchoolRecordInterpreter.WORDING_VERSION || (latest.wordingPending && copy.ai))
                                refreshWording(latest, copy)
                            latest
                        } else apply(childId, credentials, namespace, key, record, payload, hash, copy, latest,
                            initialImport || firstSnapshot).also { changed++ }
                    }!!
                    finish(stored)
                } catch (e: Exception) {
                    failed++; feedFailed = true
                    log.warn("Native school record import failed child={} kind={} ({})", childId, record.kind, e.javaClass.simpleName)
                }
            }
            // Neither denied access, a partial fetch nor a failed import can erase existing facts.
            if (feed.kind == SchoolRecordKind.EXAM && feed.complete && !feedFailed) {
                records.findByChildIdAndKindAndActiveTrue(childId, feed.kind).filter {
                    (it.namespace != namespace || it.recordKey !in keys) &&
                        it.recordDate?.let { date -> date in from..to && !date.isBefore(clock.instant().atZone(berlin).toLocalDate()) } == true
                }.forEach { old ->
                    try {
                        transactions.executeWithoutResult {
                            guard(childId, credentials, scope)
                            cancel(old)
                        }
                        finish(old)
                        changed++
                    } catch (_: Exception) { failed++; feedFailed = true }
                }
            }
            if (feedFailed) statuses[index] = feed.copy(status = SchoolFeedStatus.FAILED, complete = false,
                message = "Some records could not be imported; existing records have been kept and this section will retry")
            else transactions.executeWithoutResult {
                guard(childId, credentials, scope)
                config.recordBaseline(childId, namespace, feed.kind)
            }
        }
        return Result(seen, changed, failed, statuses)
    }

    private fun guard(childId: UUID, credentials: SchoolCredentials, scope: SchoolStudentScope) {
        val child = kids.lockById(childId) ?: throw SchoolConnectionException("Child no longer exists")
        if (config.credentials(childId) != credentials || child.name != scope.name || child.className != scope.className)
            throw SchoolConnectionException("School connection or child changed during sync; retry")
    }

    private fun apply(childId: UUID, credentials: SchoolCredentials, namespace: String, key: String, record: SchoolRecord,
                      payload: String, hash: String, copy: SchoolRecordInterpreter.Copy, old: StoredSchoolRecord?, initialImport: Boolean): StoredSchoolRecord {
        val now = clock.instant()
        val category = when (record.kind) { SchoolRecordKind.EXAM -> CaseCategory.EXAM; SchoolRecordKind.PAYMENT -> CaseCategory.PAYMENT
            SchoolRecordKind.FORGOTTEN_MATERIAL -> CaseCategory.MATERIAL; else -> CaseCategory.INFO }
        val c = old?.let { cases.lockById(it.caseId) ?: throw IllegalStateException("School record case missing") }
            ?: lifecycle.createCase(childId, copy.title, category, "school:$key")
        val before = old?.payload
        if (old != null && c.status != CaseStatus.INVESTIGATING) lifecycle.transition(c, CaseStatus.INVESTIGATING, Actor.SYSTEM, "Native school record changed")
        c.title = copy.title; c.summary = copy.summary; c.category = category
        c.informationOnly = record.kind != SchoolRecordKind.PAYMENT
        c.importanceScore = if (record.kind == SchoolRecordKind.PAYMENT) 5 else 4
        c.importance = importanceFromScore(c.importanceScore)
        c.investigationComplete = true
        c.investigationNotes = "Authoritative native school record; no website crawl needed"
        val historicalReport = record.kind in setOf(SchoolRecordKind.FORGOTTEN_HOMEWORK, SchoolRecordKind.FORGOTTEN_MATERIAL) &&
            record.date?.isBefore(clock.instant().atZone(berlin).toLocalDate().minusDays(3)) == true
        c.suppressInitialNotification = (old == null && initialImport) || historicalReport
        c.updatedAt = now
        cases.save(c)
        val bytes = buildString {
            appendLine("${record.kind.label}: ${record.title}")
            record.date?.let { appendLine("${if (record.kind == SchoolRecordKind.EXAM) "Exam date" else "Reported date"}: $it") }
            record.time?.let { appendLine("Time: $it") }
            copy.dueDate?.let { appendLine("Payment deadline: $it") }
            appendLine(record.text)
        }.toByteArray(Charsets.UTF_8)
        val documentHash = documents.put(bytes)
        val source = old?.let { sources.findById(it.sourceId).orElseThrow() } ?: Source(
            sourceType = if (credentials.provider == SchoolProvider.ELTERNPORTAL) SourceType.ELTERNPORTAL_RECORD else SourceType.SCHULMANAGER_RECORD,
            externalId = "${record.kind}:${record.id}", dedupKey = "src:$key", contentHash = documentHash,
            receivedAt = now, createdAt = now)
        source.title = record.title; source.contentHash = documentHash; source.storageRef = documentHash
        source.originalSentAt = record.publishedAt
        source.canonicalUrl = record.url; source.mimeType = "text/plain"; source.byteSize = bytes.size.toLong()
        source.fetchStatus = FetchStatus.FETCHED; source.updatedAt = now
        sources.save(source)
        if (old == null) {
            lifecycle.attachSource(c, source)
            lifecycle.transition(c, CaseStatus.INGESTING, Actor.SYSTEM, "Native school record imported")
        }
        c.investigationComplete = true
        applyEvent(c, record, copy, key)
        applyPayment(c, record, copy, key, credentials.provider)
        lifecycle.transition(c, CaseStatus.READY_FOR_REASONING, Actor.SYSTEM, "Native school facts validated")
        lifecycle.transition(c, if (c.isInfo()) CaseStatus.INFORMATION_ONLY else CaseStatus.ACTION_REQUIRED, Actor.SYSTEM, "School record applied")
        changes.save(CaseChange(caseId = c.id, changeType = if (old == null) ChangeType.ADD else ChangeType.CHANGE,
            targetType = ChangeTargetType.CASE, beforeJson = before, afterJson = payload, createdAt = now))
        val stored = old ?: StoredSchoolRecord(key, namespace, childId, record.kind, record.id, record.date, hash, payload, c.id, source.id, updatedAt = now)
        stored.contentHash = hash; stored.payload = payload; stored.recordDate = record.date
        stored.active = true; stored.notificationPending = true; stored.projectionPending = true; stored.wordingPending = !copy.ai; stored.updatedAt = now
        stored.wordingVersion = SchoolRecordInterpreter.WORDING_VERSION
        cases.flush(); sources.flush()
        return records.save(stored)
    }

    private fun applyEvent(c: SchoolCase, record: SchoolRecord, copy: SchoolRecordInterpreter.Copy, key: String) {
        val now = clock.instant()
        val date = if (record.kind == SchoolRecordKind.PAYMENT) copy.dueDate else record.date
        if (date != null) {
            val type = when (record.kind) { SchoolRecordKind.EXAM -> EventType.EXAM; SchoolRecordKind.PAYMENT -> EventType.PAYMENT; else -> EventType.SCHOOL_REPORT }
            val event = events.findByDedupKey("evt:$key") ?: SchoolEvent(caseId = c.id, childId = c.childId, title = copy.title, eventType = type,
                startTime = date.atStartOfDay(berlin).toInstant(), dedupKey = "evt:$key", createdAt = now)
            event.title = copy.title; event.eventType = type
            event.startTime = date.atTime(record.time ?: java.time.LocalTime.MIDNIGHT).atZone(berlin).toInstant()
            event.endTime = record.endTime?.let { date.atTime(it).atZone(berlin).toInstant() }
            require(event.endTime == null || event.endTime!! > event.startTime) { "Invalid native school event time range" }
            event.allDay = record.time == null; event.status = EventStatus.PLANNED; event.updatedAt = now
            events.save(event)
        } else events.findByDedupKey("evt:$key")?.let { it.status = EventStatus.CANCELLED; it.updatedAt = now; events.save(it) }
    }

    private fun applyPayment(c: SchoolCase, record: SchoolRecord, copy: SchoolRecordInterpreter.Copy, key: String, provider: SchoolProvider) {
        val now = clock.instant()
        if (record.kind == SchoolRecordKind.PAYMENT) {
            val action = actions.findByDedupKey("act:$key") ?: ParentAction(caseId = c.id, title = copy.action!!, dedupKey = "act:$key", createdAt = now)
            action.title = copy.action!!; action.description = copy.summary; action.externalUrl = record.url
            action.portalProvider = provider.name
            action.dueAt = copy.dueDate?.atStartOfDay(berlin)?.toInstant()
            action.status = ActionStatus.OPEN; action.completedAt = null; action.updatedAt = now
            actions.save(action)
        }
    }

    private fun cancel(old: StoredSchoolRecord) {
        val c = cases.lockById(old.caseId) ?: return
        if (c.status != CaseStatus.CANCELLED) lifecycle.transition(c, CaseStatus.CANCELLED, Actor.SYSTEM, "Removed from the verified class exam timetable")
        events.findByCaseId(c.id).forEach { it.status = EventStatus.CANCELLED; it.updatedAt = clock.instant(); events.save(it) }
        changes.save(CaseChange(caseId = c.id, changeType = ChangeType.CANCEL, targetType = ChangeTargetType.CASE,
            beforeJson = old.payload, afterJson = "{\"withdrawnFromTimetable\":true}", createdAt = clock.instant()))
        old.active = false; old.notificationPending = true; old.projectionPending = true; old.updatedAt = clock.instant(); records.save(old)
    }

    /** Copy repair preserves completion and notifications; a newly grounded deadline is recorded as a fact change. */
    private fun refreshWording(record: StoredSchoolRecord, copy: SchoolRecordInterpreter.Copy) {
        val c = cases.lockById(record.caseId) ?: return
        val before = json.writeValueAsString(mapOf("title" to c.title, "summary" to c.summary,
            "wordingPending" to record.wordingPending, "wordingVersion" to record.wordingVersion))
        c.title = copy.title; c.summary = copy.summary; cases.save(c)
        events.findByCaseId(c.id).forEach { it.title = copy.title; events.save(it) }
        if (record.kind == SchoolRecordKind.PAYMENT) actions.findByCaseId(c.id).forEach {
            it.title = copy.action!!; it.description = copy.summary
            val due = copy.dueDate?.atStartOfDay(berlin)?.toInstant()
            if (copy.ai && due != it.dueAt) {
                changes.save(CaseChange(caseId = c.id, changeType = ChangeType.CHANGE, targetType = ChangeTargetType.ACTION, targetId = it.id,
                    beforeJson = json.writeValueAsString(mapOf("dueAt" to it.dueAt?.toString())),
                    afterJson = json.writeValueAsString(mapOf("dueAt" to due?.toString())), createdAt = clock.instant()))
                it.dueAt = due
                if (!c.isClosed()) {
                    val source = json.readTree(record.payload)
                    applyEvent(c, SchoolRecord(record.nativeId, record.kind, source.path("title").asText(), source.path("text").asText(),
                        url = source.path("url").asText()), copy, record.recordKey)
                }
            }
            actions.save(it)
        }
        changes.save(CaseChange(caseId = c.id, changeType = ChangeType.CHANGE, targetType = ChangeTargetType.CASE,
            beforeJson = before, afterJson = json.writeValueAsString(mapOf("title" to copy.title, "summary" to copy.summary,
                "wordingPending" to !copy.ai, "wordingVersion" to SchoolRecordInterpreter.WORDING_VERSION)), createdAt = clock.instant()))
        lifecycle.recordEnrichment(c, "{\"schoolRecordKind\":\"${record.kind}\"}")
        record.wordingPending = !copy.ai; record.wordingVersion = SchoolRecordInterpreter.WORDING_VERSION
        record.projectionPending = true; record.updatedAt = clock.instant(); records.save(record)
    }

    /** The same durable record outbox handles active updates and withdrawn events, even between polls. */
    fun retryPendingEffects() {
        records.findTop100ByNotificationPendingTrueOrProjectionPendingTrueOrderByUpdatedAtAsc().forEach(::finish)
    }

    private fun finish(stored: StoredSchoolRecord) = synchronized(effectLocks[(stored.recordKey.hashCode() and Int.MAX_VALUE) % effectLocks.size]) {
        val record = records.findById(stored.recordKey).orElse(null) ?: return@synchronized
        fun acknowledgeProjection() = transactions.executeWithoutResult {
            kids.lockById(record.childId) ?: return@executeWithoutResult
            records.findById(record.recordKey).orElse(null)?.let {
                if (it.contentHash == record.contentHash && it.active == record.active && it.wordingPending == record.wordingPending) {
                    it.projectionPending = false
                    records.save(it)
                }
            }
        }
        if (record.projectionPending) runCatching {
            if (project(record.caseId)) acknowledgeProjection()
        }.onFailure { log.warn("School calendar projection will retry for {} ({})", record.caseId, it.javaClass.simpleName) }
        if (record.notificationPending) runCatching {
            // Suppression consumption, notification enqueue and outbox acknowledgement commit together.
            // Otherwise a crash after consuming a quiet-import flag would announce the backlog on retry.
            transactions.executeWithoutResult {
                kids.lockById(record.childId) ?: return@executeWithoutResult
                val current = records.findById(record.recordKey).orElse(null) ?: return@executeWithoutResult
                if (!current.notificationPending || current.contentHash != record.contentHash || current.active != record.active ||
                    current.wordingPending != record.wordingPending) return@executeWithoutResult
                val c = cases.findById(current.caseId).orElseThrow()
                val change = changes.findByCaseIdOrderByCreatedAtAsc(c.id).lastOrNull {
                    it.beforeJson != null && it.afterJson != null && it.afterJson == current.payload
                }
                val oldDate = change?.beforeJson?.let { runCatching { json.readTree(it).path("date").asText(null) }.getOrNull() }
                val dateFormat = java.time.format.DateTimeFormatter.ofPattern("dd.MM.uuuu")
                val date = current.recordDate?.format(dateFormat)
                val previousDate = oldDate?.let { runCatching { LocalDate.parse(it).format(dateFormat) }.getOrDefault(it) }
                when {
                    !current.active -> notifications.notifySchoolChange(c, "Cancelled", "Removed from the school's exam timetable. Previous date: $date.")
                    current.kind == SchoolRecordKind.EXAM && oldDate != null && oldDate != current.recordDate?.toString() && !c.suppressInitialNotification ->
                        notifications.notifySchoolChange(c, "Date changed", "Previously $previousDate; now $date.")
                    else -> notifications.notifyDecision(c)
                }
                current.notificationPending = false; records.save(current)
            }
        }.onFailure { log.warn("School notification preparation will retry for {} ({})", record.caseId, it.javaClass.simpleName) }
    }
    private fun project(caseId: UUID): Boolean {
        var success = true
        events.findByCaseId(caseId).forEach { event ->
            when {
                event.eventType == EventType.SCHOOL_REPORT || event.status == EventStatus.CANCELLED -> calendar.removeProjection(event)
                oauth.isConfigured(event.childId) -> calendar.syncEvent(event)
            }
            if (event.googleSyncStatus == GoogleSyncStatus.ERROR) success = false
        }
        return success
    }
    internal fun namespace(childId: UUID, credentials: SchoolCredentials, scope: SchoolStudentScope) = DedupKeys.sha256(
        "$childId|${credentials.provider}|${credentials.url.trimEnd('/')}|${credentials.username.trim().lowercase()}|${credentials.institutionId.orEmpty()}|${scope.studentId.orEmpty()}")
    private fun key(namespace: String, record: SchoolRecord) = "school:" + DedupKeys.sha256("$namespace|${record.kind}|${record.id}")
    private fun payload(record: SchoolRecord) = json.writeValueAsString(linkedMapOf("id" to record.id, "kind" to record.kind,
        "title" to record.title, "text" to record.text, "date" to record.date?.toString(), "time" to record.time?.toString(),
        "endTime" to record.endTime?.toString(), "dueDate" to record.dueDate?.toString(), "publishedAt" to record.publishedAt?.toString(), "url" to record.url))
}
