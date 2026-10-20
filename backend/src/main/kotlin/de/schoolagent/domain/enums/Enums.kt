package de.schoolagent.domain.enums

/** Case lifecycle, spec §8. Transition rules in CaseStateMachine. */
enum class CaseStatus {
    NEW, INGESTING, INVESTIGATING, READY_FOR_REASONING,
    ACTION_REQUIRED, WAITING_FOR_PARENT, INFORMATION_ONLY,
    COMPLETED, CANCELLED, FAILED, NEEDS_REVIEW,
}

enum class CaseCategory { TRIP, EVENT, FORM, PAYMENT, INFO, TIMETABLE, EXAM, MATERIAL, MEETING, OTHER }

/** Notification salience, spec §26. Derived from the 0–5 parent-facing score. */
enum class Importance { SILENT, DIGEST, IMMEDIATE }

fun importanceFromScore(score: Int): Importance = when {
    score >= 4 -> Importance.IMMEDIATE
    score >= 2 -> Importance.DIGEST
    else -> Importance.SILENT
}

fun scoreFromImportance(importance: Importance): Int = when (importance) {
    Importance.IMMEDIATE -> 5
    Importance.SILENT -> 1
    Importance.DIGEST -> 3
}

fun clampImportanceScore(raw: Int): Int = raw.coerceIn(0, 5)

enum class SourceType {
    EMAIL, PORTAL_MESSAGE, PORTAL_LETTER, SCHULMANAGER_LETTER, ELTERNPORTAL_RECORD, SCHULMANAGER_RECORD, ATTACHMENT,
    WEBPAGE, WEBPAGE_JS, MANUAL_UPLOAD, URL_SUBMISSION,
}

enum class FetchStatus { PENDING, FETCHED, FAILED }

/** Failure classification, spec §40. */
enum class FailureCategory { TRANSIENT, AUTHENTICATION, CONTENT, INVESTIGATION, AI }

/** Distilled evidence kinds produced by the extraction model, spec §16. */
enum class EvidenceKind {
    FACT, DEADLINE, OBLIGATION_PARENT, OBLIGATION_CHILD,
    COST, CONSENT, LINK, SCHEDULE, CANCELLATION, REQUIREMENT,
}

enum class InvestigationStatus { RUNNING, COMPLETE, INCOMPLETE, FAILED }

enum class StepType { FETCH_URL, PARSE_DOCUMENT, BROWSER_RENDER, EXTRACT, LINK_DECISION }

enum class StepOutcome { OK, SKIPPED, FAILED }

/** ParentAction statuses, spec §20. */
enum class ActionStatus { OPEN, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED, OVERDUE }

/** ChildRequirement statuses, spec §21. */
enum class RequirementStatus { OPEN, DONE, CANCELLED }

/** External form accessibility as determined by investigation, spec §12. */
enum class FormAccessStatus { ACCESSIBLE, LOGIN_REQUIRED, UNREACHABLE, UNSUPPORTED }

/** SchoolEvent types, spec §22/§28. */
enum class EventType {
    SCHOOL_EVENT, DEADLINE, EXAM, PARENT_EVENT,
    BRING_ITEM, PAYMENT, TIMETABLE_CHANGE, MUSIC_EVENT, SCHOOL_REPORT;

    /** Reports and change notices describe school activity; they are not appointments. */
    fun isCalendarAppointment() = this != SCHOOL_REPORT && this != TIMETABLE_CHANGE
}

enum class EventStatus { PLANNED, CONFIRMED, CANCELLED, MOVED }

enum class GoogleSyncStatus { PENDING, SYNCED, ERROR, NOT_APPLICABLE }

enum class NotificationLevel { IMMEDIATE, DIGEST, SILENT }

enum class NotificationChannel { WHATSAPP, DIGEST, FRONTEND }

enum class NotificationStatus { QUEUED, ACCEPTED, SENT, DELIVERED, READ, FAILED, UNKNOWN, SKIPPED }

/** CaseChange mutation types, spec §23. */
enum class ChangeType { ADD, MOVE, REMOVE, CHANGE, CANCEL, REOPEN, STATUS }

enum class ChangeTargetType { EVENT, ACTION, REQUIREMENT, DEADLINE, SUMMARY, CASE }

/** Who or what caused an audited action. */
enum class Actor { SYSTEM, AI, MANUAL }

/** Connectors tracked by health monitoring, spec §30. */
enum class ConnectorType { GMAIL, ELTERNPORTAL, SCHULMANAGER, CALENDAR, WHATSAPP, PLAYWRIGHT, AI }

enum class HealthStatus { OK, DEGRADED, DOWN, UNKNOWN }

/** ModelRouter roles: one text model, one vision model. */
enum class ModelRole { TEXT, VISION }
