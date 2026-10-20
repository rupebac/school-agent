package de.schoolagent.ingestion

import com.fasterxml.jackson.databind.ObjectMapper
import de.schoolagent.common.DedupKeys
import de.schoolagent.connectors.google.GoogleOAuthService
import de.schoolagent.connectors.school.*
import de.schoolagent.documents.DocumentStore
import de.schoolagent.diagnostics.*
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
    private val diagnostics: IntegrationDiagnostics? = null,
    private val requirements: ChildRequirementRepository? = null,
) {
    private val json = ObjectMapper()
    private val berlin = ZoneId.of("Europe/Berlin")
    private val log = LoggerFactory.getLogger(javaClass)
    data class Result(val seen: Int, val changed: Int, val failed: Int, val feeds: List<SchoolRecordFeed> = emptyList())
    private val effectLocks = Array(64) { Any() }

    fun sync(childId: UUID, credentials: SchoolCredentials, scope: SchoolStudentScope,
             feeds: List<SchoolRecordFeed>, from: LocalDate, to: LocalDate, initialImport: Boolean, run: UUID = UUID.randomUUID()): Result {
        val namespace = namespace(childId, credentials, scope)
        fun trace(outcome: DiagnosticOutcome, feed: SchoolRecordFeed, record: SchoolRecord? = null, caseId: UUID? = null) {
            val entry = DiagnosticTrace(if (record == null) DiagnosticStage.SCHOOL_FEED else DiagnosticStage.SCHOOL_RECORD,
                outcome, run, childId, caseId = caseId, recordKey = record?.let { key(namespace, it) }, provider = credentials.provider,
                kind = feed.kind, count = if (record == null) feed.records.size else null, complete = if (record == null) feed.complete else null)
            if (outcome in setOf(DiagnosticOutcome.UNCHANGED, DiagnosticOutcome.BEFORE_WINDOW, DiagnosticOutcome.AFTER_WINDOW))
                diagnostics?.oncePerDay(entry) else diagnostics?.record(entry)
        }
        var seen = 0; var changed = 0; var failed = 0
        val statuses = feeds.toMutableList()
        for ((index, feed) in feeds.withIndex()) {
            // Save the complete set, including past exams, before applying any filters.
            // Identical sets need one daily checkpoint; a changed set is recorded immediately.
            diagnostics?.snapshot(DiagnosticTrace(DiagnosticStage.SCHOOL_SNAPSHOT,
                when (feed.status) { SchoolFeedStatus.AVAILABLE -> DiagnosticOutcome.AVAILABLE
                    SchoolFeedStatus.UNAVAILABLE -> DiagnosticOutcome.UNAVAILABLE; SchoolFeedStatus.FAILED -> DiagnosticOutcome.FAILED },
                run, childId, provider = credentials.provider, kind = feed.kind, count = feed.records.size, complete = feed.complete,
                recordKeys = feed.records.map { key(namespace, it) }))
            if (feed.status != SchoolFeedStatus.AVAILABLE) continue
            if (scope.studentId.isNullOrBlank() || feed.records.any { it.kind != feed.kind || (it.kind == SchoolRecordKind.EXAM && it.date == null) } ||
                feed.records.map { it.id }.distinct().size != feed.records.size) {
                failed++
                statuses[index] = feed.copy(status = SchoolFeedStatus.FAILED, complete = false,
                    message = "Invalid child binding, dates or duplicate record identifiers; this section has been kept unchanged and will retry")
                trace(DiagnosticOutcome.INVALID_FEED, feed)
                continue
            }
            val firstSnapshot = !config.hasRecordBaseline(childId, namespace, feed.kind)
            if (firstSnapshot) trace(DiagnosticOutcome.INITIAL_SNAPSHOT, feed)
            val keys = mutableSetOf<String>()
            var feedFailed = false
            for (record in feed.records) {
                val examDate = record.date
                if (record.kind == SchoolRecordKind.EXAM && examDate != null && examDate !in from..to) {
                    trace(if (examDate.isBefore(from)) DiagnosticOutcome.BEFORE_WINDOW else DiagnosticOutcome.AFTER_WINDOW, feed, record)
                    continue
                }
                seen++
                val key = key(namespace, record)
                keys += key
                try {
                    val payload = payload(record)
                    val hash = DedupKeys.sha256(payload)
                    val previous = records.findById(key).orElse(null)
                    if (record.timetable?.changed == false && previous == null) continue
                    if (previous != null && previous.active && record.kind == SchoolRecordKind.PAYMENT &&
                        paymentMetadataOnly(previous.payload, payload)) {
                        transactions.executeWithoutResult {
                            guard(childId, credentials, scope)
                            val latest = records.findById(key).orElseThrow()
                            if (latest.contentHash == previous.contentHash) {
                                latest.payload = payload; latest.contentHash = hash
                                records.save(latest)
                            }
                        }
                        finish(previous)
                        continue
                    }
                    val sameFacts = previous?.contentHash == hash && previous.active
                    if (previous?.contentHash == hash && previous.active && !previous.wordingPending &&
                        previous.wordingVersion == SchoolRecordInterpreter.WORDING_VERSION) {
                        trace(DiagnosticOutcome.UNCHANGED, feed, record, previous.caseId)
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
                    trace(if (sameFacts) DiagnosticOutcome.WORDING_REPAIRED else DiagnosticOutcome.APPLIED, feed, record, stored.caseId)
                    if (previous == null && (initialImport || firstSnapshot)) trace(DiagnosticOutcome.INITIAL_SNAPSHOT, feed, record)
                    finish(stored)
                } catch (e: Exception) {
                    failed++; feedFailed = true
                    trace(DiagnosticOutcome.FAILED, feed, record)
                    log.warn("Native school record import failed run={} kind={} errorType={}", run, record.kind, e.javaClass.simpleName)
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
                        diagnostics?.record(DiagnosticTrace(DiagnosticStage.SCHOOL_RECORD, DiagnosticOutcome.WITHDRAWN, run, childId,
                            caseId = old.caseId, recordKey = old.recordKey, provider = credentials.provider, kind = old.kind))
                        finish(old)
                        changed++
                    } catch (e: Exception) {
                        failed++; feedFailed = true
                        diagnostics?.record(DiagnosticTrace(DiagnosticStage.SCHOOL_RECORD, DiagnosticOutcome.FAILED, run, childId,
                            caseId = old.caseId, recordKey = old.recordKey, provider = credentials.provider, kind = old.kind))
                    }
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
        val oldSource = old?.let { sources.findById(it.sourceId).orElseThrow() }
        val c = old?.let { cases.lockById(oldSource?.caseId ?: it.caseId) ?: throw IllegalStateException("School record case missing") }
            ?: lifecycle.createCase(childId, copy.title, category, "school:$key")
        val before = old?.payload
        val priorCaseStatus = c.status
        val previousPayment = before?.let { runCatching { json.readTree(it).path("payment") }.getOrNull() }
        val previousTotal = previousPayment?.path("totalMinor")?.takeIf { it.isIntegralNumber }?.asLong()
        val previousPaid = previousPayment?.path("paidMinor")?.takeIf { it.isIntegralNumber }?.asLong()
        val settled = record.payment?.status in setOf(SchoolPaymentStatus.SETTLED, SchoolPaymentStatus.CANCELLED)
        val renewedPayment = record.kind == SchoolRecordKind.PAYMENT && record.payment?.let { p ->
            (previousTotal != null && p.totalMinor != null && p.totalMinor > previousTotal) ||
                (previousPaid != null && p.paidMinor != null && p.paidMinor < previousPaid)
        } == true
        val preserveDone = old != null && record.kind == SchoolRecordKind.PAYMENT &&
            actions.findByDedupKey("act:$key")?.status == ActionStatus.COMPLETED && !renewedPayment
        val reviewCompletedDuty = c.schoolUpdatePending || old != null && (
            preserveDone && (record.payment == null || record.payment.status == SchoolPaymentStatus.UNKNOWN) ||
                record.kind == SchoolRecordKind.HOMEWORK && requirements?.findByDedupKey("req:$key")?.status == RequirementStatus.DONE &&
                json.readTree(old.payload).path("text").asText() != record.text)
        if (reviewCompletedDuty && !c.schoolUpdatePending) {
            c.schoolPreviousStatus = priorCaseStatus.name
            c.schoolUpdatePending = true
        }
        if (old != null && c.status != CaseStatus.INVESTIGATING) lifecycle.transition(c, CaseStatus.INVESTIGATING, Actor.SYSTEM, "Native school record changed")
        val merged = oldSource != null && sources.findByCaseId(c.id).any { it.id != oldSource.id }
        if (!merged) { c.title = copy.title; c.summary = copy.summary; c.category = category }
        c.informationOnly = record.kind !in setOf(SchoolRecordKind.PAYMENT, SchoolRecordKind.HOMEWORK)
        val recordImportance = when (record.kind) { SchoolRecordKind.PAYMENT -> 5; SchoolRecordKind.HOMEWORK -> 2; else -> 4 }
        c.importanceScore = if (merged) maxOf(c.importanceScore, recordImportance) else recordImportance
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
            copy.dueDate?.let { appendLine("${if (record.kind == SchoolRecordKind.PAYMENT) "Payment deadline" else "School deadline"}: $it") }
            appendLine(record.text)
        }.toByteArray(Charsets.UTF_8)
        val documentHash = documents.put(bytes)
        val version = (oldSource?.schoolVersion ?: if (oldSource == null) 0 else 1) + 1
        val source = Source(
            sourceType = if (credentials.provider == SchoolProvider.ELTERNPORTAL) SourceType.ELTERNPORTAL_RECORD else SourceType.SCHULMANAGER_RECORD,
            externalId = "${record.kind}:${record.id}", dedupKey = if (oldSource == null) "src:$key" else "src:$key:$version", contentHash = documentHash,
            schoolVersion = version, revisionIdentity = oldSource?.revisionIdentity ?: oldSource?.id?.toString(),
            receivedAt = now, createdAt = now)
        oldSource?.let { it.superseded = true; sources.save(it) }
        source.title = record.title; source.contentHash = documentHash; source.storageRef = documentHash
        source.originalSentAt = record.publishedAt
        source.canonicalUrl = record.url; source.mimeType = "text/plain"; source.byteSize = bytes.size.toLong()
        source.fetchStatus = FetchStatus.FETCHED; source.updatedAt = now
        sources.save(source)
        lifecycle.attachSource(c, source)
        if (old == null) {
            lifecycle.transition(c, CaseStatus.INGESTING, Actor.SYSTEM, "Native school record imported")
        }
        c.investigationComplete = true
        if (!reviewCompletedDuty) {
            applyEvent(c, record, copy, key)
            applyPayment(c, record, copy, key, credentials.provider, preserveDone || settled)
        }
        if (record.kind == SchoolRecordKind.HOMEWORK && !reviewCompletedDuty) {
            val repo = requireNotNull(requirements)
            val r = repo.findByDedupKey("req:$key") ?: ChildRequirement(caseId = c.id, description = copy.summary,
                dedupKey = "req:$key", createdAt = now)
            r.description = copy.summary; r.relevantOn = record.dueDate
            if (r.status != RequirementStatus.DONE) r.status = RequirementStatus.OPEN
            r.updatedAt = now; repo.save(r)
        }
        if (merged) c.informationOnly = actions.findByCaseId(c.id).none { it.required && it.status !in setOf(ActionStatus.COMPLETED, ActionStatus.CANCELLED) } &&
            requirements?.findByCaseId(c.id)?.none { it.required && it.status == RequirementStatus.OPEN } != false
        lifecycle.transition(c, CaseStatus.READY_FOR_REASONING, Actor.SYSTEM, "Native school facts validated")
        if (!reviewCompletedDuty) lifecycle.transition(c, if (c.isInfo()) CaseStatus.INFORMATION_ONLY else CaseStatus.ACTION_REQUIRED, Actor.SYSTEM, "School record applied")
        if (!reviewCompletedDuty && record.kind == SchoolRecordKind.PAYMENT && (preserveDone || settled)) {
            val outstanding = actions.findByCaseId(c.id).any { it.required && it.status !in setOf(ActionStatus.COMPLETED, ActionStatus.CANCELLED) } ||
                requirements?.findByCaseId(c.id)?.any { it.required && it.status == RequirementStatus.OPEN } == true
            if (!outstanding) lifecycle.transition(c, CaseStatus.COMPLETED, Actor.SYSTEM, if (settled) "School payment resolved" else "Local payment completion preserved")
            events.findByDedupKey("evt:$key")?.let {
                it.status = EventStatus.CANCELLED; it.googleSyncStatus = GoogleSyncStatus.PENDING; events.save(it)
            }
        }
        if (!reviewCompletedDuty && record.kind == SchoolRecordKind.HOMEWORK && priorCaseStatus == CaseStatus.COMPLETED &&
            actions.findByCaseId(c.id).none { it.required && it.status !in setOf(ActionStatus.COMPLETED, ActionStatus.CANCELLED) } &&
            requirements?.findByCaseId(c.id)?.none { it.required && it.status == RequirementStatus.OPEN } == true)
            lifecycle.transition(c, CaseStatus.COMPLETED, Actor.SYSTEM, "Completed assignment preserved after deadline update")
        changes.save(CaseChange(caseId = c.id, changeType = if (old == null) ChangeType.ADD else ChangeType.CHANGE,
            targetType = ChangeTargetType.CASE, beforeJson = before, afterJson = payload, createdAt = now))
        val stored = old ?: StoredSchoolRecord(key, namespace, childId, record.kind, record.id, record.date, hash, payload, c.id, source.id, updatedAt = now)
        stored.caseId = c.id; stored.sourceId = source.id
        stored.contentHash = hash; stored.payload = payload; stored.recordDate = record.date
        stored.active = true; stored.notificationPending = !reviewCompletedDuty && record.kind != SchoolRecordKind.HOMEWORK; stored.projectionPending = true; stored.wordingPending = !copy.ai; stored.updatedAt = now
        stored.wordingVersion = SchoolRecordInterpreter.WORDING_VERSION
        cases.flush(); sources.flush()
        return records.save(stored)
    }

    private fun applyEvent(c: SchoolCase, record: SchoolRecord, copy: SchoolRecordInterpreter.Copy, key: String) {
        if (record.kind == SchoolRecordKind.HOMEWORK) return // Undated assignments do not invent Calendar appointments.
        val now = clock.instant()
        val date = if (record.kind == SchoolRecordKind.PAYMENT) copy.dueDate else record.date
        if (date != null) {
            val type = when (record.kind) { SchoolRecordKind.EXAM -> EventType.EXAM; SchoolRecordKind.PAYMENT -> EventType.PAYMENT
                SchoolRecordKind.TIMETABLE_CHANGE -> EventType.TIMETABLE_CHANGE; else -> EventType.SCHOOL_REPORT }
            val event = events.findByDedupKey("evt:$key") ?: SchoolEvent(caseId = c.id, childId = c.childId, title = copy.title, eventType = type,
                startTime = date.atStartOfDay(berlin).toInstant(), dedupKey = "evt:$key", createdAt = now)
            event.title = copy.title; event.eventType = type
            event.startTime = date.atTime(record.time ?: java.time.LocalTime.MIDNIGHT).atZone(berlin).toInstant()
            event.endTime = record.endTime?.let { date.atTime(it).atZone(berlin).toInstant() }
            require(event.endTime == null || event.endTime!! > event.startTime) { "Invalid native school event time range" }
            event.allDay = record.time == null; event.status = if (record.timetable?.changed == false) EventStatus.CANCELLED else EventStatus.PLANNED;
            event.googleSyncStatus = GoogleSyncStatus.PENDING; event.updatedAt = now
            events.save(event)
        } else events.findByDedupKey("evt:$key")?.let { it.status = EventStatus.CANCELLED; it.updatedAt = now; events.save(it) }
    }

    private fun applyPayment(c: SchoolCase, record: SchoolRecord, copy: SchoolRecordInterpreter.Copy, key: String, provider: SchoolProvider,
                             resolved: Boolean = false) {
        val now = clock.instant()
        if (record.kind == SchoolRecordKind.PAYMENT) {
            val action = actions.findByDedupKey("act:$key") ?: ParentAction(caseId = c.id, title = copy.action!!, dedupKey = "act:$key", createdAt = now)
            action.title = if (record.payment?.status == SchoolPaymentStatus.UNKNOWN) "Check the school payment balance" else copy.action!!; action.description = copy.summary; action.externalUrl = record.url
            action.portalProvider = provider.name
            action.dueAt = copy.dueDate?.atStartOfDay(berlin)?.toInstant()
            action.status = when {
                record.payment?.status == SchoolPaymentStatus.CANCELLED -> ActionStatus.CANCELLED
                resolved -> ActionStatus.COMPLETED
                else -> ActionStatus.OPEN
            }
            action.completedAt = if (resolved) action.completedAt ?: now else null; action.updatedAt = now
            actions.save(action)
        }
    }

    private fun cancel(old: StoredSchoolRecord) {
        val source = sources.findById(old.sourceId).orElse(null)
        val c = cases.lockById(source?.caseId ?: old.caseId) ?: return
        old.caseId = c.id
        events.findByDedupKey("evt:${old.recordKey}")?.let {
            it.status = EventStatus.CANCELLED; it.googleSyncStatus = GoogleSyncStatus.PENDING; it.updatedAt = clock.instant(); events.save(it)
        }
        val otherFacts = sources.findByCaseId(c.id).any { it.id != old.sourceId }
        if (!otherFacts && c.status != CaseStatus.CANCELLED) lifecycle.transition(c, CaseStatus.CANCELLED, Actor.SYSTEM, "Removed from the verified class exam timetable")
        changes.save(CaseChange(caseId = c.id, changeType = ChangeType.CANCEL, targetType = ChangeTargetType.CASE,
            beforeJson = old.payload, afterJson = "{\"withdrawnFromTimetable\":true}", createdAt = clock.instant()))
        old.active = false; old.notificationPending = true; old.projectionPending = true; old.updatedAt = clock.instant(); records.save(old)
    }

    /** Copy repair preserves completion and notifications; a newly grounded deadline is recorded as a fact change. */
    private fun refreshWording(record: StoredSchoolRecord, copy: SchoolRecordInterpreter.Copy) {
        sources.findById(record.sourceId).orElse(null)?.caseId?.let { record.caseId = it }
        val c = cases.lockById(record.caseId) ?: return
        if (c.schoolUpdatePending) return
        val before = json.writeValueAsString(mapOf("title" to c.title, "summary" to c.summary,
            "wordingPending" to record.wordingPending, "wordingVersion" to record.wordingVersion))
        if (sources.findByCaseId(c.id).none { it.id != record.sourceId }) {
            c.title = copy.title; c.summary = copy.summary; cases.save(c)
        }
        events.findByDedupKey("evt:${record.recordKey}")?.let { it.title = copy.title; events.save(it) }
        if (record.kind == SchoolRecordKind.HOMEWORK) requirements?.findByDedupKey("req:${record.recordKey}")?.let {
            it.description = copy.summary; requirements.save(it)
        }
        if (record.kind == SchoolRecordKind.PAYMENT) actions.findByDedupKey("act:${record.recordKey}")?.let {
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
        val effectiveCaseId = sources.findById(record.sourceId).orElse(null)?.caseId ?: record.caseId
        if (cases.findById(effectiveCaseId).orElse(null)?.schoolUpdatePending == true) return@synchronized
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
            if (project(effectiveCaseId)) acknowledgeProjection()
        }.onFailure { log.warn("School calendar projection will retry for {} ({})", record.caseId, it.javaClass.simpleName) }
        if (record.notificationPending) runCatching {
            // Suppression consumption, notification enqueue and outbox acknowledgement commit together.
            // Otherwise a crash after consuming a quiet-import flag would announce the backlog on retry.
            transactions.executeWithoutResult {
                kids.lockById(record.childId) ?: return@executeWithoutResult
                val current = records.findById(record.recordKey).orElse(null) ?: return@executeWithoutResult
                if (!current.notificationPending || current.contentHash != record.contentHash || current.active != record.active ||
                    current.wordingPending != record.wordingPending) return@executeWithoutResult
                current.caseId = sources.findById(current.sourceId).orElse(null)?.caseId ?: current.caseId
                val c = cases.lockById(current.caseId) ?: return@executeWithoutResult
                if (c.schoolUpdatePending) return@executeWithoutResult
                val change = changes.findByCaseIdOrderByCreatedAtAsc(c.id).lastOrNull {
                    it.beforeJson != null && it.afterJson != null && it.afterJson == current.payload
                }
                val oldDate = change?.beforeJson?.let { runCatching { json.readTree(it).path("date").asText(null) }.getOrNull() }
                val dateFormat = java.time.format.DateTimeFormatter.ofPattern("dd.MM.uuuu")
                val date = current.recordDate?.format(dateFormat)
                val previousDate = oldDate?.let { runCatching { LocalDate.parse(it).format(dateFormat) }.getOrDefault(it) }
                when {
                    current.kind == SchoolRecordKind.TIMETABLE_CHANGE && json.readTree(current.payload).path("timetable").path("changed").asBoolean(true).not() ->
                        notifications.notifySchoolChange(c, "Lesson restored", c.summary.orEmpty())
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
    private fun payload(record: SchoolRecord) = json.writeValueAsString(linkedMapOf<String, Any?>("id" to record.id, "kind" to record.kind,
        "title" to record.title, "text" to record.text, "date" to record.date?.toString(), "time" to record.time?.toString(),
        "endTime" to record.endTime?.toString(), "dueDate" to record.dueDate?.toString(), "publishedAt" to record.publishedAt?.toString(), "url" to record.url
        ).apply {
            record.payment?.let { put("payment", it) }
            record.timetable?.let { put("timetable", it) }
        })

    /** Adding typed metadata to an unchanged legacy invoice is not a new school instruction. */
    internal fun paymentMetadataOnly(before: String, after: String): Boolean {
        val old = json.readTree(before)
        val next = json.readTree(after)
        if (old.has("payment") || !next.hasNonNull("payment")) return false
        fun comparable(node: com.fasterxml.jackson.databind.JsonNode): com.fasterxml.jackson.databind.JsonNode {
            val copy = node.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            copy.remove("payment")
            val text = copy.path("text").asText().lineSequence()
                .filterNot { it.startsWith("School payment status: ") }
                .map { line ->
                    if (line.startsWith("Requested amount: ") || line.startsWith("Already paid: "))
                        Regex("\\d+(?:\\.\\d+)?(?= EUR)").replace(line) { it.value.toBigDecimal().stripTrailingZeros().toPlainString() }
                    else line
                }.joinToString("\n")
            copy.put("text", text)
            return copy
        }
        return comparable(old) == comparable(next)
    }
}
