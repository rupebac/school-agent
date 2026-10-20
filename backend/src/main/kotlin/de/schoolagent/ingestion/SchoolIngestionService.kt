package de.schoolagent.ingestion

import de.schoolagent.connectors.school.*
import de.schoolagent.diagnostics.*
import de.schoolagent.domain.enums.*
import de.schoolagent.domain.repo.ChildRepository
import de.schoolagent.health.HealthTracker
import de.schoolagent.reasoning.CaseReasoningService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

@Service
class SchoolIngestionService(
    connectors: List<SchoolConnector>,
    private val config: SchoolConnectionService,
    private val kids: ChildRepository,
    private val investigation: InvestigationService,
    private val reasoning: CaseReasoningService,
    private val health: HealthTracker,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val nativeRecords: SchoolRecordIngestionService? = null,
    private val diagnostics: IntegrationDiagnostics? = null,
    private val noticeIngestion: SchoolNoticeIngestionService,
    private val accountLock: SchoolAccountLock,
) {
    private val connectors = connectors.associateBy { it.provider }
    private val log = LoggerFactory.getLogger(javaClass)
    data class SyncResult(val briefsSeen: Int, val ingested: Int, val failed: Int = 0, val recordsSeen: Int = 0, val recordsChanged: Int = 0)
    data class ConnectionTest(val briefsFound: Int, val identity: SchoolIdentity)

    fun discover(credentials: SchoolCredentials): SchoolIdentity = withAccount(credentials) { connector(credentials).connect(credentials).use { it.identity } }
    fun testConnection(childId: UUID): ConnectionTest {
        val credentials = config.credentials(childId) ?: throw SchoolConnectionException("School portal not configured")
        return withAccount(credentials) { connector(credentials).connect(credentials).use {
            val child = kids.findById(childId).orElseThrow { SchoolConnectionException("Child no longer exists") }
            it.selectStudent(SchoolStudentScope(child.name, child.className, credentials.studentId))
            ConnectionTest(it.notices(credentials.studentId).size, it.identity)
        } }
    }

    fun syncOnce(childId: UUID): SyncResult {
        val run = UUID.randomUUID()
        val original = config.credentials(childId) ?: run {
            diagnostics?.record(DiagnosticTrace(DiagnosticStage.SCHOOL_SYNC, DiagnosticOutcome.NOT_CONFIGURED, run, childId))
            throw SchoolConnectionException("School portal not configured")
        }
        diagnostics?.record(DiagnosticTrace(DiagnosticStage.SCHOOL_SYNC, DiagnosticOutcome.STARTED, run, childId, provider = original.provider))
        return withAccount(original) {
            try {
                connector(original).connect(original).use { session ->
                    val child = kids.findById(childId).orElseThrow { SchoolConnectionException("Child no longer exists") }
                    val requested = SchoolStudentScope(child.name, child.className, original.studentId)
                    val selected = session.selectStudent(requested)
                    val scope = requested.copy(studentId = selected?.id ?: requested.studentId)
                    val initialImport = config.status(childId).lastSync == null
                    val credentials = if (original.provider == SchoolProvider.ELTERNPORTAL && selected != null) {
                        transactions.executeWithoutResult {
                            kids.lockById(childId) ?: throw SchoolConnectionException("Child no longer exists")
                            if (config.credentials(childId) != original) throw SchoolConnectionException("School connection changed during sync; retry")
                            config.bindStudent(childId, selected)
                        }
                        original.copy(studentId = selected.id)
                    } else original
                    var ingested = 0
                    var lettersFailed = 0
                    var noticesFailed = 0
                    var noticesSeen = 0
                    var newNoticeBacklog = 0
                    val noticeFeeds = session.noticeFeeds(scope.studentId).map { feed ->
                        noticesSeen += feed.notices.size
                        if (feed.status != SchoolFeedStatus.AVAILABLE) {
                            if (feed.status == SchoolFeedStatus.FAILED) {
                                noticesFailed++
                                if (feed.kind == SchoolNoticeKind.LETTER) lettersFailed++
                            }
                            feed
                        } else {
                            val baseline = de.schoolagent.common.DedupKeys.sha256("${credentials.provider}|${credentials.url}|${credentials.username}|${scope.studentId}|${feed.kind}")
                            val initial = initialImport || (feed.kind != SchoolNoticeKind.LETTER && !config.hasNoticeBaseline(childId, baseline))
                            val result = noticeIngestion.sync(childId, credentials, session, feed.notices, initial)
                            ingested += result.changed
                            noticesFailed += result.failed
                            if (feed.kind == SchoolNoticeKind.LETTER) {
                                lettersFailed += result.failed
                                newNoticeBacklog += result.newPending
                            }
                            if (result.failed > 0) feed.copy(status = SchoolFeedStatus.FAILED,
                                message = if (!session.canAcquireDocuments && feed.kind == SchoolNoticeKind.LETTER)
                                    "PDF downloads require automatic receipt permission in the connection settings"
                                else "Some documents could not be checked; previous versions are retained and the check will retry")
                            else {
                                transactions.executeWithoutResult {
                                    kids.lockById(childId) ?: throw SchoolConnectionException("Child no longer exists")
                                    if (config.credentials(childId) != credentials) throw SchoolConnectionException("School connection changed during sync; retry")
                                    if (result.newPending == 0) config.recordNoticeBaseline(childId, baseline)
                                }
                                if (result.pending > 0) feed.copy(message = "Content checks queued: ${result.pending}. Each case shows its last successful content check.")
                                else feed
                            }
                        }
                    }
                    transactions.executeWithoutResult {
                        kids.lockById(childId) ?: throw SchoolConnectionException("Child no longer exists")
                        if (config.credentials(childId) == credentials) config.recordNoticeSections(childId, noticeFeeds)
                    }
                    // Letters establish connection health and their own initial-import boundary.
                    // Optional sections can never leave new-letter notifications permanently suppressed.
                    if (lettersFailed == 0) {
                        transactions.executeWithoutResult {
                            kids.lockById(childId) ?: throw SchoolConnectionException("Child no longer exists")
                            if (config.credentials(childId) != credentials) throw SchoolConnectionException("School connection changed during sync; retry")
                            if (newNoticeBacklog == 0) config.recordSync(childId)
                        }
                        health.success(credentials.provider.healthType)
                    } else health.failure(credentials.provider.healthType, "$lettersFailed school letters could not be imported; they will be retried")
                    val today = clock.instant().atZone(java.time.ZoneId.of("Europe/Berlin")).toLocalDate()
                    val to = today.plusMonths(18)
                    var feeds = try { session.records(scope, today, to) } catch (e: Exception) {
                        log.warn("Optional school sections failed run={} errorType={}", run, e.javaClass.simpleName)
                        SchoolRecordKind.entries.map { SchoolRecordFeed.failed(it) }
                    }
                    val nativeResult = try { nativeRecords?.sync(childId, credentials, scope, feeds, today, to, initialImport, run) }
                    catch (e: Exception) {
                        log.warn("Optional school record import failed run={} errorType={}", run, e.javaClass.simpleName)
                        feeds = feeds.map { if (it.status == SchoolFeedStatus.AVAILABLE) it.copy(status = SchoolFeedStatus.FAILED, complete = false,
                            message = "Record import failed; existing data has been kept and this section will retry") else it }
                        null
                    }
                    feeds = nativeResult?.feeds ?: feeds
                    feeds.forEach { feed -> diagnostics?.record(DiagnosticTrace(DiagnosticStage.SCHOOL_FEED,
                        when (feed.status) { SchoolFeedStatus.AVAILABLE -> DiagnosticOutcome.AVAILABLE
                            SchoolFeedStatus.UNAVAILABLE -> DiagnosticOutcome.UNAVAILABLE; SchoolFeedStatus.FAILED -> DiagnosticOutcome.FAILED },
                        run, childId, provider = credentials.provider, kind = feed.kind, count = feed.records.size, complete = feed.complete)) }
                    transactions.executeWithoutResult {
                        kids.lockById(childId) ?: throw SchoolConnectionException("Child no longer exists")
                        if (config.credentials(childId) == credentials) config.recordSections(childId, feeds)
                    }
                    SyncResult(noticesSeen, ingested, noticesFailed + feeds.count { it.status == SchoolFeedStatus.FAILED }, nativeResult?.seen ?: 0, nativeResult?.changed ?: 0).also {
                        diagnostics?.record(DiagnosticTrace(DiagnosticStage.SCHOOL_SYNC, DiagnosticOutcome.COMPLETED, run, childId,
                            provider = credentials.provider, count = it.recordsSeen, changed = it.recordsChanged, failed = it.failed))
                    }
                }
            } catch (e: Exception) {
                diagnostics?.record(DiagnosticTrace(DiagnosticStage.SCHOOL_SYNC, DiagnosticOutcome.FAILED, run, childId, provider = original.provider))
                log.warn("School sync failed run={} errorType={}", run, e.javaClass.simpleName)
                health.failure(original.provider.healthType, "School connection failed; test it in Settings")
                throw e
            }
        }
    }

    /** The portal may keep viewing context at account level; serialize siblings and manual tests too. */
    private fun <T> withAccount(credentials: SchoolCredentials, work: () -> T): T = accountLock.withAccount(credentials, work)

    fun syncAndProcess(childId: UUID): SyncResult {
        val result = syncOnce(childId)
        investigation.investigateAllPending()
        reasoning.reasonAllPending()
        return result
    }

    private fun connector(credentials: SchoolCredentials) = connectors.getValue(credentials.provider)
}
