package de.schoolagent.whatsapp

import de.schoolagent.connectors.school.SchoolRecordKind
import de.schoolagent.connectors.school.TimetableChanges
import de.schoolagent.domain.enums.EventType
import de.schoolagent.domain.model.SchoolEvent
import de.schoolagent.domain.model.StoredSchoolRecord
import de.schoolagent.domain.repo.CaseChangeRepository
import de.schoolagent.domain.repo.SchoolRecordRepository
import de.schoolagent.domain.repo.SourceRepository
import org.springframework.stereotype.Service
import java.util.UUID

/** The same rule applies to new alerts, durable retries and daily/weekly plans. */
@Service
class TimetableNoticePolicy(
    private val records: SchoolRecordRepository,
    private val sources: SourceRepository,
    private val changes: CaseChangeRepository,
) {
    fun permitsCase(caseId: UUID): Boolean {
        val current = records.findCurrentByCaseId(caseId)
        if (current.isEmpty() || current.any { it.kind != SchoolRecordKind.TIMETABLE_CHANGE }) return true
        // A merged letter or other evidence can carry unrelated instructions: never silence it.
        if (sources.findByCaseId(caseId).any { s -> current.none { it.sourceId == s.id } }) return true
        return current.any { significant(it, caseId) }
    }
    fun permitsEvent(event: SchoolEvent): Boolean {
        if (event.eventType != EventType.TIMETABLE_CHANGE) return true
        val record = records.findCurrentByCaseId(event.caseId).firstOrNull { "evt:${it.recordKey}" == event.dedupKey }
            ?: return false
        return significant(record, event.caseId)
    }
    private fun significant(record: StoredSchoolRecord, caseId: UUID): Boolean {
        // A later staffing edit must not suppress an already queued cancellation or remove
        // a still-relevant subject change from plans. Follow only this record's own history.
        val history = changes.findByCaseIdOrderByCreatedAtAsc(caseId)
        var payload = record.payload
        repeat(history.size + 1) {
            val before = history.lastOrNull { it.afterJson == payload }?.beforeJson
            if (TimetableChanges.significant(payload, before)) return true
            payload = before ?: return false
        }
        return false
    }
}
