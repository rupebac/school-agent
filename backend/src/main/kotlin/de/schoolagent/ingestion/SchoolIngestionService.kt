package de.schoolagent.ingestion

import de.schoolagent.common.DedupKeys
import de.schoolagent.connectors.school.*
import de.schoolagent.documents.DocumentStore
import de.schoolagent.domain.enums.*
import de.schoolagent.domain.model.Source
import de.schoolagent.domain.repo.ChildRepository
import de.schoolagent.domain.repo.SourceRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.service.CaseService
import de.schoolagent.health.HealthTracker
import de.schoolagent.reasoning.CaseReasoningService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class SchoolIngestionService(
    connectors: List<SchoolConnector>,
    private val config: SchoolConnectionService,
    private val documents: DocumentStore,
    private val caseService: CaseService,
    private val sources: SourceRepository,
    private val cases: SchoolCaseRepository,
    private val kids: ChildRepository,
    private val investigation: InvestigationService,
    private val reasoning: CaseReasoningService,
    private val health: HealthTracker,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val nativeRecords: SchoolRecordIngestionService? = null,
) {
    private val connectors = connectors.associateBy { it.provider }
    private val locks = ConcurrentHashMap<String, Any>()
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
        val original = config.credentials(childId) ?: throw SchoolConnectionException("School portal not configured")
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
                    val notices = session.notices(scope.studentId)
                    var ingested = 0
                    var lettersFailed = 0
                    for (notice in notices) {
                        val key = sourceKey(credentials, childId, notice.id)
                        if (alreadyImported(credentials, childId, notice.id, key)) continue
                        try {
                            val parts = session.documents(notice, scope.studentId)
                            require(parts.isNotEmpty()) { "School notice contains no documents" }
                            transactions.executeWithoutResult {
                                kids.lockById(childId) ?: throw SchoolConnectionException("Child no longer exists")
                                if (config.credentials(childId) != credentials) throw SchoolConnectionException("School connection changed during sync; retry")
                                if (!alreadyImported(credentials, childId, notice.id, key)) {
                                    ingest(credentials, childId, notice, parts, key, initialImport)
                                    ingested++
                                }
                            }
                        } catch (e: Exception) {
                            lettersFailed++
                            log.warn("School import failed for child {} notice {} ({})", childId, notice.id, e.javaClass.simpleName)
                        }
                    }
                    // Letters establish connection health and their own initial-import boundary.
                    // Optional sections can never leave new-letter notifications permanently suppressed.
                    if (lettersFailed == 0) {
                        transactions.executeWithoutResult {
                            kids.lockById(childId) ?: throw SchoolConnectionException("Child no longer exists")
                            if (config.credentials(childId) != credentials) throw SchoolConnectionException("School connection changed during sync; retry")
                            config.recordSync(childId)
                        }
                        health.success(credentials.provider.healthType)
                    } else health.failure(credentials.provider.healthType, "$lettersFailed school letters could not be imported; they will be retried")
                    val today = clock.instant().atZone(java.time.ZoneId.of("Europe/Berlin")).toLocalDate()
                    val to = today.plusMonths(18)
                    var feeds = try { session.records(scope, today, to) } catch (e: Exception) {
                        log.warn("Optional school sections failed for child {} ({})", childId, e.javaClass.simpleName)
                        SchoolRecordKind.entries.map { SchoolRecordFeed.failed(it) }
                    }
                    val nativeResult = try { nativeRecords?.sync(childId, credentials, scope, feeds, today, to, initialImport) }
                    catch (e: Exception) {
                        log.warn("Optional school record import failed for child {} ({})", childId, e.javaClass.simpleName)
                        feeds = feeds.map { if (it.status == SchoolFeedStatus.AVAILABLE) it.copy(status = SchoolFeedStatus.FAILED, complete = false,
                            message = "Record import failed; existing data has been kept and this section will retry") else it }
                        null
                    }
                    feeds = nativeResult?.feeds ?: feeds
                    transactions.executeWithoutResult {
                        kids.lockById(childId) ?: throw SchoolConnectionException("Child no longer exists")
                        if (config.credentials(childId) == credentials) config.recordSections(childId, feeds)
                    }
                    SyncResult(notices.size, ingested, lettersFailed + feeds.count { it.status == SchoolFeedStatus.FAILED }, nativeResult?.seen ?: 0, nativeResult?.changed ?: 0)
                }
            } catch (e: Exception) {
                health.failure(original.provider.healthType, "School connection failed; test it in Settings")
                throw e
            }
        }
    }

    /** The portal may keep viewing context at account level; serialize siblings and manual tests too. */
    private fun <T> withAccount(credentials: SchoolCredentials, work: () -> T): T = synchronized(locks.computeIfAbsent(
        DedupKeys.sha256("${credentials.provider}|${credentials.url.trimEnd('/')}|${credentials.username.trim().lowercase()}|${credentials.institutionId.orEmpty()}")
    ) { Any() }) { work() }

    fun syncAndProcess(childId: UUID): SyncResult {
        val result = syncOnce(childId)
        investigation.investigateAllPending()
        reasoning.reasonAllPending()
        return result
    }

    private fun alreadyImported(credentials: SchoolCredentials, childId: UUID, id: String, key: String): Boolean {
        if (sources.findByDedupKey(key)?.fetchStatus == FetchStatus.FETCHED) return true
        if (credentials.provider != SchoolProvider.ELTERNPORTAL) return false
        val legacy = sources.findByDedupKey(DedupKeys.source(SourceType.PORTAL_LETTER, id)) ?: return false
        return legacy.fetchStatus == FetchStatus.FETCHED && legacy.caseId?.let { cases.findById(it).orElse(null)?.childId == childId } == true
    }

    private fun ingest(credentials: SchoolCredentials, childId: UUID, notice: SchoolNotice, parts: List<SchoolDocument>, key: String, initialImport: Boolean) {
        sources.findByDedupKey(key)?.let { sources.delete(it); sources.flush() }
        val schoolCase = caseService.createCase(childId, notice.title.take(200))
        schoolCase.suppressInitialNotification = initialImport
        cases.save(schoolCase)
        for ((index, part) in parts.withIndex()) {
            val hash = documents.put(part.bytes)
            val source = sources.save(Source(
                sourceType = if (index == 0) credentials.provider.sourceType else SourceType.ATTACHMENT,
                externalId = if (index == 0) notice.id else "${notice.id}:${part.id}",
                dedupKey = if (index == 0) key else DedupKeys.source(SourceType.ATTACHMENT, "$key:${part.id}"),
                contentHash = hash, title = part.title, receivedAt = notice.receivedAt, originalSentAt = part.sentAt,
                canonicalUrl = notice.url, storageRef = hash, mimeType = part.mimeType,
                byteSize = part.bytes.size.toLong(), fetchStatus = FetchStatus.FETCHED,
                createdAt = clock.instant(), updatedAt = clock.instant(),
            ))
            caseService.attachSource(schoolCase, source)
        }
        caseService.transition(schoolCase, CaseStatus.INGESTING, Actor.SYSTEM, "${credentials.provider.label} letter imported")
    }

    private fun connector(credentials: SchoolCredentials) = connectors.getValue(credentials.provider)
    private fun sourceKey(credentials: SchoolCredentials, childId: UUID, id: String): String =
        DedupKeys.source(credentials.provider.sourceType, if (credentials.provider == SchoolProvider.ELTERNPORTAL) "$childId:$id"
            else "$childId:${credentials.institutionId}:${credentials.studentId}:$id")
}
