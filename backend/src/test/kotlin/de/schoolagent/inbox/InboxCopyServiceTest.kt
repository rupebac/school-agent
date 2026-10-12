package de.schoolagent.inbox

import de.schoolagent.ai.ModelRouter
import de.schoolagent.domain.enums.*
import de.schoolagent.domain.model.*
import de.schoolagent.domain.repo.*
import io.mockk.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID

class InboxCopyServiceTest {
    private val cases = mockk<SchoolCaseRepository>()
    private val actions = mockk<ParentActionRepository>()
    private val events = mockk<SchoolEventRepository>()
    private val needs = mockk<ChildRequirementRepository>()
    private val copies = mockk<InboxCopyRepository>()
    private val model = mockk<ModelRouter>()
    private val case = SchoolCase(title = "Buy class book by 5 October", summary = "Used copies are fine.", status = CaseStatus.ACTION_REQUIRED)
    private val action = ParentAction(caseId = case.id, dedupKey = "test", title = "Buy the book by 5 October", dueAt = Instant.parse("2026-10-05T21:59:00Z"))
    private val stored = mutableMapOf<UUID, StoredInboxCopy>()
    private fun service() = InboxCopyService(cases, actions, events, needs, copies, model,
        Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), java.time.ZoneOffset.UTC))
    private val output get() = """{"title":"Class reading book","description":"Used copies are accepted.","activity":null,"abbreviations":{},"actions":{"${action.id}":"Buy the class book"},"reminders":{"${action.dueAt!!.atZone(java.time.ZoneId.of("Europe/Berlin")).toLocalDate()}":"Buy the class book"}}"""
    init {
        every { cases.findById(case.id) } returns Optional.of(case)
        every { actions.findByCaseIdIn(any()) } returns listOf(action)
        every { events.findByCaseIdIn(any()) } returns emptyList()
        every { needs.findByCaseIdIn(any()) } returns emptyList()
        every { copies.findById(any()) } answers { Optional.ofNullable(stored[firstArg<UUID>()]) }
        every { copies.findAllById(any()) } answers { firstArg<Iterable<UUID>>().mapNotNull { stored[it] } }
        every { copies.save(any<StoredInboxCopy>()) } answers { firstArg<StoredInboxCopy>().also { stored[it.caseId] = it } }
        every { model.available(ModelRole.TEXT) } returns true
        every { model.complete(ModelRole.TEXT, any(), any(), any()) } answers { output }
    }

    @Test fun `reads never generate copy and persisted output survives service recreation`() {
        assertThat(service().current(listOf(case))).isEmpty()
        verify(exactly = 0) { model.complete(any(), any(), any(), any()) }
        assertThat(service().refresh(case.id)).isTrue()
        assertThat(service().refresh(case.id)).isFalse()
        assertThat(service().current(listOf(case)).getValue(case.id).title).isEqualTo("Class reading book")
        assertThat(case.title).isEqualTo("Buy class book by 5 October")
        assertThat(action.title).isEqualTo("Buy the book by 5 October")
        verify(exactly = 1) { model.complete(any(), any(), any(), any()) }
    }

    @Test fun `combined reminder is persisted and matches only its exact date and active facts`() {
        val need = ChildRequirement(caseId = case.id, description = "Bring the same reading book", relevantOn = java.time.LocalDate.of(2026, 10, 5), dedupKey = "need")
        every { needs.findByCaseIdIn(any()) } returns listOf(need)
        every { model.complete(any(), any(), any(), any()) } returns output.replace("Buy the class book\"}}", "Buy and bring the class book\"}}")
        service().refresh(case.id)
        val copy = service().current(listOf(case)).getValue(case.id)
        assertThat(copy.reminder("2026-10-05", listOf(action), emptyList(), listOf(need))).isEqualTo("Buy and bring the class book")
        assertThat(copy.reminder("2026-10-06", listOf(action), emptyList(), listOf(need))).isNull()
        assertThat(copy.reminder("2026-10-05", emptyList(), emptyList(), listOf(need))).isNull()
        assertThat(service().refresh(case.id)).isFalse()
        verify(exactly = 1) { model.complete(any(), any(), any(), any()) }
    }

    @Test fun `small character-count overrun preserves the instruction and reuses the cached repair`() {
        val json = com.fasterxml.jackson.databind.ObjectMapper()
        fun response(length: Int): String {
            val root = json.readTree(output) as com.fasterxml.jackson.databind.node.ObjectNode
            (root.path("reminders") as com.fasterxml.jackson.databind.node.ObjectNode).put("2026-10-05", "R".repeat(length))
            return root.toString()
        }
        every { model.complete(any(), any(), any(), "inbox-copy") } returns response(101)
        every { model.complete(any(), any(), any(), "inbox-copy-repair") } returns response(95)
        assertThat(service().refresh(case.id)).isTrue()
        assertThat(service().current(listOf(case)).getValue(case.id).reminders.getValue("2026-10-05").text).hasSize(95)
        assertThat(service().refresh(case.id)).isFalse()
        verify(exactly = 1) { model.complete(any(), any(), any(), "inbox-copy-repair") }
    }

    @Test fun `legacy display copy remains readable while missing reminders upgrade once`() {
        service().refresh(case.id)
        val saved = stored.getValue(case.id)
        val tree = com.fasterxml.jackson.databind.ObjectMapper().readTree(saved.payload) as com.fasterxml.jackson.databind.node.ObjectNode
        tree.remove("reminders")
        saved.payload = tree.toString()
        assertThat(service().current(listOf(case)).getValue(case.id).title).isEqualTo("Class reading book")
        assertThat(service().refresh(case.id)).isTrue()
        assertThat(service().refresh(case.id)).isFalse()
    }

    @Test fun `copy input uses Berlin dates rather than the previous UTC calendar day`() {
        action.dueAt = Instant.parse("2026-10-04T22:00:00Z")
        service().refresh(case.id)
        verify { model.complete(any(), any(), match {
            it.contains("2026-10-05T00:00+02:00") && it.contains("Europe/Berlin") && !it.contains("2026-10-04")
        }, any()) }
    }

    @Test fun `completion does not regenerate copy but edited facts invalidate it immediately`() {
        service().refresh(case.id)
        action.status = ActionStatus.COMPLETED
        case.status = CaseStatus.COMPLETED
        assertThat(service().refresh(case.id)).isFalse()
        assertThat(service().current(listOf(case))).hasSize(1)
        action.dueAt = Instant.parse("2026-10-06T21:59:00Z")
        assertThat(service().current(listOf(case))).isEmpty()
        assertThat(service().refresh(case.id)).isTrue()
        verify(exactly = 2) { model.complete(any(), any(), any(), any()) }
    }

    @Test fun `older acronym copy upgrades once while reads stay fast and facts remain intact`() {
        case.title = "ADHS parents' evening"
        case.summary = "Register for a talk about ADHS."
        val explained = output.replace("Class reading book", "ADHD parents' evening")
            .replace("\"abbreviations\":{}", "\"abbreviations\":{\"ADHD\":\"attention deficit hyperactivity disorder\"}")
        every { model.complete(any(), any(), any(), any()) } returns explained
        service().refresh(case.id)
        val old = stored.getValue(case.id)
        val json = com.fasterxml.jackson.databind.ObjectMapper()
        val payload = json.readTree(old.payload) as com.fasterxml.jackson.databind.node.ObjectNode
        payload.remove("plainLanguageReviewed")
        payload.remove("abbreviations")
        payload.remove("activity")
        payload.put("title", "ADHS parents' evening")
        old.payload = payload.toString()
        assertThat(service().current(listOf(case)).getValue(case.id).title).isEqualTo("ADHS parents' evening")
        verify(exactly = 1) { model.complete(any(), any(), any(), any()) }
        assertThat(service().refresh(case.id)).isTrue()
        assertThat(service().refresh(case.id)).isFalse()
        val refreshed = service().current(listOf(case)).getValue(case.id)
        assertThat(refreshed.title).contains("ADHD (attention deficit hyperactivity disorder)")
        assertThat(refreshed.plainLanguageReviewed).isTrue()
        assertThat(case.title).isEqualTo("ADHS parents' evening")
        assertThat(action.title).isEqualTo("Buy the book by 5 October")
        verify(exactly = 2) { model.complete(any(), any(), any(), any()) }
    }

    @Test fun `uncertain abbreviation review is persisted even when the venue name stays unchanged`() {
        case.title = "XYZ family centre evening"
        every { model.complete(any(), any(), any(), any()) } returns output.replace("Class reading book", case.title).replace("\"abbreviations\":{}", "\"abbreviations\":{\"XYZ\":null}")
        assertThat(service().refresh(case.id)).isTrue()
        assertThat(service().refresh(case.id)).isFalse()
        assertThat(service().current(listOf(case)).getValue(case.id).title).isEqualTo(case.title)
        verify(exactly = 1) { model.complete(any(), any(), any(), any()) }
    }

    @Test fun `older copy without acronyms is not regenerated for the language improvement`() {
        service().refresh(case.id)
        val old = stored.getValue(case.id)
        val payload = com.fasterxml.jackson.databind.ObjectMapper().readTree(old.payload) as com.fasterxml.jackson.databind.node.ObjectNode
        payload.remove("plainLanguageReviewed")
        old.payload = payload.toString()
        assertThat(service().refresh(case.id)).isFalse()
        verify(exactly = 1) { model.complete(any(), any(), any(), any()) }
    }

    @Test fun `upcoming event copy receives the clearer purpose review once even without an acronym`() {
        case.title = "Parents' evening"
        case.summary = "Meet the teachers and discuss the coming school year."
        every { events.findByCaseIdIn(any()) } returns listOf(SchoolEvent(caseId = case.id, title = "Parents' evening",
            eventType = EventType.PARENT_EVENT, startTime = Instant.parse("2026-10-16T16:00:00Z"), dedupKey = "evening"))
        val json = com.fasterxml.jackson.databind.ObjectMapper()
        val response = json.readTree(output) as com.fasterxml.jackson.databind.node.ObjectNode
        response.put("title", "Parents' evening")
        response.put("description", "Meet the teachers and discuss the coming school year.")
        response.put("activity", "Meet the teachers and discuss the coming school year.")
        (response.path("reminders") as com.fasterxml.jackson.databind.node.ObjectNode).put("2026-10-16", "Meet the teachers at 18:00")
        every { model.complete(any(), any(), any(), any()) } returns response.toString()
        service().refresh(case.id)
        val old = stored.getValue(case.id)
        val payload = json.readTree(old.payload) as com.fasterxml.jackson.databind.node.ObjectNode
        payload.remove("plainLanguageReviewed")
        payload.put("description", "Speaker: the headteacher.")
        old.payload = payload.toString()
        assertThat(service().refresh(case.id)).isTrue()
        assertThat(service().refresh(case.id)).isFalse()
        assertThat(service().current(listOf(case)).getValue(case.id).description).startsWith("Meet the teachers")
        verify(exactly = 2) { model.complete(any(), any(), any(), any()) }
    }

    @Test fun `known meanings are rendered on first mention while unknown venue codes remain intact`() {
        val raw = """{"title":"ADHD (Attention deficit hyperactivity disorder) evening at XYZ","description":"Questions about ADHD.","activity":"Understanding ADHD and supporting children.","actions":{},"reminders":{"2026-10-16":"ADHD parents' evening"},"abbreviations":{"ADHD":"attention deficit hyperactivity disorder","XYZ":null}}"""
        val parsed = service().parse(raw, emptySet(), requireLanguageFields = true)
        assertThat(parsed.title).isEqualTo("ADHD (attention deficit hyperactivity disorder) evening at XYZ")
        assertThat(parsed.activity).isEqualTo("Understanding ADHD and supporting children.")
        assertThat(parsed.description).isEqualTo("Questions about ADHD.")
        assertThat(parsed.reminders.getValue("2026-10-16").text).contains("ADHD (attention deficit hyperactivity disorder)")
        assertThat(parsed.abbreviations["XYZ"]).isNull()
    }

    @Test fun `missing acronym meanings cannot silently pass review and the repaired result is reused`() {
        case.title = "ADHD parents' evening"
        val raw = output.replace("Class reading book", case.title)
        every { model.complete(any(), any(), any(), "inbox-copy") } returns raw
        every { model.complete(any(), any(), any(), "inbox-copy-repair") } returns raw.replace("\"abbreviations\":{}", "\"abbreviations\":{\"ADHD\":\"attention deficit hyperactivity disorder\"}")
        assertThat(service().refresh(case.id)).isTrue()
        assertThat(service().refresh(case.id)).isFalse()
        assertThat(service().current(listOf(case)).getValue(case.id).title).contains("ADHD (attention deficit hyperactivity disorder)")
        verify(exactly = 1) { model.complete(any(), any(), any(), "inbox-copy-repair") }
    }

    @Test fun `unused source glossary entries do not invalidate translated or simplified copy`() {
        val raw = output.replace("Class reading book", "ADHD evening")
            .replace("\"abbreviations\":{}", "\"abbreviations\":{\"ADHD\":\"attention deficit hyperactivity disorder\",\"ADHS\":\"German source acronym\",\"i-Net Menue\":null}")
        val parsed = service().parse(raw, setOf(action.id.toString()), requireLanguageFields = true)
        assertThat(parsed.title).contains("ADHD (attention deficit hyperactivity disorder)")
        assertThat(parsed.abbreviations.keys).containsExactly("ADHD")
    }

    @Test fun `malformed JSON receives one repair and subsequent reads reuse the saved copy`() {
        every { model.complete(any(), any(), any(), "inbox-copy") } returns "{\"title\":\"Book\", invalid JSON}"
        every { model.complete(any(), any(), any(), "inbox-copy-repair") } answers { output }
        assertThat(service().refresh(case.id)).isTrue()
        assertThat(service().refresh(case.id)).isFalse()
        assertThat(service().current(listOf(case)).getValue(case.id).title).isEqualTo("Class reading book")
        verify(exactly = 1) { model.complete(any(), match { it.contains("Malformed JSON") }, any(), "inbox-copy-repair") }
    }

    @Test fun `missing required description receives the same bounded repair`() {
        val raw = com.fasterxml.jackson.databind.ObjectMapper().readTree(output) as com.fasterxml.jackson.databind.node.ObjectNode
        raw.remove("description")
        every { model.complete(any(), any(), any(), "inbox-copy") } returns raw.toString()
        every { model.complete(any(), any(), any(), "inbox-copy-repair") } answers { output }
        assertThat(service().refresh(case.id)).isTrue()
        assertThat(service().refresh(case.id)).isFalse()
        verify(exactly = 1) { model.complete(any(), any(), any(), "inbox-copy-repair") }
    }

    @Test fun `expanded standalone reminders survive persistence and subsequent reads without re-expansion`() {
        case.title = "ADHD evening"
        val json = com.fasterxml.jackson.databind.ObjectMapper()
        val raw = json.readTree(output) as com.fasterxml.jackson.databind.node.ObjectNode
        raw.put("title", case.title)
        raw.putObject("abbreviations").put("ADHD", "attention deficit hyperactivity disorder")
        (raw.path("reminders") as com.fasterxml.jackson.databind.node.ObjectNode).put("2026-10-05", "ADHD " + "R".repeat(75))
        every { model.complete(any(), any(), any(), any()) } returns raw.toString()
        assertThat(service().refresh(case.id)).isTrue()
        val text = service().current(listOf(case)).getValue(case.id).reminders.getValue("2026-10-05").text
        assertThat(text.length).isGreaterThan(100)
        assertThat(service().current(listOf(case)).getValue(case.id).reminders.getValue("2026-10-05").text).isEqualTo(text)
        assertThat(service().refresh(case.id)).isFalse()
        verify(exactly = 1) { model.complete(any(), any(), any(), any()) }
    }

    @Test fun `changing facts while AI runs discards the obsolete answer`() {
        every { model.complete(any(), any(), any(), any()) } answers {
            action.title = "Buy a different book"
            output
        }
        assertThat(service().refresh(case.id)).isFalse()
        assertThat(stored).isEmpty()
    }

    @Test fun `null description is a successful result and is cached`() {
        every { model.complete(any(), any(), any(), any()) } returns output.replace("\"Used copies are accepted.\"", "null")
        service().refresh(case.id)
        assertThat(service().current(listOf(case)).getValue(case.id).description).isNull()
        assertThat(service().refresh(case.id)).isFalse()
    }

    @Test fun `rejects invented action ids and malformed output without saving partial copy`() {
        every { model.complete(any(), any(), any(), any()) } returns """{"title":"Book","description":null,"actions":{"invented":"Pay"}}"""
        assertThatThrownBy { service().refresh(case.id) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(stored).isEmpty()
        assertThatThrownBy { service().parse("""{"title":"Book","actions":{}}""", emptySet()) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `repairs an invalid cached response once and reuses the saved copy thereafter`() {
        every { model.complete(any(), any(), any(), "inbox-copy") } returns
            """{"title":"Book","description":null,"actions":{"wrong-id":"Buy book"}}"""
        every { model.complete(any(), any(), any(), "inbox-copy-repair") } answers { output }
        assertThat(service().refresh(case.id)).isTrue()
        assertThat(service().refresh(case.id)).isFalse()
        assertThat(service().current(listOf(case)).getValue(case.id).actions.keys).containsExactly(action.id.toString())
        verify(exactly = 1) { model.complete(any(), any(), any(), "inbox-copy-repair") }
    }

    @Test fun `info classification excludes leftover tasks from generated instructions`() {
        case.infoOverride = true
        every { model.complete(any(), any(), any(), any()) } returns """{"title":"Reading update","description":null,"actions":{},"reminders":{}}"""
        assertThat(service().refresh(case.id)).isTrue()
        assertThat(service().current(listOf(case)).getValue(case.id).actions).isEmpty()
        verify { model.complete(any(), any(), match { it.contains("\"actions\":[]") }, any()) }
    }
}
