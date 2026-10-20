package de.schoolagent.connectors.school

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Native records carry authoritative dates; providers never hand general school calendars to ingestion. */
enum class SchoolRecordKind(val label: String) {
    EXAM("Exams"), FORGOTTEN_HOMEWORK("Forgotten homework"), FORGOTTEN_MATERIAL("Forgotten materials"), PAYMENT("Payments"),
    HOMEWORK("Homework assignments"), TIMETABLE_CHANGE("Timetable changes"),
}
enum class SchoolFeedStatus { AVAILABLE, UNAVAILABLE, FAILED }
data class SchoolStudentScope(val name: String, val className: String?, val studentId: String?)
data class SchoolRecord(
    val id: String, val kind: SchoolRecordKind, val title: String, val text: String,
    val date: LocalDate? = null, val time: LocalTime? = null, val endTime: LocalTime? = null,
    val dueDate: LocalDate? = null, val publishedAt: Instant? = null, val url: String,
    val payment: SchoolPayment? = null,
    val timetable: SchoolTimetableChange? = null,
)
/** A change notice remains visible even when the underlying lesson is cancelled. */
data class SchoolTimetableChange(val cancelled: Boolean, val subject: String?, val room: String?, val period: String, val changed: Boolean = true)
object SchoolHomework {
    fun observed(id: String, subject: String, text: String, recordedOn: LocalDate, url: String): SchoolRecord {
        val explicit = Regex("(?i)(?:\\bbis\\b|Abgabe(?:termin)?|fällig|\\bdue\\b)\\s*(?:am|on|by)?\\s*:?\\s*(\\d{2}\\.\\d{2}\\.\\d{4}|\\d{4}-\\d{2}-\\d{2})\\b")
            .find(text)?.groupValues?.get(1)
        val due = explicit?.let { raw -> runCatching {
            if (raw.contains('.')) LocalDate.parse(raw, java.time.format.DateTimeFormatter.ofPattern("dd.MM.uuuu")
                .withResolverStyle(java.time.format.ResolverStyle.STRICT)) else LocalDate.parse(raw)
        }.getOrNull() }
        return SchoolRecord(id, SchoolRecordKind.HOMEWORK, subject, text, date = recordedOn, dueDate = due, url = url)
    }
}
enum class SchoolPaymentStatus { OUTSTANDING, PARTIAL, SETTLED, CANCELLED, UNKNOWN }
/** Integer minor units. Provider observations never imply that School Agent made a payment. */
data class SchoolPayment(
    val currency: String,
    val totalMinor: Long?,
    val paidMinor: Long?,
    val status: SchoolPaymentStatus,
) {
    val remainingMinor: Long? get() = if (totalMinor != null && paidMinor != null) totalMinor - paidMinor else null
    companion object {
        fun observed(total: String?, paid: String?, currency: String = "EUR"): SchoolPayment {
            fun amount(raw: String?): Long? = runCatching {
                raw?.takeIf { it.matches(Regex("\\d+(?:\\.\\d{1,2})?")) }
                    ?.toBigDecimal()?.movePointRight(2)?.longValueExact()
            }.getOrNull()
            val t = amount(total); val p = amount(paid)
            val status = when {
                currency != "EUR" || t == null || p == null || p > t -> SchoolPaymentStatus.UNKNOWN
                p == t -> SchoolPaymentStatus.SETTLED
                p > 0 -> SchoolPaymentStatus.PARTIAL
                else -> SchoolPaymentStatus.OUTSTANDING
            }
            return SchoolPayment(currency, t, p, status)
        }
    }
}
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
