package de.schoolagent.whatsapp

import de.schoolagent.domain.enums.*
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.repo.*
import de.schoolagent.inbox.CaseRelevanceService
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Instant alerts announce useful changes; they are not a replay of overdue obligations.
 * Re-evaluated before composing and immediately before delivery, including school corrections.
 * This never completes or hides the underlying family task.
 */
@Service
class InstantNoticePolicy(
    private val actions: ParentActionRepository,
    private val events: SchoolEventRepository,
    private val requirements: ChildRequirementRepository,
    private val relevance: CaseRelevanceService,
    private val clock: Clock,
    private val timetable: TimetableNoticePolicy,
) {
    private val berlin = ZoneId.of("Europe/Berlin")
    fun eligible(c: SchoolCase, schoolChange: Boolean = false): Boolean {
        if (c.suppressInitialNotification || relevance.isArchived(c) || !timetable.permitsCase(c.id)) return false
        val deadlines = mutableListOf<Instant>()
        if (!c.isInfo()) {
            val open = actions.findByCaseId(c.id).filter { it.status !in setOf(ActionStatus.COMPLETED, ActionStatus.CANCELLED) }
            val needs = requirements.findByCaseId(c.id).filter { it.required && it.status == RequirementStatus.OPEN }
            if (open.any { it.dueAt == null } || needs.any { it.relevantOn == null }) return true
            deadlines += open.mapNotNull { it.dueAt?.let(::endOfDay) }
            deadlines += needs.mapNotNull { it.relevantOn?.plusDays(1)?.atStartOfDay(berlin)?.toInstant() }
        }
        // A cancellation still matters before its original date, even when its case is closed.
        deadlines += events.findByCaseId(c.id).filter {
            (schoolChange || it.status != EventStatus.CANCELLED) && it.eventType != EventType.SCHOOL_REPORT
        }.map { if (it.allDay || it.startTime.atZone(berlin).toLocalTime() == java.time.LocalTime.MIDNIGHT)
            endOfDay(it.endTime ?: it.startTime) else it.startTime }
        return deadlines.isEmpty() || deadlines.any { it.isAfter(clock.instant()) }
    }
    private fun endOfDay(time: Instant) = time.atZone(berlin).toLocalDate().plusDays(1).atStartOfDay(berlin).toInstant()
}
