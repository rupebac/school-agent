package de.schoolagent.whatsapp

import de.schoolagent.ai.ModelRouter
import de.schoolagent.domain.enums.ActionStatus
import de.schoolagent.domain.enums.EvidenceKind
import de.schoolagent.domain.enums.EventType
import de.schoolagent.domain.enums.SourceType
import de.schoolagent.domain.model.AppUser
import de.schoolagent.domain.model.ChildRequirement
import de.schoolagent.domain.model.Evidence
import de.schoolagent.domain.model.ParentAction
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.model.SchoolEvent
import de.schoolagent.domain.model.Source
import de.schoolagent.domain.repo.ChildRequirementRepository
import de.schoolagent.domain.repo.EvidenceRepository
import de.schoolagent.domain.repo.ParentActionRepository
import de.schoolagent.domain.repo.SchoolCalendarEntryRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.repo.SchoolEventRepository
import de.schoolagent.domain.repo.SourceRepository
import de.schoolagent.security.KidAccessService
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class QuestionAnsweringServiceTest {

    private val caseRepo = mockk<SchoolCaseRepository>()
    private val eventRepo = mockk<SchoolEventRepository>()
    private val actionRepo = mockk<ParentActionRepository>()
    private val evidenceRepo = mockk<EvidenceRepository>()
    private val sourceRepo = mockk<SourceRepository>()
    private val requirementRepo = mockk<ChildRequirementRepository>()
    private val calendarRepo = mockk<SchoolCalendarEntryRepository>()
    private val access = mockk<KidAccessService>()
    private val router = mockk<ModelRouter>(relaxed = true)
    private val kids = mockk<de.schoolagent.domain.repo.ChildRepository>()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var qa: QuestionAnsweringService

    private val kidId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val otherKid = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
    private val user = AppUser(name = "morgan", displayName = "Morgan", passwordHash = "x")

    @BeforeEach
    fun setUp() {
        qa = QuestionAnsweringService(
            caseRepo, eventRepo, actionRepo, evidenceRepo, sourceRepo,
            requirementRepo, calendarRepo, access, router, clock, kids,
        )
        every { kids.findAll() } returns listOf(
            de.schoolagent.domain.model.Child(id = kidId, name = "Jamie"),
            de.schoolagent.domain.model.Child(id = otherKid, name = "Taylor"),
        )
        every { access.requireAccess(any(), any()) } just Runs
        every { access.accessibleKidIds(any()) } returns listOf(kidId)
        every { router.available(any()) } returns false
        every { caseRepo.findAll() } returns emptyList()
        every { eventRepo.findAll() } returns emptyList()
        every { actionRepo.findAll() } returns emptyList()
        every { actionRepo.findByStatusIn(any()) } returns emptyList()
        every { evidenceRepo.findAll() } returns emptyList()
        every { sourceRepo.findAll() } returns emptyList()
        every { requirementRepo.findAll() } returns emptyList()
        every { calendarRepo.findAll() } returns emptyList()
    }

    @ParameterizedTest
    @CsvSource(
        "hello, false",
        "ok, false",
        "thanks, false",
        "'when is x?', true",
        "wann ist das Konzert, true",
        "cuando es el Elternabend, true",
    )
    fun `looksLikeQuestion`(text: String, expected: Boolean) {
        assertThat(qa.looksLikeQuestion(text)).isEqualTo(expected)
    }

    @Test
    fun `deterministic keyword match answers without calling the model`() {
        val caseId = UUID.randomUUID()
        every { caseRepo.findAll() } returns listOf(SchoolCase(id = caseId, childId = kidId, title = "Elternabend"))
        every { eventRepo.findAll() } returns listOf(
            SchoolEvent(
                caseId = caseId,
                childId = kidId,
                title = "Elternabend",
                eventType = EventType.PARENT_EVENT,
                startTime = Instant.parse("2026-10-12T16:00:00Z"),
                dedupKey = "e1",
            ),
        )

        val answer = qa.answer("when is the Elternabend?", user)

        assertThat(answer.text).contains("Elternabend")
        verify(exactly = 0) { router.complete(any(), any(), any(), any()) }
    }

    @Test
    fun `does not leak another kid's open action`() {
        val mine = UUID.randomUUID()
        val theirs = UUID.randomUUID()
        every { caseRepo.findAll() } returns listOf(
            SchoolCase(id = mine, childId = kidId, title = "Ours"),
            SchoolCase(id = theirs, childId = otherKid, title = "Theirs"),
        )
        every { actionRepo.findAll() } returns listOf(
            ParentAction(caseId = theirs, title = "Pay Schullandheim", dedupKey = "secret"),
        )

        val answer = qa.answer("when do we pay Schullandheim?", user)

        assertThat(answer.text).doesNotContain("Pay Schullandheim")
        assertThat(answer.text).contains("don't have that information")
    }

    @Test
    fun `mention search cites the letter without the model`() {
        val caseId = UUID.randomUUID()
        val sourceId = UUID.randomUUID()
        val received = Instant.parse("2026-06-10T08:00:00Z")
        every { caseRepo.findAll() } returns listOf(
            SchoolCase(id = caseId, childId = kidId, title = "Start of school"),
        )
        every { sourceRepo.findAll() } returns listOf(
            Source(
                id = sourceId,
                caseId = caseId,
                sourceType = SourceType.PORTAL_LETTER,
                externalId = "x",
                dedupKey = "s1",
                contentHash = "h",
                title = "Elternbrief Weltatlas",
                receivedAt = received,
            ),
        )
        every { evidenceRepo.findAll() } returns listOf(
            Evidence(
                caseId = caseId,
                sourceId = sourceId,
                kind = EvidenceKind.OBLIGATION_CHILD,
                subject = "Bring a Weltatlas on the first day",
                dedupKey = "ev1",
                createdAt = received,
            ),
        )

        val answer = qa.answer("is there any place where they are talking about Weltatlas?", user)

        assertThat(answer.text).contains("Weltatlas")
        assertThat(answer.text).contains("Elternbrief")
        verify(exactly = 0) { router.complete(any(), any(), any(), any()) }
    }

    @Test
    fun `mention search does not treat parent as a hit for are`() {
        val caseId = UUID.randomUUID()
        val sourceId = UUID.randomUUID()
        every { caseRepo.findAll() } returns listOf(
            SchoolCase(id = caseId, childId = kidId, title = "Parents evening", summary = "A parent letter about shoes"),
        )
        every { sourceRepo.findAll() } returns listOf(
            Source(
                id = sourceId, caseId = caseId, sourceType = SourceType.PORTAL_LETTER,
                externalId = "x", dedupKey = "s2", contentHash = "h",
                title = "Parent letter", receivedAt = Instant.parse("2026-06-10T08:00:00Z"),
            ),
        )
        every { evidenceRepo.findAll() } returns listOf(
            Evidence(
                caseId = caseId, sourceId = sourceId, kind = EvidenceKind.FACT,
                subject = "Bring indoor shoes", dedupKey = "ev2",
                createdAt = Instant.parse("2026-06-10T08:00:00Z"),
            ),
        )

        val answer = qa.answer("is there any place they are talking about Weltatlas?", user)

        assertThat(answer.text).doesNotContain("indoor shoes")
        assertThat(answer.text).doesNotContain("Parents evening")
        assertThat(answer.text).contains("don't have that information")
    }

    @Test
    fun `date window lists only events inside it`() {
        val caseId = UUID.randomUUID()
        every { caseRepo.findAll() } returns listOf(SchoolCase(id = caseId, childId = kidId, title = "Year"))
        every { eventRepo.findAll() } returns listOf(
            SchoolEvent(
                caseId = caseId, childId = kidId, title = "Summer concert",
                eventType = EventType.MUSIC_EVENT,
                startTime = Instant.parse("2026-06-15T16:00:00Z"), dedupKey = "jun",
            ),
            SchoolEvent(
                caseId = caseId, childId = kidId, title = "Autumn fair",
                eventType = EventType.SCHOOL_EVENT,
                startTime = Instant.parse("2026-10-05T16:00:00Z"), dedupKey = "oct",
            ),
        )

        val answer = qa.answer("what events after 1 january and before 1st september", user)

        assertThat(answer.text).contains("Summer concert")
        assertThat(answer.text).doesNotContain("Autumn fair")
        verify(exactly = 0) { router.complete(any(), any(), any(), any()) }
    }

    @Test
    fun `hello is an invite not a failed search`() {
        val answer = qa.answer("hello there", user)
        assertThat(answer.text).contains("ask me about dates")
        assertThat(answer.text).doesNotContain("done <n>").doesNotContain("pending")
        verify(exactly = 0) { router.complete(any(), any(), any(), any()) }
    }

    @Test
    fun `next vacations come from the school calendar`() {
        every { calendarRepo.findAll() } returns listOf(
            de.schoolagent.domain.model.SchoolCalendarEntry(
                name = "Sommerferien bayern 2026",
                startDate = java.time.LocalDate.of(2026, 8, 3),
                endDate = java.time.LocalDate.of(2026, 9, 15),
                entryType = de.schoolagent.domain.model.CalendarEntryType.VACATION,
            ),
            de.schoolagent.domain.model.SchoolCalendarEntry(
                name = "Herbstferien bayern 2026",
                startDate = java.time.LocalDate.of(2026, 11, 2),
                endDate = java.time.LocalDate.of(2026, 11, 7),
                entryType = de.schoolagent.domain.model.CalendarEntryType.VACATION,
            ),
            de.schoolagent.domain.model.SchoolCalendarEntry(
                name = "Tag der Deutschen Einheit",
                startDate = java.time.LocalDate.of(2026, 10, 3),
                endDate = java.time.LocalDate.of(2026, 10, 3),
                entryType = de.schoolagent.domain.model.CalendarEntryType.HOLIDAY,
            ),
        )

        val answer = qa.answer("when are the next vacations?", user)

        assertThat(answer.text).contains("Herbstferien")
        assertThat(answer.text).doesNotContain("Sommerferien")
        assertThat(answer.text).doesNotContain("Deutschen Einheit")
        verify(exactly = 0) { router.complete(any(), any(), any(), any()) }
    }

    @Test
    fun `next week follow-up is a short agenda not a letter dump`() {
        val caseId = UUID.randomUUID()
        every { caseRepo.findAll() } returns listOf(
            SchoolCase(
                id = caseId,
                childId = kidId,
                title = "Swimming",
                summary = "Non-swimmers bring short sports trousers and a towel plus goggles recommended.",
            ),
        )
        every { eventRepo.findAll() } returns listOf(
            SchoolEvent(
                caseId = caseId, childId = kidId, title = "Swimming lessons begin",
                eventType = EventType.SCHOOL_EVENT,
                startTime = Instant.parse("2026-09-28T07:00:00Z"), dedupKey = "swim",
            ),
            SchoolEvent(
                caseId = caseId, childId = kidId,
                title = "Bring: swimwear, goggles; non-swimmers: short sports trousers and towel",
                eventType = EventType.BRING_ITEM,
                startTime = Instant.parse("2026-09-28T07:00:00Z"), dedupKey = "kit",
            ),
        )
        every { requirementRepo.findAll() } returns listOf(
            ChildRequirement(
                caseId = caseId,
                description = "Wear appropriate swimwear (not sportive swimwear) for swimming lessons",
                relevantOn = java.time.LocalDate.of(2026, 9, 28),
                dedupKey = "r1",
            ),
            ChildRequirement(
                caseId = caseId,
                description = "Bring swimming goggles (recommended)",
                relevantOn = java.time.LocalDate.of(2026, 9, 28),
                dedupKey = "r2",
            ),
            ChildRequirement(
                caseId = caseId,
                description = "Non-swimmers: wear short sports trousers and bring a towel as a mat for sitting",
                relevantOn = java.time.LocalDate.of(2026, 9, 28),
                dedupKey = "r3",
            ),
        )
        val history = listOf(
            ChatTurn("user", "Weltatlas"),
            ChatTurn("assistant", "Weltatlas bulk order was due on Wed 23 Sep."),
        )

        val answer = qa.answer("and next week?", user, history)

        assertThat(answer.text).contains("Swimming lessons begin")
        assertThat(answer.text).doesNotContain("trousers")
        assertThat(answer.text).doesNotContain("goggles")
        assertThat(answer.text).doesNotContain("swimwear")
        verify(exactly = 0) { router.complete(any(), any(), any(), any()) }
    }

    @Test
    fun `glm skill describes matters and nested kit`() {
        assertThat(ChatSkill.text).contains("child_needs")
        assertThat(ChatSkill.text).contains("OVERVIEW")
        assertThat(ChatSkill.text).contains("one matter")
        assertThat(ChatSkill.text).contains("\"caseId\"")
        assertThat(ChatSkill.text).contains("information only")
    }

    @Test
    fun `when the model is up next week is one swimming matter with kit nested`() {
        val caseId = UUID.randomUUID()
        every { router.available(any()) } returns true
        val system = slot<String>()
        val prompt = slot<String>()
        every { router.complete(any(), capture(system), capture(prompt), any()) } returns
            """{"answer":"Swimming lessons begin — 28 Sep 2026"}"""
        every { caseRepo.findAll() } returns listOf(
            SchoolCase(id = caseId, childId = kidId, title = "Swimming"),
        )
        every { eventRepo.findAll() } returns listOf(
            SchoolEvent(
                caseId = caseId, childId = kidId, title = "Swimming lessons begin",
                eventType = EventType.SCHOOL_EVENT,
                startTime = Instant.parse("2026-09-28T07:00:00Z"), dedupKey = "swim",
            ),
            SchoolEvent(
                caseId = caseId, childId = kidId, title = "Bring goggles",
                eventType = EventType.BRING_ITEM,
                startTime = Instant.parse("2026-09-28T07:00:00Z"), dedupKey = "kit",
            ),
        )
        every { requirementRepo.findAll() } returns listOf(
            ChildRequirement(
                caseId = caseId,
                description = "Bring swimming goggles (recommended)",
                relevantOn = java.time.LocalDate.of(2026, 9, 28),
                dedupKey = "r2",
            ),
        )

        val answer = qa.answer("what is up for next week", user)

        assertThat(answer.text).contains("Swimming lessons begin")
        assertThat(answer.refs.map { it.id }).contains(caseId)
        assertThat(system.captured).contains("child_needs")
        assertThat(prompt.captured).contains("\"child_needs\"")
        assertThat(prompt.captured).contains("Bring swimming goggles")
        assertThat(prompt.captured.indexOf("Swimming lessons begin"))
            .isEqualTo(prompt.captured.lastIndexOf("Swimming lessons begin"))
        io.mockk.verify { router.complete(any(), ChatSkill.text, any(), "chat") }
    }

    @Test
    fun `an open letter is used when the parent asks what to bring`() {
        val swim = UUID.fromString("00000000-0000-0000-0000-0000000000cc")
        val other = UUID.fromString("00000000-0000-0000-0000-0000000000dd")
        every { caseRepo.findAll() } returns listOf(
            SchoolCase(id = swim, childId = kidId, title = "Swimming"),
            SchoolCase(id = other, childId = kidId, title = "Weltatlas"),
        )
        every { eventRepo.findAll() } returns listOf(
            SchoolEvent(
                caseId = swim, childId = kidId, title = "Swimming lessons begin",
                eventType = EventType.SCHOOL_EVENT,
                startTime = Instant.parse("2026-09-28T07:00:00Z"), dedupKey = "swim",
            ),
        )
        every { requirementRepo.findAll() } returns listOf(
            ChildRequirement(
                caseId = swim,
                description = "Bring swimming goggles",
                relevantOn = java.time.LocalDate.of(2026, 9, 28),
                dedupKey = "goggles",
            ),
            ChildRequirement(
                caseId = other,
                description = "Pay 35.50 EUR",
                relevantOn = java.time.LocalDate.of(2026, 9, 23),
                dedupKey = "pay",
            ),
        )

        val answer = qa.answer("what do we need to bring?", user, focusCaseId = swim)

        assertThat(answer.text).contains("goggles")
        assertThat(answer.text).doesNotContain("35.50")
        assertThat(answer.refs).extracting("id").containsExactly(swim)
    }

    @Test
    fun `next week still means the week even if a letter is open`() {
        val swim = UUID.randomUUID()
        every { caseRepo.findAll() } returns listOf(
            SchoolCase(id = swim, childId = kidId, title = "Swimming"),
        )
        every { eventRepo.findAll() } returns listOf(
            SchoolEvent(
                caseId = swim, childId = kidId, title = "Swimming lessons begin",
                eventType = EventType.SCHOOL_EVENT,
                startTime = Instant.parse("2026-09-28T07:00:00Z"), dedupKey = "swim",
            ),
        )

        val answer = qa.answer("what is for next week", user, focusCaseId = swim)

        assertThat(answer.text).contains("Swimming lessons begin")
        assertThat(answer.text).doesNotContain("goggles")
    }

    @Test
    fun `legacy whatsapp body adds a link without repeating the case title`() {
        val id = UUID.fromString("00000000-0000-0000-0000-0000000000ee")
        val body = ChatAnswer("Swimming on Tuesday", listOf(ChatRef(id, "Swimming lessons begin")))
            .forWhatsApp("https://school.example.org")
        assertThat(body).contains("Swimming on Tuesday")
        assertThat(body).contains("https://school.example.org/cases/$id")
        assertThat(body).doesNotContain("Swimming lessons begin")
    }
    @Test
    fun `named child follow-up filters siblings before building model context`() {
        every { access.accessibleKidIds(any()) } returns listOf(kidId, otherKid)
        val jamie = SchoolCase(childId = kidId, title = "Jamie concert")
        val taylor = SchoolCase(childId = otherKid, title = "Taylor trip")
        every { caseRepo.findAll() } returns listOf(jamie, taylor)
        every { actionRepo.findAll() } returns listOf(
            ParentAction(caseId = jamie.id, title = "Return concert form", dedupKey = "jamie-form"),
            ParentAction(caseId = taylor.id, title = "Return trip form", dedupKey = "taylor-form"),
        )
        val prompt = slot<String>()
        every { router.available(any()) } returns true
        every { router.complete(any(), any(), capture(prompt), any()) } returns
            """{"sections":[{"text":"• Taylor: return trip form","caseId":"${taylor.id}"}]}"""
        val answer = qa.answer("and Taylor?", user, listOf(ChatTurn("user", "pending for Jamie?")))
        val state = prompt.captured.substringBefore("Earlier in this chat:")
        assertThat(state).contains("Taylor trip", "Return trip form", "child_id", "child_needs")
        assertThat(state).doesNotContain("Jamie concert", "Return concert form")
        assertThat(answer.refs.map { it.id }).containsExactly(taylor.id)
        assertThat(answer.forWhatsApp("https://school.example.org/"))
            .isEqualTo("• Taylor: return trip form\n↗ https://school.example.org/cases/${taylor.id}")
    }

    @Test
    fun `structured reply keeps each source with its bullet and links each case once`() {
        val first = ChatRef(UUID.randomUUID(), "Full registration title")
        val second = ChatRef(UUID.randomUUID(), "Full payment title")
        val sections = listOf(
            ChatSection("• Register — Tuesday", listOf(first)),
            ChatSection("• Pay — Friday", listOf(second)),
            ChatSection("Bring the receipt too", listOf(second)),
        )
        val body = ChatAnswer("unused", listOf(first, second), sections).forWhatsApp("https://school.example.org")
        assertThat(body).isEqualTo("• Register — Tuesday\n↗ https://school.example.org/cases/${first.id}\n\n" +
            "• Pay — Friday\n↗ https://school.example.org/cases/${second.id}\n\nBring the receipt too")
        assertThat(body).doesNotContain("Full registration title", "Full payment title")
    }

    @Test
    fun `child scope is not inferred from substrings or unauthorized names`() {
        val mine = SchoolCase(childId = kidId, title = "Concert")
        val theirs = SchoolCase(childId = otherKid, title = "Private letter")
        every { caseRepo.findAll() } returns listOf(mine, theirs)
        every { actionRepo.findAll() } returns listOf(
            ParentAction(caseId = mine.id, title = "Return form", dedupKey = "mine"),
            ParentAction(caseId = theirs.id, title = "Private task", dedupKey = "theirs"),
        )
        val prompt = slot<String>()
        every { router.available(any()) } returns true
        every { router.complete(any(), any(), capture(prompt), any()) } returns
            """{"sections":[{"text":"No matching information","ids":[]}]}"""
        qa.answer("Tell me about Taylor", user)
        assertThat(prompt.captured.substringBefore("Question:")).doesNotContain("Private letter", "Private task")
        every { access.accessibleKidIds(any()) } returns listOf(kidId, otherKid)
        qa.answer("What is the Sarajevo trip?", user)
        assertThat(prompt.captured).contains("Concert", "Private letter")
    }

    @Test
    fun `structured source IDs cannot link outside the selected child`() {
        every { access.accessibleKidIds(any()) } returns listOf(kidId, otherKid)
        val jamie = SchoolCase(childId = kidId, title = "Concert")
        val taylor = SchoolCase(childId = otherKid, title = "Trip")
        every { caseRepo.findAll() } returns listOf(jamie, taylor)
        every { actionRepo.findAll() } returns listOf(ParentAction(caseId = taylor.id, title = "Return form", dedupKey = "form"))
        every { router.available(any()) } returns true
        every { router.complete(any(), any(), any(), any()) } returns
            """{"sections":[{"text":"Summary","caseId":"${jamie.id}"},{"text":"More","caseId":"invalid"}]}"""
        val answer = qa.answer("and Taylor?", user)
        assertThat(answer.refs).isEmpty()
        assertThat(answer.forWhatsApp("https://school.example.org")).doesNotContain("/cases/")
    }

}
