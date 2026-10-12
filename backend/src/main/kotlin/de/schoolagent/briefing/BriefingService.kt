package de.schoolagent.briefing

import de.schoolagent.calendar.SchoolCalendarService
import de.schoolagent.domain.enums.ActionStatus
import de.schoolagent.domain.enums.EventStatus
import de.schoolagent.domain.enums.RequirementStatus
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.repo.ChildRepository
import de.schoolagent.domain.repo.ChildRequirementRepository
import de.schoolagent.domain.repo.ParentActionRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.repo.SchoolEventRepository
import de.schoolagent.security.KidAccessService
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

/**
 * Assembles daily and weekly briefing messages (spec §26): "what's due
 * tomorrow" for the evening, "what's happening next week" for Sunday.
 */
@Service
class BriefingService(
    private val caseRepo: SchoolCaseRepository,
    private val eventRepo: SchoolEventRepository,
    private val actionRepo: ParentActionRepository,
    private val requirementRepo: ChildRequirementRepository,
    private val kids: ChildRepository,
    private val schoolCalendar: SchoolCalendarService,
    private val access: KidAccessService,
    private val whatsapp: de.schoolagent.whatsapp.WhatsAppConfigService,
    private val copy: de.schoolagent.inbox.InboxCopyService? = null,
    private val relevance: de.schoolagent.inbox.CaseRelevanceService? = null,
) {

    private fun currentCases(): List<SchoolCase> {
        val all = caseRepo.findAll()
        val visibility = relevance?.current(all).orEmpty()
        return all.filter { visibility[it.id]?.archived != true }
    }

    private val berlin = ZoneId.of("Europe/Berlin")
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val shortDay = DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH)
    private val monthDay = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val openStates = listOf(ActionStatus.OPEN, ActionStatus.ASSIGNED, ActionStatus.IN_PROGRESS, ActionStatus.OVERDUE)

    /** Builds the evening message. Only score 4–5 matters; otherwise silence. */
    fun buildDaily(userDetails: UserDetails, tomorrow: LocalDate): String {
        val es = de.schoolagent.i18n.UserLanguage.of(access.account(userDetails)?.language) == de.schoolagent.i18n.UserLanguage.ES
        val locale = if (es) Locale.forLanguageTag("es") else Locale.ENGLISH
        val dayFmt = dayFmt.withLocale(locale)
        val shortDay = shortDay.withLocale(locale)
        val monthDay = monthDay.withLocale(locale)
        val accessible = access.accessibleKidIds(userDetails)
        if (accessible.isEmpty()) return ""
        val kidList = kids.findAll().filter { accessible.contains(it.id) }.sortedBy { it.name }
        val today = tomorrow.minusDays(1)
        val singleKid = kidList.size == 1
        val cases = currentCases().filter { !it.isClosed() && !it.muted }.associateBy { it.id }
        val dayStart = tomorrow.atStartOfDay(berlin).toInstant()
        val dayEnd = tomorrow.plusDays(1).atStartOfDay(berlin).toInstant()
        val events = eventRepo.findByStartTimeBetweenOrderByStartTimeAsc(dayStart, dayEnd)
            .filter { it.status != EventStatus.CANCELLED && it.eventType != de.schoolagent.domain.enums.EventType.SCHOOL_REPORT }
            .filter { e -> cases[e.caseId]?.isInfo() != true || e.eventType !in setOf(de.schoolagent.domain.enums.EventType.BRING_ITEM, de.schoolagent.domain.enums.EventType.DEADLINE, de.schoolagent.domain.enums.EventType.PAYMENT) }
            .filter { it.startTime.atZone(berlin).toLocalDate() == tomorrow }
        val open = actionRepo.findByStatusIn(openStates).filter { cases[it.caseId]?.isInfo() == false }
        val requirements = requirementRepo.findAll().filter { it.status == RequirementStatus.OPEN && cases[it.caseId]?.isInfo() == false }

        val body = StringBuilder()
        var any = false
        for (kid in kidList) {
            val lines = mutableListOf<Line>()
            events.filter { belongsTo(it.childId, it.caseId, kid.id, cases) }
                .filter { loud(it.caseId, cases) }
                .forEach { e -> lines.add(Line(e.caseId, e.title, RANK_EVENT, eventTime(e))) }
            open.filter { a -> a.dueAt?.atZone(berlin)?.toLocalDate() == tomorrow }
                .filter { a -> childOf(a.caseId, cases) == kid.id }
                .filter { a -> loud(a.caseId, cases) }
                .forEach { a ->
                    lines.add(Line(a.caseId, a.title, RANK_ACTION, if (es) "vence" else "due"))
                }
            requirements.filter { r -> r.relevantOn == tomorrow }
                .filter { r -> childOf(r.caseId, cases) == kid.id }
                .filter { r -> loud(r.caseId, cases) }
                .forEach { r -> lines.add(Line(r.caseId, r.description, RANK_REQUIREMENT)) }
            val collapsed = collapse(lines)
            if (collapsed.isEmpty()) continue
            if (!singleKid) body.appendLine("*${kid.name}*")
            collapsed.forEach { body.appendLine("• $it") }
            if (!singleKid) body.appendLine()
            any = true
        }

        val hasTomorrow = any
        val overdue = open
            .filter { a -> a.dueAt?.atZone(berlin)?.toLocalDate() == today }
            .filter { a -> accessible.contains(childOf(a.caseId, cases)) }
            .filter { a -> loud(a.caseId, cases) }
        val overdueTitles = kidList.flatMap { kid ->
            collapse(overdue.filter { childOf(it.caseId, cases) == kid.id }.map { Line(it.caseId, it.title, RANK_ACTION) })
                .map { "${kid.name} · $it" }
        }
        if (overdueTitles.isNotEmpty()) {
            body.appendLine()
            body.appendLine(if (es) "*Pendiente de hoy:*" else "*Still open from today:*")
            overdueTitles.forEach { title -> body.appendLine("• $title") }
            any = true
        }

        if (!any) return ""

        return buildString {
            val label = if (hasTomorrow) { if (es) "Mañana" else "Tomorrow" }
                else { if (es) "Pendiente" else "Still open" }
            val date = (if (hasTomorrow) tomorrow else today).format(dayFmt)
            val child = if (singleKid) "${kidList[0].name}, " else ""
            appendLine("📋 $label — $child$date")
            appendLine()
            append(body)
        }.trim().replace(Regex("\n{3,}"), "\n\n")
    }

    /** Builds the Sunday message: one bullet list per weekday, no second action dump. */
    fun buildWeekly(userDetails: UserDetails, weekStart: LocalDate): String {
        val es = de.schoolagent.i18n.UserLanguage.of(access.account(userDetails)?.language) == de.schoolagent.i18n.UserLanguage.ES
        val locale = if (es) Locale.forLanguageTag("es") else Locale.ENGLISH
        val dayFmt = dayFmt.withLocale(locale)
        val shortDay = shortDay.withLocale(locale)
        val monthDay = monthDay.withLocale(locale)
        val accessible = access.accessibleKidIds(userDetails)
        if (accessible.isEmpty()) return ""
        val kidList = kids.findAll().filter { accessible.contains(it.id) }.sortedBy { it.name }
        val weekEnd = weekStart.plusDays(6)
        val lastWeekStart = weekStart.minusDays(7)
        val lastWeekEnd = weekStart.minusDays(1)
        val singleKid = kidList.size == 1
        val cases = currentCases().filter { !it.isClosed() && !it.muted }.associateBy { it.id }
        val weekEvents = eventRepo.findByStartTimeBetweenOrderByStartTimeAsc(
            weekStart.atStartOfDay(berlin).toInstant(),
            weekEnd.plusDays(1).atStartOfDay(berlin).toInstant(),
        ).filter { it.status != EventStatus.CANCELLED && it.eventType != de.schoolagent.domain.enums.EventType.SCHOOL_REPORT }
            .filter { e -> cases[e.caseId]?.isInfo() != true || e.eventType !in setOf(de.schoolagent.domain.enums.EventType.BRING_ITEM, de.schoolagent.domain.enums.EventType.DEADLINE, de.schoolagent.domain.enums.EventType.PAYMENT) }
        val open = actionRepo.findByStatusIn(openStates).filter { cases[it.caseId]?.isInfo() == false }
        val requirements = requirementRepo.findAll().filter { it.status == RequirementStatus.OPEN && cases[it.caseId]?.isInfo() == false }

        val days = StringBuilder()
        var anyDay = false
        for (i in 0..6) {
            val date = weekStart.plusDays(i.toLong())
            val dayLines = mutableListOf<String>()
            for (kid in kidList) {
                val holiday = schoolCalendar.holidayName(date, kid.id)
                if (holiday != null && !schoolCalendar.isSchoolDay(date, kid.id) &&
                    (i == 0 || schoolCalendar.holidayName(date.minusDays(1), kid.id) != holiday)) {
                    var until = date
                    while (until.isBefore(weekEnd) && schoolCalendar.holidayName(until.plusDays(1), kid.id) == holiday &&
                        !schoolCalendar.isSchoolDay(until.plusDays(1), kid.id)) until = until.plusDays(1)
                    val range = if (until != date) ", ${date.format(monthDay)} – ${until.format(monthDay)}" else ""
                    dayLines += "${kid.name} · ${if (es) "SIN CLASE" else "NO SCHOOL"} ($holiday$range)"
                }
                val lines = mutableListOf<Line>()
                weekEvents.filter { it.startTime.atZone(berlin).toLocalDate() == date }
                    .filter { belongsTo(it.childId, it.caseId, kid.id, cases) }
                    .forEach { lines += Line(it.caseId, it.title, RANK_EVENT, eventTime(it)) }
                open.filter { it.dueAt?.atZone(berlin)?.toLocalDate() == date && childOf(it.caseId, cases) == kid.id }
                    .forEach { lines += Line(it.caseId, it.title, RANK_ACTION, if (es) "vence" else "due") }
                requirements.filter { it.relevantOn == date && childOf(it.caseId, cases) == kid.id }
                    .forEach { lines += Line(it.caseId, it.description, RANK_REQUIREMENT) }
                dayLines += collapse(lines).map { "${kid.name} · $it" }
            }
            if (dayLines.isNotEmpty()) {
                anyDay = true
                days.appendLine("*${date.format(shortDay)}*")
                dayLines.forEach { days.appendLine("• $it") }
                days.appendLine()
            }
        }

        val overdue = open
            .filter { a -> a.dueAt?.atZone(berlin)?.toLocalDate()?.let { !it.isBefore(lastWeekStart) && !it.isAfter(lastWeekEnd) } == true }
            .filter { a -> accessible.contains(childOf(a.caseId, cases)) }
        val overdueTitles = kidList.flatMap { kid ->
            collapse(overdue.filter { childOf(it.caseId, cases) == kid.id }.map { Line(it.caseId, it.title, RANK_ACTION) })
                .map { "${kid.name} · $it" }
        }

        if (!anyDay && overdueTitles.isEmpty()) return ""

        return buildString {
            if (singleKid) {
                appendLine("📅 ${if (es) "La próxima semana" else "Next week"} — ${kidList[0].name}, ${weekStart.format(monthDay)} – ${weekEnd.format(monthDay)}")
            } else {
                appendLine("📅 ${if (es) "La próxima semana" else "Next week"} — ${weekStart.format(monthDay)} – ${weekEnd.format(monthDay)}")
            }
            appendLine()
            append(days)
            if (overdueTitles.isNotEmpty()) {
                appendLine()
                appendLine(if (es) "*Pendiente de la semana pasada:*" else "*Overdue from last week:*")
                overdueTitles.forEach { appendLine("• $it") }
            }
        }.trim().replace(Regex("\n{3,}"), "\n\n")
    }

    data class Plan(val childId: UUID, val child: String, val title: String, val rows: List<String>)

    /** Typed per-child plan for outbound templates; chat rendering remains separately readable. */
    fun plans(user: UserDetails, start: LocalDate, weekly: Boolean): List<Plan> {
        val allowed = access.accessibleKidIds(user).toSet()
        val end = if (weekly) start.plusDays(6) else start
        val previousStart = start.minusDays(7)
        val activeCases = currentCases().filter { !it.isClosed() && !it.muted &&
            it.childId in allowed && (weekly || it.importanceScore >= DAILY_MIN_SCORE) }.associateBy { it.id }
        val projections = copy?.current(activeCases.values.toList()).orEmpty()
        val events = eventRepo.findByStartTimeBetweenOrderByStartTimeAsc(start.atStartOfDay(berlin).toInstant(),
            end.plusDays(1).atStartOfDay(berlin).toInstant()).filter {
            it.status != EventStatus.CANCELLED && it.caseId in activeCases &&
            it.eventType !in setOf(de.schoolagent.domain.enums.EventType.BRING_ITEM, de.schoolagent.domain.enums.EventType.DEADLINE, de.schoolagent.domain.enums.EventType.PAYMENT, de.schoolagent.domain.enums.EventType.SCHOOL_REPORT)
        }
        val actions = actionRepo.findByStatusIn(openStates).filter { activeCases[it.caseId]?.isInfo() == false }
        val needs = requirementRepo.findAll().filter { it.status == RequirementStatus.OPEN && activeCases[it.caseId]?.isInfo() == false }
        val home = whatsapp.config().frontendUrl.trimEnd('/')
        return kids.findAll().filter { it.id in allowed }.sortedBy { it.name }.mapNotNull { kid ->
            val rows = mutableListOf<String>()
            var day = start
            while (!day.isAfter(end)) {
                val date = day
                val holiday = schoolCalendar.holidayName(date, kid.id)
                if (weekly && holiday != null && !schoolCalendar.isSchoolDay(date, kid.id) &&
                    (date == start || schoolCalendar.holidayName(date.minusDays(1), kid.id) != holiday)) {
                    rows += "${date.format(dayFmt)} · No school: $holiday"
                }
                activeCases.values.filter { it.childId == kid.id }.forEach { c ->
                    val aa = actions.filter { it.caseId == c.id && it.dueAt?.atZone(berlin)?.toLocalDate() == date }
                    val ee = events.filter { it.caseId == c.id && it.startTime.atZone(berlin).toLocalDate() == date }
                    val rr = needs.filter { it.caseId == c.id && it.relevantOn == date }
                    if (aa.isNotEmpty() || ee.isNotEmpty() || rr.isNotEmpty()) {
                        val projection = projections[c.id]
                        val reminder = projection?.reminder(date.toString(), aa, ee, rr)
                            ?: (aa.map { projection?.actions?.get(it.id.toString()) ?: it.title } +
                                rr.map { it.description } + ee.map { it.title + eventTime(it).takeIf(String::isNotBlank)?.let { time -> " · $time" }.orEmpty() })
                                .distinct().joinToString("; ")
                        rows += "${date.format(dayFmt)} · $reminder · $home/cases/${c.id}"
                    }
                }
                day = day.plusDays(1)
            }
            actions.filter { a -> weekly && activeCases[a.caseId]?.childId == kid.id && a.dueAt?.atZone(berlin)?.toLocalDate()?.let {
                !it.isBefore(previousStart) && it.isBefore(start) } == true }.groupBy { it.caseId to it.dueAt!!.atZone(berlin).toLocalDate() }.forEach { (key, group) ->
                val (id, date) = key
                val projection = projections[id]
                val sameDayNeeds = needs.filter { it.caseId == id && it.relevantOn == date }
                val reminder = projection?.reminder(date.toString(), group, emptyList(), sameDayNeeds)
                    ?: group.map { projection?.actions?.get(it.id.toString()) ?: it.title }.distinct().joinToString("; ")
                rows += "Still open · ${date.format(dayFmt)} · $reminder · $home/cases/$id"
            }
            if (rows.isEmpty()) null else Plan(kid.id, kid.name,
                if (weekly) "${start.format(monthDay)} – ${end.format(monthDay)}"
                else start.format(dayFmt), rows)
        }
    }

    /** Week key for dedup (ISO week number). */
    fun weekKey(date: LocalDate): String {
        val wf = WeekFields.ISO
        return "${date.get(wf.weekBasedYear())}-W${date.get(wf.weekOfWeekBasedYear())}"
    }

    private fun childOf(caseId: UUID, cases: Map<UUID, SchoolCase>): UUID? = cases[caseId]?.childId

    private fun belongsTo(eventChild: UUID?, caseId: UUID, kidId: UUID, cases: Map<UUID, SchoolCase>): Boolean =
        cases.containsKey(caseId) && (eventChild ?: childOf(caseId, cases)) == kidId

    private fun loud(caseId: UUID, cases: Map<UUID, SchoolCase>): Boolean =
        (cases[caseId]?.importanceScore ?: 0) >= DAILY_MIN_SCORE

    private fun eventTime(event: de.schoolagent.domain.model.SchoolEvent): String {
        if (event.allDay) return ""
        val local = event.startTime.atZone(berlin)
        if (local.hour == 0 && local.minute == 0) return ""
        return local.format(clockFmt)
    }

    private fun dueLabel(from: LocalDate, due: LocalDate, kidId: UUID?): String =
        "due" + schoolDaysNote(from, due, kidId)

    private fun schoolDaysNote(from: LocalDate, due: LocalDate?, kidId: UUID?): String {
        if (due == null) return ""
        val school = schoolCalendar.schoolDaysBetween(from.plusDays(1), due, kidId)
        val calendar = java.time.temporal.ChronoUnit.DAYS.between(from, due).toInt()
        if (school < 0 || calendar <= 0 || school >= calendar) return ""
        return " ($school school days)"
    }

    private data class Line(val caseId: UUID, val title: String, val rank: Int, val detail: String = "")

    /** One school matter once: prefer a parent task over a calendar paraphrase. */
    private fun collapse(lines: List<Line>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val base = whatsapp.config().frontendUrl.trimEnd('/')
        return lines.groupBy { it.caseId }.map { (caseId, group) ->
            val parts = group.sortedByDescending { it.rank }
                .groupBy { it.title.replace(Regex("\\s+"), " ").trim().lowercase() }
                .values.map { duplicates ->
                    val details = duplicates.map { it.detail }.filter { it.isNotBlank() }.distinct()
                    duplicates.first().title.trim() + if (details.isEmpty()) "" else " — ${details.joinToString(", ")}"
                }
            parts.joinToString(" · ") + "\n↗ $base/cases/$caseId"
        }
    }

    companion object {
        const val DAILY_MIN_SCORE = 4
        private const val RANK_ACTION = 2
        private const val RANK_EVENT = 1
        private const val RANK_REQUIREMENT = 0
    }
}
