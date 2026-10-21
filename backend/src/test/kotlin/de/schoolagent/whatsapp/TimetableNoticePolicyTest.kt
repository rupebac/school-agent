package de.schoolagent.whatsapp

import com.fasterxml.jackson.databind.ObjectMapper
import de.schoolagent.connectors.school.*
import de.schoolagent.domain.enums.*
import de.schoolagent.domain.model.*
import de.schoolagent.domain.repo.*
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class TimetableNoticePolicyTest {
    private val json = ObjectMapper()
    private fun payload(subject: String = "Mathematics", day: String = "2026-11-12", period: String = "3",
                        teacher: String = "AAA", room: String = "12", cancelled: Boolean = false,
                        schedule: Boolean = false, changed: Boolean = true, added: Boolean = false) = json.writeValueAsString(mapOf(
        "date" to day, "text" to "Teacher $teacher", "timetable" to SchoolTimetableChange(cancelled, subject, room, period, changed, schedule, added)))
    @Test fun `teacher room wording and normal teacher restoration do not change the schedule`() {
        val before = payload()
        assertThat(TimetableChanges.significant(before, null)).isFalse()
        assertThat(TimetableChanges.significant(payload(teacher = "BBB", room = "14"), before)).isFalse()
        assertThat(TimetableChanges.significant(payload(changed = false), before)).isFalse()
    }
    @Test fun `subject day period cancellation restoration and added lessons remain significant`() {
        val before = payload()
        listOf(payload(subject = "English"), payload(day = "2026-11-13"), payload(period = "4"),
            payload(cancelled = true), payload(added = true)).forEach {
            assertThat(TimetableChanges.significant(it, before)).isTrue()
        }
        assertThat(TimetableChanges.significant(before, payload(cancelled = true))).isTrue()
        assertThat(TimetableChanges.significant(payload(schedule = true), null)).isTrue()
        assertThat(TimetableChanges.significant(payload(cancelled = true), null)).isTrue()
        assertThat(TimetableChanges.significant(payload(cancelled = true, teacher = "BBB"), payload(cancelled = true))).isFalse()
    }
    @Test fun `legacy substitutions stay quiet and a later staffing edit cannot erase a meaningful queued change`() {
        val records = mockk<SchoolRecordRepository>()
        val sources = mockk<SourceRepository>()
        val changes = mockk<CaseChangeRepository>()
        val policy = TimetableNoticePolicy(records, sources, changes)
        val caseId = UUID.randomUUID()
        val old = payload()
        val significant = payload(subject = "English")
        val latest = payload(subject = "English", teacher = "BBB")
        val record = StoredSchoolRecord("school:synthetic", "synthetic", UUID.randomUUID(), SchoolRecordKind.TIMETABLE_CHANGE,
            "synthetic", null, "hash", old, caseId, UUID.randomUUID(), updatedAt = Instant.EPOCH)
        every { records.findCurrentByCaseId(caseId) } returns listOf(record)
        every { sources.findByCaseId(caseId) } returns emptyList()
        every { changes.findByCaseIdOrderByCreatedAtAsc(caseId) } returns emptyList()
        assertThat(policy.permitsCase(caseId)).isFalse()
        record.payload = latest
        every { changes.findByCaseIdOrderByCreatedAtAsc(caseId) } returns listOf(
            CaseChange(caseId = caseId, changeType = ChangeType.CHANGE, targetType = ChangeTargetType.CASE, beforeJson = old, afterJson = significant),
            CaseChange(caseId = caseId, changeType = ChangeType.CHANGE, targetType = ChangeTargetType.CASE, beforeJson = significant, afterJson = latest))
        assertThat(policy.permitsCase(caseId)).isTrue()
        val event = SchoolEvent(caseId = caseId, title = "Changed subject", eventType = EventType.TIMETABLE_CHANGE,
            startTime = Instant.EPOCH, dedupKey = "evt:${record.recordKey}")
        assertThat(policy.permitsEvent(event)).isTrue()
        record.payload = old
        every { sources.findByCaseId(caseId) } returns listOf(Source(sourceType = SourceType.ATTACHMENT,
            externalId = "another-letter", dedupKey = "another-letter", contentHash = "hash"))
        assertThat(policy.permitsCase(caseId)).isTrue()
    }
}
