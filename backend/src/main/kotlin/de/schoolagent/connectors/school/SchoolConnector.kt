package de.schoolagent.connectors.school

import de.schoolagent.domain.enums.ConnectorType
import de.schoolagent.domain.enums.SourceType
import java.time.Instant

/** Providers normalize school notices here; ingestion and reasoning never see provider payloads. */
enum class SchoolProvider(val label: String, val sourceType: SourceType, val healthType: ConnectorType) {
    ELTERNPORTAL("Elternportal", SourceType.PORTAL_LETTER, ConnectorType.ELTERNPORTAL),
    SCHULMANAGER("Schulmanager Online", SourceType.SCHULMANAGER_LETTER, ConnectorType.SCHULMANAGER),
}

data class SchoolCredentials(
    val provider: SchoolProvider,
    val url: String,
    val username: String,
    val password: String,
    val studentId: String? = null,
    val institutionId: String? = null,
    val allowReceiptDownloads: Boolean = false,
    val shareConnectedMessages: Boolean = false,
)

data class SchoolStudent(val id: String, val name: String, val className: String? = null)
data class SchoolIdentity(val institutionId: String?, val schoolName: String?, val students: List<SchoolStudent>)
enum class SchoolNoticeKind { LETTER, MESSAGE, NOTICEBOARD }
data class SchoolNotice(val id: String, val title: String, val receivedAt: Instant?, val url: String?,
                        val changeMarker: String? = null, val kind: SchoolNoticeKind = SchoolNoticeKind.LETTER)
data class SchoolNoticeFeed(val kind: SchoolNoticeKind, val status: SchoolFeedStatus,
                           val notices: List<SchoolNotice> = emptyList(), val message: String? = null)
data class SchoolDocument(val id: String, val title: String, val mimeType: String, val bytes: ByteArray, val sentAt: Instant? = null) {
    companion object {
        // Originals can exceed the extraction budget; those remain available for manual review.
        const val MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024
    }
}
data class SchoolResponse(
    val receipt: String = "UNKNOWN",
    val response: String = "UNKNOWN",
    val deadline: Instant? = null,
    val questionIds: List<String> = emptyList(),
    val answeredQuestions: Int? = null,
    val deadlineAllDay: Boolean = false,
)
data class SchoolNoticeDetails(val documents: List<SchoolDocument>, val response: SchoolResponse? = null)

interface SchoolSession : AutoCloseable {
    val identity: SchoolIdentity
    /** Bind the fresh session before reading either letters or native records. */
    fun selectStudent(scope: SchoolStudentScope): SchoolStudent? = null
    fun records(scope: SchoolStudentScope, from: java.time.LocalDate, to: java.time.LocalDate): List<SchoolRecordFeed> = emptyList()
    fun notices(studentId: String?): List<SchoolNotice>
    fun noticeFeeds(studentId: String?): List<SchoolNoticeFeed> = listOf(
        try { SchoolNoticeFeed(SchoolNoticeKind.LETTER, SchoolFeedStatus.AVAILABLE, notices(studentId)) }
        catch (_: Exception) { SchoolNoticeFeed(SchoolNoticeKind.LETTER, SchoolFeedStatus.FAILED,
            message = "Could not read school letters; existing documents have been kept") })
    fun documents(notice: SchoolNotice, studentId: String?): List<SchoolDocument>
    /** True only where the read path is separate from school receipt/answer mutations. */
    val canRefreshDocuments: Boolean get() = false
    val canAcquireDocuments: Boolean get() = true
    fun canAcquire(notice: SchoolNotice) = notice.kind != SchoolNoticeKind.LETTER || canAcquireDocuments
    fun canRefresh(notice: SchoolNotice) = notice.kind != SchoolNoticeKind.LETTER || canRefreshDocuments
    fun details(notice: SchoolNotice, studentId: String?): SchoolNoticeDetails = SchoolNoticeDetails(documents(notice, studentId))
    override fun close() {}
}

interface SchoolConnector {
    val provider: SchoolProvider
    /** A fresh session per operation isolates children and concurrent credential tests. */
    fun connect(credentials: SchoolCredentials): SchoolSession
}

class SchoolConnectionException(message: String) : RuntimeException(message)
