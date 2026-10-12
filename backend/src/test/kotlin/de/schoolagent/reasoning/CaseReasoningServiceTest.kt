package de.schoolagent.reasoning

import com.fasterxml.jackson.databind.ObjectMapper
import de.schoolagent.ai.ChatHttpClient
import de.schoolagent.ai.ModelRouter
import de.schoolagent.connectors.google.GoogleOAuthService
import de.schoolagent.domain.enums.CaseStatus
import de.schoolagent.domain.enums.EvidenceKind
import de.schoolagent.domain.enums.EventType
import de.schoolagent.domain.enums.Importance
import de.schoolagent.domain.enums.ModelRole
import de.schoolagent.domain.model.Evidence
import de.schoolagent.domain.model.ParentAction
import de.schoolagent.domain.model.SchoolCase
import de.schoolagent.domain.model.SchoolEvent
import de.schoolagent.domain.repo.ChildRepository
import de.schoolagent.domain.repo.ChildRequirementRepository
import de.schoolagent.domain.repo.EvidenceRepository
import de.schoolagent.domain.repo.ModelConfigRepository
import de.schoolagent.domain.repo.ParentActionRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.repo.SchoolEventRepository
import de.schoolagent.domain.service.CaseService
import de.schoolagent.events.CalendarProjectionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class CaseReasoningServiceTest {

    private val caseRepo = mockk<SchoolCaseRepository>(relaxed = true)
    private val caseService = mockk<CaseService>(relaxed = true)
    private val evidenceRepo = mockk<EvidenceRepository>(relaxed = true)
    private val actionRepo = mockk<ParentActionRepository>(relaxed = true)
    private val eventRepo = mockk<SchoolEventRepository>(relaxed = true)
    private val requirementRepo = mockk<ChildRequirementRepository>(relaxed = true)
    private val calendarProjection = mockk<CalendarProjectionService>(relaxed = true)
    private val oauth = mockk<GoogleOAuthService>()
    private val configRepo = mockk<ModelConfigRepository>()
    private val usageRepo = mockk<de.schoolagent.domain.repo.AiUsageRepository>(relaxed = true)
    private val sourceRepo = mockk<de.schoolagent.domain.repo.SourceRepository>(relaxed = true)
    private val docStore = object : de.schoolagent.documents.DocumentStore {
        override fun put(content: ByteArray): String = "doc"
        override fun get(ref: String): ByteArray? = null
        override fun exists(ref: String): Boolean = false
    }

    private val ruleRepo = mockk<de.schoolagent.domain.repo.FamilyRuleRepository>(relaxed = true)
    private val childRepo = mockk<ChildRepository>(relaxed = true)
    private val notifications = mockk<de.schoolagent.whatsapp.NotificationService>(relaxed = true)
    private val changeRepo = mockk<de.schoolagent.domain.repo.CaseChangeRepository>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC)
    private val mapper = ObjectMapper()

    private val knownActionKeys = mutableSetOf<String>()
    private val knownEventKeys = mutableSetOf<String>()
    private val savedActions = mutableListOf<ParentAction>()
    private val savedEvents = mutableListOf<SchoolEvent>()

    private lateinit var router: ModelRouter
    private lateinit var service: CaseReasoningService

    private fun routerWith(response: String?): ModelRouter {
        val chat = mockk<ChatHttpClient>()
        if (response != null) {
            every { chat.complete(any(), any(), any()) } returns mapper.readTree(
                """{"choices":[{"message":{"content":${mapper.writeValueAsString(response)}}}],"usage":{"prompt_tokens":100,"completion_tokens":50}}""",
            )
        }
        return ModelRouter(configRepo, usageRepo, chat, clock, baseUrl = "http://ai", apiKey = if (response != null) "key" else "")
    }

    @BeforeEach
    fun setUp() {
        knownActionKeys.clear()
        knownEventKeys.clear()
        savedActions.clear()
        savedEvents.clear()
        every { configRepo.findByRole(any()) } returns de.schoolagent.domain.model.ModelConfig(
            role = ModelRole.TEXT,
            provider = "openai",
            modelName = "test-model",
            enabled = true,
        )
        every { usageRepo.save(any<de.schoolagent.domain.model.AiUsage>()) } answers { firstArg() }
        every { changeRepo.save(any<de.schoolagent.domain.model.CaseChange>()) } answers { firstArg() }
        every { actionRepo.existsByDedupKey(any()) } answers { knownActionKeys.contains(firstArg<String>()) }
        every { actionRepo.save(any<ParentAction>()) } answers {
            val a = firstArg<ParentAction>()
            savedActions.add(a)
            knownActionKeys.add(a.dedupKey)
            a
        }
        every { eventRepo.existsByDedupKey(any()) } answers { knownEventKeys.contains(firstArg<String>()) }
        every { eventRepo.findByDedupKey(any()) } returns null
        every { eventRepo.save(any<SchoolEvent>()) } answers {
            val e = firstArg<SchoolEvent>()
            savedEvents.add(e)
            knownEventKeys.add(e.dedupKey)
            e
        }
        every { requirementRepo.existsByDedupKey(any()) } returns false
        every { requirementRepo.save(any()) } answers { firstArg() }
        every { caseRepo.save(any<SchoolCase>()) } answers { firstArg() }
        every { oauth.isConfigured() } returns false
        every { childRepo.findById(any()) } returns Optional.empty()
    }

    private fun svc(documents: de.schoolagent.documents.DocumentStore = docStore) = CaseReasoningService(
        caseRepo, caseService, evidenceRepo, sourceRepo, documents, actionRepo, eventRepo,
        requirementRepo, calendarProjection, oauth, router, childRepo, ruleRepo, notifications, changeRepo, clock,
    )

    private fun readyCase() = SchoolCase(
        id = UUID.randomUUID(),
        title = "Schullandheim",
        status = CaseStatus.READY_FOR_REASONING,
    )

    private fun evidence(case: SchoolCase, subject: String) = Evidence(
        caseId = case.id,
        sourceId = UUID.randomUUID(),
        kind = EvidenceKind.FACT,
        subject = subject,
        dedupKey = "ev-${subject.hashCode()}",
    )

    @Test fun `form URLs must be actual source destinations rather than letterhead websites or guessed paths`() {
        val c = readyCase()
        val form = "https://forms.example/register?id=42"
        val source = de.schoolagent.domain.model.Source(caseId = c.id, sourceType = de.schoolagent.domain.enums.SourceType.PORTAL_LETTER,
            externalId = "letter", dedupKey = "letter", contentHash = "letter", storageRef = "letter", mimeType = "text/html",
            fetchStatus = de.schoolagent.domain.enums.FetchStatus.FETCHED)
        val documents = mockk<de.schoolagent.documents.DocumentStore>()
        every { documents.get("letter") } returns """<p>Register for the school camp.</p><a href="$form">Camp registration</a><a href="https://school.example/">School homepage</a>""".toByteArray()
        every { sourceRepo.findByCaseId(c.id) } returns listOf(source)
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        router = routerWith("""{"relevance":"ACTION","score":4,"summary":"Three requests.","actions":[
          {"title":"Register for school camp","externalUrl":"$form"},
          {"title":"Pay museum fee","externalUrl":"https://school.example/"},
          {"title":"Submit photo permission","externalUrl":"https://forms.example/guessed"}],"events":[],"requirements":[]}""")
        svc(documents).reason(c.id)
        assertThat(savedActions.map { it.externalUrl }).containsExactly(form, null, null)
    }

    @Test
    fun `parent Info override survives reasoning and flags a new required action for review`() {
        router = routerWith("""{"relevance":"ACTION","score":4,"summary":"Reply is needed.","actions":[{"title":"Return consent","dueAt":"29.09.2026"}],"events":[],"requirements":[]}""")
        val c = readyCase().also { it.infoOverride = true }
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { evidenceRepo.findByCaseId(c.id) } returns listOf(evidence(c, "Reply needed"))
        svc().reason(c.id)
        assertThat(c.infoOverride).isTrue()
        assertThat(c.isInfo()).isTrue()
        assertThat(c.classificationReview).isTrue()
        verify { caseService.transition(c, CaseStatus.INFORMATION_ONLY, any(), any()) }
        assertThat(savedActions).hasSize(1)
    }

    @Test
    fun `model cannot merge a letter into another child's case`() {
        val target = readyCase().also { it.childId = UUID.randomUUID() }
        val source = readyCase().also { it.childId = UUID.randomUUID() }
        router = routerWith("""{"relevance":"INFO","score":2,"summary":"School notice.","mergeIntoCaseId":"${target.id}","actions":[],"events":[],"requirements":[]}""")
        every { caseRepo.findById(source.id) } returns Optional.of(source)
        every { caseRepo.findById(target.id) } returns Optional.of(target)
        every { evidenceRepo.findByCaseId(source.id) } returns listOf(evidence(source, "School notice"))
        svc().reason(source.id)
        verify(exactly = 0) { caseService.transition(source, CaseStatus.CANCELLED, any(), any()) }
        verify { caseService.transition(source, CaseStatus.INFORMATION_ONLY, any(), any()) }
    }

    @Test
    fun `model decision creates actions and events and moves the case to ACTION_REQUIRED`() {
        router = routerWith(
            """{"relevance":"ACTION","importance":"IMMEDIATE","summary":"Trip with registration deadline.",
                "actions":[{"title":"Confirm participation","dueAt":"29.09.2026","externalUrl":null}],
                "events":[{"title":"Schullandheim","date":"12.10.2026","type":"SCHOOL_EVENT"}],
                "requirements":[{"description":"Sleeping bag","on":"11.10.2026"}]}""",
        )
        service = svc()

        val c = readyCase()
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { evidenceRepo.findByCaseId(c.id) } returns listOf(evidence(c, "Datum: 29.09.2026"))
        every { sourceRepo.findByCaseId(c.id) } returns emptyList()

        val result = service.reason(c.id)

        assertThat(result.relevance).isEqualTo("ACTION")
        assertThat(result.ai).isTrue()
        assertThat(savedActions.single().title).isEqualTo("Confirm participation")
        assertThat(savedEvents.single().eventType).isEqualTo(EventType.SCHOOL_EVENT)
        assertThat(c.summary).contains("Trip")
        assertThat(c.importanceScore).isEqualTo(5)
        assertThat(c.importance).isEqualTo(Importance.IMMEDIATE)
        verify(exactly = 1) { caseService.transition(any<SchoolCase>(), eq(CaseStatus.ACTION_REQUIRED), any(), any()) }
        verify(exactly = 0) { calendarProjection.syncEvent(any()) } // google not connected
    }

    @Test
    fun `numeric score 4 maps to IMMEDIATE for notifications`() {
        router = routerWith(
            """{"relevance":"ACTION","score":4,"summary":"Form due this week.",
                "actions":[{"title":"Fill form","dueAt":null,"externalUrl":null,"required":true}],
                "events":[],"requirements":[]}""",
        )
        service = svc()
        val c = readyCase()
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { evidenceRepo.findByCaseId(c.id) } returns emptyList()
        every { sourceRepo.findByCaseId(any()) } returns emptyList()

        service.reason(c.id)

        assertThat(c.importanceScore).isEqualTo(4)
        assertThat(c.importance).isEqualTo(Importance.IMMEDIATE)
        assertThat(savedActions.single().required).isTrue()
    }

    @Test
    fun `NOISE decision becomes silent INFORMATION_ONLY`() {
        router = routerWith(
            """{"relevance":"NOISE","importance":"SILENT","summary":"Retrospective newsletter.",
                "actions":[],"events":[],"requirements":[]}""",
        )
        service = svc()

        val c = readyCase()
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { evidenceRepo.findByCaseId(c.id) } returns emptyList()
        every { sourceRepo.findByCaseId(any()) } returns emptyList()

        val result = service.reason(c.id)

        assertThat(result.relevance).isEqualTo("NOISE")
        assertThat(c.importance).isEqualTo(Importance.SILENT)
        assertThat(c.importanceScore).isEqualTo(1)
        assertThat(savedActions).isEmpty()
        verify(exactly = 1) { caseService.transition(any<SchoolCase>(), eq(CaseStatus.INFORMATION_ONLY), any(), any()) }
    }

    @Test
    fun `without an API key the deterministic fallback derives a deadline action`() {
        router = routerWith(response = null) // empty key → unavailable
        service = svc()

        val c = readyCase()
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { evidenceRepo.findByCaseId(c.id) } returns listOf(evidence(c, "Anmeldung bis 29.09.2026"))

        val result = service.reason(c.id)

        assertThat(result.ai).isFalse()
        assertThat(savedActions.single().dueAt?.atZone(java.time.ZoneId.of("Europe/Berlin"))?.toLocalDate())
            .isEqualTo(java.time.LocalDate.of(2026, 9, 29))
        verify(exactly = 1) { caseService.transition(any<SchoolCase>(), eq(CaseStatus.ACTION_REQUIRED), any(), any()) }
    }

    @Test
    fun `cases not in READY_FOR_REASONING are skipped idempotently`() {
        router = routerWith(response = null)
        service = svc()
        val c = SchoolCase(id = UUID.randomUUID(), title = "x", status = CaseStatus.COMPLETED)
        every { caseRepo.findById(c.id) } returns Optional.of(c)

        val result = service.reason(c.id)

        assertThat(result.relevance).isEqualTo("SKIPPED")
        assertThat(savedActions).isEmpty()
    }
    @Test
    fun `correction merges into the matched case instead of duplicating`() {
        val targetId = UUID.randomUUID()
        val target = SchoolCase(id = targetId, title = "Schullandheim", status = CaseStatus.ACTION_REQUIRED)
        router = routerWith(
            """{"relevance":"INFO","importance":"DIGEST","summary":"Moved to 12.10.",
                "mergeIntoCaseId":"$targetId","actions":[],
                "events":[{"title":"Schullandheim","date":"12.10.2026","type":"SCHOOL_EVENT"}],
                "requirements":[]}""",
        )
        service = svc()

        val source = readyCase()
        every { caseRepo.findById(source.id) } returns Optional.of(source)
        every { caseRepo.findById(targetId) } returns Optional.of(target)
        every { caseRepo.findByStatusIn(any()) } returns listOf(target)
        every { sourceRepo.findByCaseId(any()) } returns emptyList()
        every { actionRepo.findByCaseId(any()) } returns emptyList()
        every { eventRepo.findByCaseId(any()) } returns emptyList()
        every { requirementRepo.findByCaseId(any()) } returns emptyList()

        service.reason(source.id)

        verify { caseService.transition(source, CaseStatus.CANCELLED, any(), match { it.contains("merged") }) }
        verify { caseService.transition(target, CaseStatus.INVESTIGATING, any(), any()) }
        verify { caseService.transition(target, CaseStatus.READY_FOR_REASONING, any(), any()) }
    }

    @Test
    fun `event clock time is stored not all-day and ISO dates parse`() {
        router = routerWith(
            """{"relevance":"INFO","score":2,"summary":"Autumn concert in the aula.",
                "title":"Autumn concert 15 Oct",
                "actions":[],
                "events":[{"title":"Autumn concert","date":"2026-10-15","time":"18:00","location":"Aula","type":"MUSIC_EVENT"}],
                "requirements":[]}""",
        )
        service = svc()
        val c = readyCase()
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { evidenceRepo.findByCaseId(c.id) } returns emptyList()
        every { sourceRepo.findByCaseId(any()) } returns emptyList()

        service.reason(c.id)

        val event = savedEvents.single()
        assertThat(event.allDay).isFalse()
        assertThat(event.location).isEqualTo("Aula")
        assertThat(event.eventType).isEqualTo(EventType.MUSIC_EVENT)
        assertThat(event.startTime.atZone(java.time.ZoneId.of("Europe/Berlin")).toLocalTime())
            .isEqualTo(java.time.LocalTime.of(18, 0))
        assertThat(c.title).isEqualTo("Autumn concert 15 Oct")
    }

    @Test
    fun `NOISE drops hallucinated calendar events`() {
        router = routerWith(
            """{"relevance":"NOISE","score":0,"summary":"Thanks for coming.",
                "actions":[],
                "events":[{"title":"Summer party","date":"01.07.2026","type":"SCHOOL_EVENT"}],
                "requirements":[{"description":"Cake","on":null}]}""",
        )
        service = svc()
        val c = readyCase()
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { evidenceRepo.findByCaseId(c.id) } returns emptyList()
        every { sourceRepo.findByCaseId(any()) } returns emptyList()

        service.reason(c.id)

        assertThat(savedEvents).isEmpty()
        assertThat(c.importanceScore).isEqualTo(0)
        verify(exactly = 0) { requirementRepo.save(any()) }
    }

    @Test
    fun `paraphrased weltatlas actions and deadline events collapse to one action`() {
        router = routerWith(
            """{"relevance":"ACTION","score":4,"summary":"Order the Diercke Weltatlas via Elternportal.",
                "title":"Diercke Weltatlas bulk order",
                "actions":[
                  {"title":"Answer binding survey on Diercke Weltatlas bulk order in Elternportal","dueAt":"23.09.2026","required":true},
                  {"title":"Answer the binding Elternportal survey on the Diercke Weltatlas bulk order (35.50 EUR)","dueAt":"23.09.2026","required":true}
                ],
                "events":[
                  {"title":"Deadline for Diercke Weltatlas order survey","date":"23.09.2026","type":"DEADLINE"},
                  {"title":"Deadline: Elternportal survey for Diercke Weltatlas bulk order","date":"23.09.2026","type":"DEADLINE"},
                  {"title":"Payment for Diercke Weltatlas (€35.50) if ordered","date":"23.09.2026","type":"PAYMENT"},
                  {"title":"Payment for Diercke Weltatlas (35.50 EUR) per attached payment modalities","date":"23.09.2026","type":"PAYMENT"}
                ],
                "requirements":[]}""",
        )
        service = svc()
        val c = readyCase()
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { evidenceRepo.findByCaseId(c.id) } returns emptyList()
        every { sourceRepo.findByCaseId(any()) } returns emptyList()
        every { actionRepo.findByCaseId(c.id) } returns emptyList()
        every { eventRepo.findByCaseId(c.id) } returns emptyList()

        service.reason(c.id)

        assertThat(savedActions).hasSize(1)
        assertThat(savedActions.single().title).contains("Weltatlas")
        assertThat(savedEvents).isEmpty()
    }

    @Test
    fun `a real occasion stays alongside the parent action`() {
        router = routerWith(
            """{"relevance":"ACTION","score":3,"summary":"First day of school in the aula.",
                "actions":[{"title":"Bring child to first school day, meet 08:20 in the Aula","dueAt":"15.09.2026","required":true}],
                "events":[{"title":"First day of school 5th grade","date":"15.09.2026","type":"SCHOOL_EVENT"}],
                "requirements":[]}""",
        )
        service = svc()
        val c = readyCase()
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { evidenceRepo.findByCaseId(c.id) } returns emptyList()
        every { sourceRepo.findByCaseId(any()) } returns emptyList()
        every { actionRepo.findByCaseId(c.id) } returns emptyList()
        every { eventRepo.findByCaseId(c.id) } returns emptyList()

        service.reason(c.id)

        assertThat(savedActions).hasSize(1)
        assertThat(savedEvents).hasSize(1)
        assertThat(savedEvents.single().eventType).isEqualTo(EventType.SCHOOL_EVENT)
    }

    @Test
    fun `two events of the same type on the same day become one`() {
        router = routerWith(
            """{"relevance":"INFO","score":2,"summary":"First day of school.",
                "actions":[],
                "events":[
                  {"title":"First day of school 5th grade – assembly 08:20, lessons until 11:20","date":"15.09.2026","type":"SCHOOL_EVENT"},
                  {"title":"First day of school: meet 08:20 in Aula, lessons until 11:20","date":"15.09.2026","type":"SCHOOL_EVENT"}
                ],
                "requirements":[]}""",
        )
        service = svc()
        val c = readyCase()
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { evidenceRepo.findByCaseId(c.id) } returns emptyList()
        every { sourceRepo.findByCaseId(any()) } returns emptyList()
        every { actionRepo.findByCaseId(c.id) } returns emptyList()
        every { eventRepo.findByCaseId(c.id) } returns emptyList()

        service.reason(c.id)

        assertThat(savedEvents).hasSize(1)
        assertThat(savedEvents.single().title).contains("5th grade")
    }

    @Test
    fun `INFO letter without a task is capped at 1 not 3`() {
        router = routerWith(
            """{"relevance":"INFO","score":3,"summary":"Start of year welcome letter.",
                "actions":[],"events":[],"requirements":[]}""",
        )
        service = svc()
        val c = readyCase()
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { evidenceRepo.findByCaseId(c.id) } returns emptyList()
        every { sourceRepo.findByCaseId(any()) } returns emptyList()

        service.reason(c.id)

        assertThat(c.importanceScore).isEqualTo(1)
        assertThat(c.importance).isEqualTo(Importance.SILENT)
        assertThat(savedActions).isEmpty()
        verify(exactly = 1) { caseService.transition(any<SchoolCase>(), eq(CaseStatus.INFORMATION_ONLY), any(), any()) }
    }

    @Test
    fun `calendar-only INFO is capped at 2`() {
        router = routerWith(
            """{"relevance":"INFO","score":3,"summary":"Autumn concert in the aula.",
                "actions":[],
                "events":[{"title":"Autumn concert","date":"15.10.2026","type":"MUSIC_EVENT"}],
                "requirements":[]}""",
        )
        service = svc()
        val c = readyCase()
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { evidenceRepo.findByCaseId(c.id) } returns emptyList()
        every { sourceRepo.findByCaseId(any()) } returns emptyList()

        service.reason(c.id)

        assertThat(c.importanceScore).isEqualTo(2)
        assertThat(c.importance).isEqualTo(Importance.DIGEST)
        assertThat(savedEvents).hasSize(1)
    }

    @Test
    fun `undated data-sharing consent is quiet`() {
        router = routerWith(
            """{"relevance":"ACTION","score":3,"summary":"Please confirm data sharing in the portal.",
                "actions":[{"title":"Approve data sharing","dueAt":null,"required":true}],
                "events":[],"requirements":[]}""",
        )
        service = svc()
        val c = readyCase()
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { evidenceRepo.findByCaseId(c.id) } returns emptyList()
        every { sourceRepo.findByCaseId(any()) } returns emptyList()

        service.reason(c.id)

        assertThat(c.importanceScore).isEqualTo(1)
        assertThat(c.importance).isEqualTo(Importance.SILENT)
        assertThat(savedActions).hasSize(1)
    }

    @Test
    fun `child kit keeps a working score of 3`() {
        router = routerWith(
            """{"relevance":"ACTION","score":3,"summary":"Swimming kit for next week's lesson.",
                "actions":[],"events":[],
                "requirements":[{"description":"Swim kit","on":"01.10.2026","required":true}]}""",
        )
        service = svc()
        val c = readyCase()
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { evidenceRepo.findByCaseId(c.id) } returns emptyList()
        every { sourceRepo.findByCaseId(any()) } returns emptyList()

        service.reason(c.id)

        assertThat(c.importanceScore).isEqualTo(3)
        assertThat(c.importance).isEqualTo(Importance.DIGEST)
    }

    @Test
    fun `missing JSON score is 1 not a digest 3`() {
        router = routerWith(
            """{"relevance":"INFO","summary":"School newsletter.","actions":[],"events":[],"requirements":[]}""",
        )
        service = svc()
        val c = readyCase()
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { evidenceRepo.findByCaseId(c.id) } returns emptyList()
        every { sourceRepo.findByCaseId(any()) } returns emptyList()

        service.reason(c.id)

        assertThat(c.importanceScore).isEqualTo(1)
        assertThat(c.importance).isEqualTo(Importance.SILENT)
    }

    @Test
    fun `invented review-the-circular actions do not keep a 3`() {
        router = routerWith(
            """{"relevance":"ACTION","score":3,"summary":"Start of year circular.",
                "actions":[{"title":"Review circular no. 1 and attached forms","dueAt":null,"required":true}],
                "events":[],"requirements":[]}""",
        )
        service = svc()
        val c = readyCase()
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { evidenceRepo.findByCaseId(c.id) } returns emptyList()
        every { sourceRepo.findByCaseId(any()) } returns emptyList()

        service.reason(c.id)

        assertThat(c.importanceScore).isEqualTo(1)
        assertThat(c.importance).isEqualTo(Importance.SILENT)
    }

    @Test
    fun `INFO with a leftover child note is still quiet`() {
        router = routerWith(
            """{"relevance":"INFO","score":3,"summary":"Optional mathegym login.",
                "actions":[],"events":[],
                "requirements":[{"description":"Try mathegym if you like","on":null,"required":false}]}""",
        )
        service = svc()
        val c = readyCase()
        every { caseRepo.findById(c.id) } returns Optional.of(c)
        every { evidenceRepo.findByCaseId(c.id) } returns emptyList()
        every { sourceRepo.findByCaseId(any()) } returns emptyList()

        service.reason(c.id)

        assertThat(c.importanceScore).isEqualTo(1)
        assertThat(c.importance).isEqualTo(Importance.SILENT)
    }

    @Test
    fun `recalibrateStoredScores lowers leftover FYI letters`() {
        router = routerWith(response = null)
        service = svc()
        val c = SchoolCase(
            title = "Parents council election",
            status = CaseStatus.INFORMATION_ONLY,
            importance = Importance.DIGEST,
            importanceScore = 3,
        )
        every { caseRepo.findByStatusIn(any()) } returns listOf(c)
        every { actionRepo.findByCaseId(c.id) } returns emptyList()
        every { eventRepo.findByCaseId(c.id) } returns emptyList()
        every { requirementRepo.findByCaseId(c.id) } returns emptyList()

        val n = service.recalibrateStoredScores()

        assertThat(n).isEqualTo(1)
        assertThat(c.importanceScore).isEqualTo(1)
        assertThat(c.importance).isEqualTo(Importance.SILENT)
    }

    @Test fun `letter importance sweep preserves authoritative native exam and report priorities`() {
        router = routerWith(response = null); service = svc()
        val exam = SchoolCase(title = "Mathematics exam", status = CaseStatus.INFORMATION_ONLY, importanceScore = 4)
        val report = SchoolCase(title = "Forgotten homework", status = CaseStatus.INFORMATION_ONLY, importanceScore = 4)
        val general = SchoolCase(title = "General information", status = CaseStatus.INFORMATION_ONLY, importanceScore = 4)
        every { caseRepo.findByStatusIn(any()) } returns listOf(exam, report, general)
        every { sourceRepo.findByCaseIdIn(any()) } returns listOf(
            de.schoolagent.domain.model.Source(caseId = exam.id, sourceType = de.schoolagent.domain.enums.SourceType.ELTERNPORTAL_RECORD,
                externalId = "exam", dedupKey = "native-exam", contentHash = "exam"),
            de.schoolagent.domain.model.Source(caseId = report.id, sourceType = de.schoolagent.domain.enums.SourceType.SCHULMANAGER_RECORD,
                externalId = "report", dedupKey = "native-report", contentHash = "report"))
        assertThat(service.recalibrateStoredScores()).isEqualTo(1)
        assertThat(exam.importanceScore).isEqualTo(4)
        assertThat(report.importanceScore).isEqualTo(4)
        assertThat(general.importanceScore).isEqualTo(1)
    }
}
