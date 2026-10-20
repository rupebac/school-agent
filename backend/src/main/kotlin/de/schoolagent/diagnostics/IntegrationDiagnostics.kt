package de.schoolagent.diagnostics

import com.fasterxml.jackson.databind.ObjectMapper
import de.schoolagent.common.DedupKeys
import de.schoolagent.connectors.school.SchoolProvider
import de.schoolagent.connectors.school.SchoolRecordKind
import de.schoolagent.domain.model.AuditEntry
import de.schoolagent.domain.repo.AuditEntryRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class DiagnosticStage { SCHOOL_SYNC, SCHOOL_FEED, SCHOOL_SNAPSHOT, SCHOOL_RECORD, NOTICE, BRIEFING, PLAN_EXAM, WHATSAPP, CALENDAR }
enum class DiagnosticOutcome {
    STARTED, COMPLETED, FAILED, NOT_CONFIGURED, AVAILABLE, UNAVAILABLE, INVALID_FEED,
    BEFORE_WINDOW, AFTER_WINDOW, UNCHANGED, APPLIED, WORDING_REPAIRED, WITHDRAWN,
    INITIAL_SNAPSHOT, QUIET_IMPORT, MUTED, CLOSED, LOW_PRIORITY, NO_RECIPIENTS,
    DUPLICATE, EXPIRED, QUEUED, NO_ITEMS, DISABLED, NO_NUMBER, INVALID_TIME,
    WAITING_FOR_TIME, WINDOW_PASSED, TEMPLATE_NOT_READY, PREPARATION_FAILED,
    INELIGIBLE, ARCHIVED, CANCELLED, SENDING, ACCEPTED, UNKNOWN, RETRY, DELIVERED, READ, SENT,
}

/** Fixed vocabulary plus internal identifiers, which are hashed before writing any trace. */
data class DiagnosticTrace(
    val stage: DiagnosticStage,
    val outcome: DiagnosticOutcome,
    val run: UUID? = null,
    val childId: UUID? = null,
    val accountId: UUID? = null,
    val caseId: UUID? = null,
    val recordKey: String? = null,
    val notificationId: UUID? = null,
    val provider: SchoolProvider? = null,
    val kind: SchoolRecordKind? = null,
    val count: Int? = null,
    val changed: Int? = null,
    val failed: Int? = null,
    val complete: Boolean? = null,
    val period: LocalDate? = null,
    val weekly: Boolean? = null,
    val recordKeys: List<String>? = null,
)

/** Durable private diagnostics, independent of the business transaction; failures never block ingestion. */
@Service
class IntegrationDiagnostics(
    private val audit: AuditEntryRepository,
    transactions: PlatformTransactionManager,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = ObjectMapper()
    private val transaction = TransactionTemplate(transactions).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
    private val daily = ConcurrentHashMap<String, LocalDate>()
    private val snapshots = ConcurrentHashMap<String, Pair<LocalDate, String>>()

    fun record(trace: DiagnosticTrace) {
        val detail = json.writeValueAsString(linkedMapOf(
            "version" to 1, "outcome" to trace.outcome.name, "run" to trace.run?.toString(),
            "childRef" to ref(trace.childId), "accountRef" to ref(trace.accountId),
            "caseRef" to ref(trace.caseId), "recordRef" to ref(trace.recordKey),
            "notificationRef" to ref(trace.notificationId), "provider" to trace.provider?.name,
            "kind" to trace.kind?.name, "count" to trace.count, "changed" to trace.changed,
            "failed" to trace.failed, "complete" to trace.complete, "period" to trace.period?.toString(),
            "weekly" to trace.weekly, "recordRefs" to trace.recordKeys?.mapNotNull { ref(it) }?.sorted(),
        ).filterValues { it != null })
        log.info("Integration diagnostic stage={} {}", trace.stage, detail)
        try {
            transaction.executeWithoutResult {
                audit.saveAndFlush(AuditEntry(action = "DIAGNOSTIC_${trace.stage}", detail = detail, createdAt = clock.instant()))
            }
        } catch (e: Exception) {
            log.warn("Integration diagnostic persistence failed stage={} outcome={} errorType={}",
                trace.stage, trace.outcome, e.javaClass.simpleName)
        }
    }

    /** Scheduler checks run every five minutes; repeated gate decisions need only one entry per day. */
    fun oncePerDay(trace: DiagnosticTrace) {
        val today = clock.instant().atZone(java.time.ZoneId.of("Europe/Berlin")).toLocalDate()
        daily.entries.removeIf { it.value != today }
        val key = DedupKeys.sha256("${trace.stage}:${trace.outcome}:${trace.accountId}:${trace.childId}:${trace.recordKey}:${trace.caseId}:${trace.notificationId}:${trace.period}:${trace.weekly}:${trace.recordKeys?.sorted()}:${trace.complete}")
        if (daily.put(key, today) != today) record(trace)
    }

    /** Record A → B → A transitions too, even when both sets have already appeared today. */
    fun snapshot(trace: DiagnosticTrace) {
        val today = clock.instant().atZone(java.time.ZoneId.of("Europe/Berlin")).toLocalDate()
        snapshots.entries.removeIf { it.value.first != today }
        val key = "${trace.childId}:${trace.provider}:${trace.kind}"
        val state = today to DedupKeys.sha256("${trace.outcome}:${trace.complete}:${trace.recordKeys?.sorted()}")
        if (snapshots.put(key, state) != state) record(trace)
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "Europe/Berlin")
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent::class)
    fun prune() {
        try {
            transaction.executeWithoutResult { audit.deleteDiagnosticsBefore(clock.instant().minus(Duration.ofDays(60))) }
        } catch (e: Exception) {
            log.warn("Integration diagnostic retention failed errorType={}", e.javaClass.simpleName)
        }
    }

    private fun ref(value: Any?): String? = value?.let { DedupKeys.sha256("diagnostic:$it") }
}
