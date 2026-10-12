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
)

data class SchoolStudent(val id: String, val name: String, val className: String? = null)
data class SchoolIdentity(val institutionId: String?, val schoolName: String?, val students: List<SchoolStudent>)
data class SchoolNotice(val id: String, val title: String, val receivedAt: Instant?, val url: String?)
data class SchoolDocument(val id: String, val title: String, val mimeType: String, val bytes: ByteArray, val sentAt: Instant? = null)

interface SchoolSession : AutoCloseable {
    val identity: SchoolIdentity
    /** Bind the fresh session before reading either letters or native records. */
    fun selectStudent(scope: SchoolStudentScope): SchoolStudent? = null
    fun records(scope: SchoolStudentScope, from: java.time.LocalDate, to: java.time.LocalDate): List<SchoolRecordFeed> = emptyList()
    fun notices(studentId: String?): List<SchoolNotice>
    fun documents(notice: SchoolNotice, studentId: String?): List<SchoolDocument>
    override fun close() {}
}

interface SchoolConnector {
    val provider: SchoolProvider
    /** A fresh session per operation isolates children and concurrent credential tests. */
    fun connect(credentials: SchoolCredentials): SchoolSession
}

class SchoolConnectionException(message: String) : RuntimeException(message)
