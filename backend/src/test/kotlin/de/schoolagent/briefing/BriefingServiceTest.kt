package de.schoolagent.briefing

import de.schoolagent.calendar.SchoolCalendarService
import de.schoolagent.domain.enums.ActionStatus
import de.schoolagent.domain.enums.EventType
import de.schoolagent.domain.enums.RequirementStatus
import de.schoolagent.domain.model.Child
import de.schoolagent.domain.model.ChildRequirement
import de.schoolagent.domain.model.ParentAction
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.model.SchoolEvent
import de.schoolagent.domain.repo.ChildRepository
import de.schoolagent.domain.repo.ChildRequirementRepository
import de.schoolagent.domain.repo.ParentActionRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.repo.SchoolEventRepository
import de.schoolagent.security.KidAccessService
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.core.userdetails.User
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

class BriefingServiceTest {

    private val caseRepo = mockk<SchoolCaseRepository>()
    private val eventRepo = mockk<SchoolEventRepository>()
    private val actionRepo = mockk<ParentActionRepository>()
    private val requirementRepo = mockk<ChildRequirementRepository>()
    private val kids = mockk<ChildRepository>()
    private val calendar = mockk<SchoolCalendarService>()
    private val access = mockk<KidAccessService>()
    private val whatsapp = mockk<de.schoolagent.whatsapp.WhatsAppConfigService>(relaxed = true)
    private lateinit var briefing: BriefingService

    private val kidId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val caseId = UUID.fromString("00000000-0000-0000-0000-0000000000cc")
    private val user = User("morgan", "x", emptyList())
    private val tomorrow = LocalDate.of(2026, 9, 25)

    @BeforeEach
    fun setUp() {
        briefing = BriefingService(caseRepo, eventRepo, actionRepo, requirementRepo, kids, calendar, access, whatsapp)
        every { whatsapp.config().frontendUrl } returns "https://school.example.org"
        every { access.account(any()) } returns null
        every { access.accessibleKidIds(any()) } returns listOf(kidId)
        every { kids.findAll() } returns listOf(Child(id = kidId, name = "Jamie"))
        every { kids.findById(kidId) } returns Optional.of(Child(id = kidId, name = "Jamie"))
        every { caseRepo.findAll() } returns listOf(
            SchoolCase(id = caseId, childId = kidId, title = "Trip", importanceScore = 4),
        )
        every { calendar.holidayName(any(), any()) } returns null
        every { calendar.isSchoolDay(any(), any()) } returns true
        every { calendar.schoolDaysBetween(any(), any(), any()) } returns 1
        every { eventRepo.findByStartTimeBetweenOrderByStartTimeAsc(any(), any()) } returns emptyList()
        every { actionRepo.findByStatusIn(any()) } returns emptyList()
        every { requirementRepo.findAll() } returns emptyList()
    }

    @Test fun `outbound plans separate children and keep dates duties and individual letter links`() {
        val taylor = UUID.randomUUID()
        val saraCase = UUID.randomUUID()
        every { kids.findAll() } returns listOf(Child(id = kidId, name = "Jamie"), Child(id = taylor, name = "Taylor"))
        every { access.accessibleKidIds(any()) } returns listOf(kidId, taylor)
        every { caseRepo.findAll() } returns listOf(
            SchoolCase(id = caseId, childId = kidId, title = "Trip", importanceScore = 4),
            SchoolCase(id = saraCase, childId = taylor, title = "Copying", importanceScore = 4))
        every { actionRepo.findByStatusIn(any()) } returns listOf(
            ParentAction(caseId = caseId, title = "Return permission", dueAt = tomorrow.atStartOfDay(java.time.ZoneId.of("Europe/Berlin")).toInstant(), dedupKey = "a"),
            ParentAction(caseId = saraCase, title = "Bring €17.20", dueAt = tomorrow.atStartOfDay(java.time.ZoneId.of("Europe/Berlin")).toInstant(), dedupKey = "s"))
        val plans = briefing.plans(user, tomorrow, false)
        assertThat(plans.map { it.child }).containsExactly("Jamie", "Taylor")
        assertThat(plans[0].rows.joinToString()).contains("Fri 25 Sep", "Return permission", "/cases/$caseId").doesNotContain("€17.20")
        assertThat(plans[1].rows.joinToString()).contains("Fri 25 Sep", "€17.20", "/cases/$saraCase").doesNotContain("Return permission")
        every { access.accessibleKidIds(any()) } returns listOf(taylor)
        assertThat(briefing.plans(user, tomorrow, false).map { it.child }).containsExactly("Taylor")
    }
    @Test fun `tomorrow plans exclude today's unfinished actions while weekly plans keep labelled carryover`() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        every { actionRepo.findByStatusIn(any()) } returns listOf(
            ParentAction(caseId = caseId, title = "Today's reading book", dueAt = tomorrow.minusDays(1).atStartOfDay(zone).toInstant(), dedupKey = "today"))
        assertThat(briefing.plans(user, tomorrow, false)).isEmpty()
        val weekly = briefing.plans(user, tomorrow, true).single()
        assertThat(weekly.rows.single()).contains("Still open · Thu 24 Sep", "Today's reading book")
        every { actionRepo.findByStatusIn(any()) } returns listOf(
            ParentAction(caseId = caseId, title = "Today's reading book", dueAt = tomorrow.minusDays(1).atStartOfDay(zone).toInstant(), dedupKey = "today"),
            ParentAction(caseId = caseId, title = "Tomorrow's permission", dueAt = tomorrow.atStartOfDay(zone).toInstant(), dedupKey = "tomorrow"))
        assertThat(briefing.plans(user, tomorrow, false).single().rows.single())
            .contains("Fri 25 Sep", "Tomorrow's permission").doesNotContain("Still open", "Today's reading book")
    }

    @Test fun `plans reuse one saved reminder per letter and date without repeating parent and child wording`() {
        val action = ParentAction(caseId = caseId, title = "Buy the reading book", dueAt = tomorrow.atStartOfDay(java.time.ZoneId.of("Europe/Berlin")).toInstant(), dedupKey = "book")
        val need = ChildRequirement(caseId = caseId, description = "Bring the same reading book", relevantOn = tomorrow, dedupKey = "book-need")
        val copy = mockk<de.schoolagent.inbox.InboxCopyService>()
        every { copy.current(any()) } returns mapOf(caseId to de.schoolagent.inbox.InboxCopy("Reading project", null, emptyMap(),
            mapOf(tomorrow.toString() to de.schoolagent.inbox.ReminderCopy("Buy and bring the class reading book", setOf("action:${action.id}", "need:${need.description}")))))
        every { actionRepo.findByStatusIn(any()) } returns listOf(action)
        every { requirementRepo.findAll() } returns listOf(need)
        val saved = BriefingService(caseRepo, eventRepo, actionRepo, requirementRepo, kids, calendar, access, whatsapp, copy)
        val rows = saved.plans(user, tomorrow, false).single().rows
        assertThat(rows).hasSize(1)
        assertThat(rows.single()).contains("Buy and bring the class reading book", "/cases/$caseId").doesNotContain("Reading project", "Bring the same")
        val messages = de.schoolagent.whatsapp.WaTemplates.messages(de.schoolagent.whatsapp.WaTemplates.Kind.PLAN, de.schoolagent.i18n.UserLanguage.EN, "Jamie", "Fri 25 Sep", "", rows, "calendar")
        assertThat(messages.single().params.drop(1)).hasSize(1)
    }

    @Test fun `archived matters do not appear in scheduled plans or conversational briefings`() {
        val relevance = mockk<de.schoolagent.inbox.CaseRelevanceService>()
        every { relevance.current(any()) } returns mapOf(caseId to de.schoolagent.inbox.CaseRelevance(
            Instant.EPOCH, "UNKNOWN", null, "Period unknown", true))
        every { actionRepo.findByStatusIn(any()) } returns listOf(ParentAction(caseId = caseId,
            title = "Return form", dueAt = tomorrow.atStartOfDay(java.time.ZoneId.of("Europe/Berlin")).toInstant(), dedupKey = "archived"))
        val guarded = BriefingService(caseRepo, eventRepo, actionRepo, requirementRepo, kids, calendar, access, whatsapp,
            relevance = relevance)
        assertThat(guarded.plans(user, tomorrow, false)).isEmpty()
        assertThat(guarded.buildDaily(user, tomorrow)).isEmpty()
        assertThat(guarded.buildWeekly(user, tomorrow)).isEmpty()
    }

    @Test
    fun `overdue matters always identify the child in a family briefing`() {
        val taylor = UUID.randomUUID()
        val saraCase = UUID.randomUUID()
        every { kids.findAll() } returns listOf(Child(id = kidId, name = "Jamie"), Child(id = taylor, name = "Taylor"))
        every { access.accessibleKidIds(any()) } returns listOf(kidId, taylor)
        every { caseRepo.findAll() } returns listOf(
            SchoolCase(id = caseId, childId = kidId, title = "Jamie trip", importanceScore = 4),
            SchoolCase(id = saraCase, childId = taylor, title = "Taylor trip", importanceScore = 4),
        )
        every { actionRepo.findByStatusIn(any()) } returns listOf(
            ParentAction(caseId = caseId, title = "Return permission", dueAt = tomorrow.minusDays(1).atStartOfDay(java.time.ZoneId.of("Europe/Berlin")).toInstant(), dedupKey = "a"),
            ParentAction(caseId = saraCase, title = "Bring workbook", dueAt = tomorrow.minusDays(1).atStartOfDay(java.time.ZoneId.of("Europe/Berlin")).toInstant(), dedupKey = "s"),
        )
        val message = briefing.buildDaily(user, tomorrow)
        assertThat(message).contains("Jamie · Return permission", "Taylor · Bring workbook")
        assertThat(message).startsWith("📋 Still open — Thu 24 Sep")
        assertThat(message).doesNotContain("Tomorrow")
    }

    @Test
    fun `daily briefing lists tomorrow's event, due action and requirement`() {
        every { eventRepo.findByStartTimeBetweenOrderByStartTimeAsc(any(), any()) } returns listOf(
            SchoolEvent(
                caseId = caseId,
                childId = kidId,
                title = "Autumn concert",
                eventType = EventType.MUSIC_EVENT,
                startTime = Instant.parse("2026-09-25T16:00:00Z"),
                dedupKey = "e1",
            ),
        )
        every { actionRepo.findByStatusIn(any()) } returns listOf(
            ParentAction(
                caseId = caseId,
                title = "Atlas order",
                status = ActionStatus.OPEN,
                dueAt = Instant.parse("2026-09-25T10:00:00Z"),
                dedupKey = "a1",
            ),
        )
        every { requirementRepo.findAll() } returns listOf(
            ChildRequirement(
                caseId = caseId,
                description = "Gym kit",
                relevantOn = tomorrow,
                status = RequirementStatus.OPEN,
                dedupKey = "r1",
            ),
        )

        val text = briefing.buildDaily(user, tomorrow)

        assertThat(text).contains("Tomorrow").contains("Jamie").contains("Atlas order")
        assertThat(text).doesNotContain("NO SCHOOL")
        // same letter: action wins over the concert paraphrase
        assertThat(text).contains("Autumn concert").contains("Gym kit")
    }

    @Test
    fun `daily briefing is silent on a holiday with nothing urgent`() {
        every { calendar.holidayName(tomorrow, kidId) } returns "Herbstferien"
        every { calendar.isSchoolDay(tomorrow, kidId) } returns false

        val text = briefing.buildDaily(user, tomorrow)

        assertThat(text).isEmpty()
    }

    @Test
    fun `daily briefing skips score 3 swimming but sends score 4 lice`() {
        val swimId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
        val liceId = UUID.fromString("00000000-0000-0000-0000-0000000000c2")
        every { caseRepo.findAll() } returns listOf(
            SchoolCase(id = swimId, childId = kidId, title = "Schwimmplan", importanceScore = 3),
            SchoolCase(id = liceId, childId = kidId, title = "Lice", importanceScore = 4),
        )
        every { eventRepo.findByStartTimeBetweenOrderByStartTimeAsc(any(), any()) } returns listOf(
            SchoolEvent(
                caseId = swimId,
                childId = kidId,
                title = "Swimming lessons begin",
                eventType = EventType.SCHOOL_EVENT,
                startTime = Instant.parse("2026-09-25T07:00:00Z"),
                dedupKey = "e-swim",
            ),
        )
        every { actionRepo.findByStatusIn(any()) } returns listOf(
            ParentAction(
                caseId = liceId,
                title = "Check Jamie's hair for lice",
                status = ActionStatus.OPEN,
                dueAt = Instant.parse("2026-09-25T10:00:00Z"),
                dedupKey = "a-lice",
            ),
        )

        val text = briefing.buildDaily(user, tomorrow)

        assertThat(text).contains("Check Jamie's hair")
        assertThat(text).doesNotContain("Swimming")
    }

    @Test
    fun `daily briefing still lists a score 4 task on a holiday`() {
        every { calendar.holidayName(tomorrow, kidId) } returns "Tag der Deutschen Einheit"
        every { calendar.isSchoolDay(tomorrow, kidId) } returns false
        every { actionRepo.findByStatusIn(any()) } returns listOf(
            ParentAction(
                caseId = caseId,
                title = "Check hair for lice",
                status = ActionStatus.OPEN,
                dueAt = Instant.parse("2026-09-25T10:00:00Z"),
                dedupKey = "a-lice",
            ),
        )

        val text = briefing.buildDaily(user, tomorrow)

        assertThat(text).contains("Check hair for lice")
        assertThat(text).doesNotContain("NO SCHOOL")
    }

    @Test
    fun `daily briefing is blank when tomorrow is empty and nothing is still open`() {
        val text = briefing.buildDaily(user, tomorrow)
        assertThat(text).isEmpty()
    }

    @Test
    fun `weekKey uses ISO week numbers`() {
        assertThat(briefing.weekKey(LocalDate.of(2026, 12, 28))).isEqualTo("2026-W53")
    }

    @Test
    fun `weekly briefing is one bullet per day and drops the open-actions dump`() {
        val weekStart = LocalDate.of(2026, 9, 28)
        every { eventRepo.findByStartTimeBetweenOrderByStartTimeAsc(any(), any()) } returns listOf(
            SchoolEvent(
                caseId = caseId,
                childId = kidId,
                title = "Registration deadline Forstlauf",
                eventType = EventType.DEADLINE,
                startTime = Instant.parse("2026-09-28T22:00:00Z"),
                allDay = true,
                dedupKey = "e-dl",
            ),
            SchoolEvent(
                caseId = caseId,
                childId = kidId,
                title = "Pay €10 start fee",
                eventType = EventType.PAYMENT,
                startTime = Instant.parse("2026-09-28T22:00:00Z"),
                allDay = true,
                dedupKey = "e-pay",
            ),
        )
        every { actionRepo.findByStatusIn(any()) } returns listOf(
            ParentAction(
                caseId = caseId,
                title = "Register and pay €10 for Forstlauf via PE teacher",
                status = ActionStatus.OPEN,
                dueAt = Instant.parse("2026-09-28T22:00:00Z"),
                dedupKey = "a1",
            ),
            ParentAction(
                caseId = caseId,
                title = "Register child for Forstlauf 2026 with Mr. Streng",
                status = ActionStatus.OPEN,
                dueAt = Instant.parse("2026-09-28T22:00:00Z"),
                dedupKey = "a2",
            ),
        )

        val text = briefing.buildWeekly(user, weekStart)

        assertThat(text).contains("Next week").contains("Tue 29").contains("Register and pay")
        assertThat(text).doesNotContain("Open actions")
        assertThat(text).contains("Registration deadline Forstlauf")
        assertThat(text.indexOf("Register and pay")).isEqualTo(text.lastIndexOf("Register and pay"))
    }
    @Test
    fun `same task in calendar and actions is printed once with its date and link`() {
        every { eventRepo.findByStartTimeBetweenOrderByStartTimeAsc(any(), any()) } returns listOf(
            SchoolEvent(caseId = caseId, childId = kidId, title = "Return form",
                eventType = EventType.DEADLINE, startTime = Instant.parse("2026-09-25T10:00:00Z"), dedupKey = "same-event"),
        )
        every { actionRepo.findByStatusIn(any()) } returns listOf(
            ParentAction(caseId = caseId, title = "Return form", dueAt = Instant.parse("2026-09-25T10:00:00Z"), dedupKey = "same-task"),
        )
        val text = briefing.buildDaily(user, tomorrow)
        assertThat(Regex("Return form").findAll(text).count()).isEqualTo(1)
        assertThat(text).contains("due", "12:00", "https://school.example.org/cases/$caseId")
        assertThat(text).doesNotContain("\n\n\n", "*Jamie*")
    }

}
