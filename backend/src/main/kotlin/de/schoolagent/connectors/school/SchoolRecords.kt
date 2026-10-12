package de.schoolagent.connectors.school

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Native records carry authoritative dates; providers never hand general school calendars to ingestion. */
enum class SchoolRecordKind(val label: String) {
    EXAM("Exams"), FORGOTTEN_HOMEWORK("Forgotten homework"), FORGOTTEN_MATERIAL("Forgotten materials"), PAYMENT("Payments"),
}
enum class SchoolFeedStatus { AVAILABLE, UNAVAILABLE, FAILED }
data class SchoolStudentScope(val name: String, val className: String?, val studentId: String?)
data class SchoolRecord(
    val id: String, val kind: SchoolRecordKind, val title: String, val text: String,
    val date: LocalDate? = null, val time: LocalTime? = null, val endTime: LocalTime? = null,
    val dueDate: LocalDate? = null, val publishedAt: Instant? = null, val url: String,
)
data class SchoolRecordFeed(
    val kind: SchoolRecordKind, val status: SchoolFeedStatus, val records: List<SchoolRecord> = emptyList(),
    /** Only a verified complete exam list may withdraw previously imported future dates. */
    val complete: Boolean = false, val message: String? = null,
) {
    companion object {
        fun unavailable(kind: SchoolRecordKind, message: String) = SchoolRecordFeed(kind, SchoolFeedStatus.UNAVAILABLE, message = message)
        fun failed(kind: SchoolRecordKind) = SchoolRecordFeed(kind, SchoolFeedStatus.FAILED, message = "Could not read this section; it will be retried")
    }
}
