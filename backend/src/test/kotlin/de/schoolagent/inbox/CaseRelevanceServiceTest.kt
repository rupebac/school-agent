package de.schoolagent.inbox

import de.schoolagent.ai.AiUnavailableException
import de.schoolagent.ai.ModelRouter
import de.schoolagent.documents.DocumentStore
import de.schoolagent.domain.enums.*
import de.schoolagent.domain.model.*
import de.schoolagent.domain.repo.*
import io.mockk.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.*
import java.util.Optional
import java.util.UUID

class CaseRelevanceServiceTest {
    private val cases = mockk<SchoolCaseRepository>()
    private val sources = mockk<SourceRepository>()
    private val actions = mockk<ParentActionRepository>()
    private val events = mockk<SchoolEventRepository>()
    private val needs = mockk<ChildRequirementRepository>()
    private val estimates = mockk<CaseRelevanceRepository>()
    private val audit = mockk<AuditEntryRepository>(relaxed = true)
    private val settings = mockk<InboxSettingsService>()
    private var weeks = 4
    private val model = mockk<ModelRouter>()
    private val documents = mockk<DocumentStore>()
    private var now = Instant.parse("2026-10-03T12:00:00Z")
    private val clock = object : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private val c = SchoolCase(title = "Class information", summary = "General guidance", status = CaseStatus.INFORMATION_ONLY,
        informationOnly = true, openedAt = Instant.parse("2026-10-02T12:00:00Z"))
    private val source = Source(caseId = c.id, sourceType = SourceType.EMAIL, externalId = "email", dedupKey = "email",
        contentHash = "original", originalSentAt = Instant.parse("2026-09-10T10:00:00Z"), receivedAt = c.openedAt)
    private val aa = mutableListOf<ParentAction>()
    private val ee = mutableListOf<SchoolEvent>()
    private val rr = mutableListOf<ChildRequirement>()
    private val stored = mutableMapOf<UUID, StoredCaseRelevance>()
    private fun service() = CaseRelevanceService(cases, sources, actions, events, needs, estimates, audit, model, documents, clock, settings)
    private fun view() = service().current(listOf(c))[c.id]
    init {
        every { settings.current() } answers { InboxSettings(weeks) }
        every { audit.save(any<AuditEntry>()) } answers { firstArg() }
        every { cases.findById(c.id) } returns Optional.of(c)
        every { cases.lockById(c.id) } returns c
        every { sources.findByCaseId(c.id) } answers { listOf(source) }
        every { sources.findByCaseIdIn(any()) } answers { listOf(source) }
        every { actions.findByCaseId(c.id) } answers { aa.toList() }
        every { actions.findByCaseIdIn(any()) } answers { aa.toList() }
        every { events.findByCaseId(c.id) } answers { ee.toList() }
        every { events.findByCaseIdIn(any()) } answers { ee.toList() }
        every { needs.findByCaseId(c.id) } answers { rr.toList() }
        every { needs.findByCaseIdIn(any()) } answers { rr.toList() }
        every { estimates.findById(any()) } answers { Optional.ofNullable(stored[firstArg<UUID>()]) }
        every { estimates.findAllById(any()) } answers { firstArg<Iterable<UUID>>().mapNotNull { stored[it] } }
        every { estimates.save(any<StoredCaseRelevance>()) } answers { firstArg<StoredCaseRelevance>().also { stored[it.caseId] = it } }
        every { model.available(ModelRole.TEXT) } returns true
        every { model.complete(any(), any(), any(), any()) } returns """{"relevantUntil":null,"confidence":0.2,"reason":"No relevant period is stated."}"""
    }

    @Test fun `unknown end persists and uses four weeks from original school message not import`() {
        assertThat(service().refresh(c.id)).isTrue()
        assertThat(service().refresh(c.id)).isFalse()
        assertThat(view()!!.assessment).isEqualTo("UNKNOWN")
        assertThat(view()!!.reason).contains("unknown", "No relevant period")
        assertThat(view()!!.expiresAt).isEqualTo(Instant.parse("2026-10-07T22:00:00Z"))
        verify(exactly = 1) { model.complete(any(), any(), any(), any()) }
        assertThat(c.status).isEqualTo(CaseStatus.INFORMATION_ONLY)
        verify(exactly = 1) { audit.save(match { it.action == "CASE_RELEVANCE_ASSESSED" }) }
    }

    @Test fun `reads do no AI or mutations and expiry follows Berlin midnight across clock changes`() {
        service().refresh(c.id)
        now = Instant.parse("2026-10-07T21:59:59Z")
        assertThat(view()!!.archived).isFalse()
        now = now.plusSeconds(1)
        assertThat(view()!!.archived).isTrue()
        assertThat(service().refresh(c.id)).isFalse()
        verify(exactly = 1) { model.complete(any(), any(), any(), any()) }
        verify(exactly = 1) { estimates.save(any<StoredCaseRelevance>()) }
    }

    @Test fun `confident end is inclusive and respects the autumn DST transition`() {
        every { model.complete(any(), any(), any(), any()) } returns """{"relevantUntil":"2026-10-25","confidence":0.95,"reason":"The notice states the service ends on 25 October."}"""
        service().refresh(c.id)
        assertThat(view()!!.assessment).isEqualTo("ESTIMATED")
        assertThat(view()!!.expiresAt).isEqualTo(Instant.parse("2026-10-25T23:00:00Z"))
    }

    @Test fun `low confidence dates and invalid estimates fall back without repeated requests`() {
        every { model.complete(any(), any(), any(), any()) } returns """{"relevantUntil":"2026-10-04","confidence":0.6,"reason":"The period is uncertain."}"""
        service().refresh(c.id)
        assertThat(view()!!.assessment).isEqualTo("UNKNOWN")
        assertThat(view()!!.expiresAt).isEqualTo(Instant.parse("2026-10-07T22:00:00Z"))
        source.contentHash = "updated"
        every { model.complete(any(), any(), any(), any()) } returns """{"relevantUntil":"not a date","confidence":true,"reason":"bad"}"""
        service().refresh(c.id)
        assertThat(view()!!.assessment).isEqualTo("UNKNOWN")
        assertThat(service().refresh(c.id)).isFalse()
        verify(exactly = 3) { model.complete(any(), any(), any(), any()) }
    }

    @Test fun `unknown future concert stays visible until its final scheduled day`() {
        ee += SchoolEvent(caseId = c.id, title = "Concert", startTime = Instant.parse("2026-12-01T17:00:00Z"), eventType = EventType.MUSIC_EVENT, dedupKey = "e")
        every { model.complete(any(), any(), any(), any()) } returns """{"relevantUntil":"2026-10-04","confidence":0.99,"reason":"First notice is old."}"""
        service().refresh(c.id)
        assertThat(view()!!.expiresAt).isEqualTo(Instant.parse("2026-12-01T23:00:00Z"))
        assertThat(view()!!.archived).isFalse()
    }

    @Test fun `undated high priority action uses same rules without completing any work`() {
        c.informationOnly = false; c.status = CaseStatus.ACTION_REQUIRED; c.importanceScore = 5
        aa += ParentAction(caseId = c.id, title = "Return form", dedupKey = "a")
        now = Instant.parse("2026-11-01T12:00:00Z")
        service().refresh(c.id)
        assertThat(view()!!.archived).isTrue()
        assertThat(aa.single().status).isEqualTo(ActionStatus.OPEN)
        assertThat(c.status).isEqualTo(CaseStatus.ACTION_REQUIRED)
        aa.single().dueAt = Instant.parse("2026-11-05T12:00:00Z")
        assertThat(view()).isNull()
    }

    @Test fun `undated group matches preferred required action and protects a different dated action`() {
        c.informationOnly = false; c.status = CaseStatus.ACTION_REQUIRED
        aa += ParentAction(caseId = c.id, title = "Buy supplies", dedupKey = "required", required = true)
        aa += ParentAction(caseId = c.id, title = "Optional registration", dedupKey = "optional", required = false, dueAt = Instant.parse("2026-12-01T12:00:00Z"))
        assertThat(view()!!.expiresAt).isEqualTo(Instant.parse("2026-12-01T23:00:00Z"))
    }

    @Test fun `processed dated action and unprocessed or closed cases are not auto archived`() {
        c.informationOnly = false; c.status = CaseStatus.ACTION_REQUIRED
        aa += ParentAction(caseId = c.id, title = "Pay", dueAt = now.minusSeconds(86400), dedupKey = "a")
        assertThat(view()).isNull()
        aa.clear()
        ee += SchoolEvent(caseId = c.id, title = "Trip", startTime = now.plusSeconds(86400), eventType = EventType.SCHOOL_EVENT, dedupKey = "e")
        assertThat(view()).isNull()
        c.informationOnly = true; c.status = CaseStatus.READY_FOR_REASONING
        assertThat(view()).isNull()
        c.status = CaseStatus.COMPLETED
        assertThat(view()).isNull()
    }

    @Test fun `new evidence invalidates archive immediately and is assessed once for the new revision`() {
        every { model.complete(any(), any(), any(), any()) } returns """{"relevantUntil":"2026-09-20","confidence":0.9,"reason":"Temporary notice until 20 September."}"""
        service().refresh(c.id)
        assertThat(view()!!.archived).isTrue()
        source.contentHash = "new facts"; source.originalSentAt = now
        assertThat(view()!!.assessment).isEqualTo("PENDING")
        assertThat(view()!!.archived).isFalse()
        assertThat(service().refresh(c.id)).isTrue()
        assertThat(service().refresh(c.id)).isFalse()
        verify(exactly = 2) { model.complete(any(), any(), any(), any()) }
    }

    @Test fun `changed facts while model runs cannot persist a stale relevance estimate`() {
        every { model.complete(any(), any(), any(), any()) } answers {
            source.contentHash = "changed during assessment"
            """{"relevantUntil":null,"confidence":0.2,"reason":"No period stated."}"""
        }
        assertThat(service().refresh(c.id)).isFalse()
        assertThat(stored).isEmpty()
    }

    @Test fun `AI outage has persisted fallback and waits before retrying`() {
        every { model.complete(any(), any(), any(), any()) } throws AiUnavailableException("unavailable")
        service().refresh(c.id)
        assertThat(view()!!.assessment).isEqualTo("UNAVAILABLE")
        assertThat(service().refresh(c.id)).isFalse()
        verify(exactly = 1) { model.complete(any(), any(), any(), any()) }
        now = now.plusSeconds(1801)
        assertThat(service().refresh(c.id)).isTrue()
        verify(exactly = 2) { model.complete(any(), any(), any(), any()) }
    }

    @Test fun `policy changes update saved unknown and pending expiries without more AI or writes`() {
        service().refresh(c.id)
        weeks = 8
        val expanded = view()!!
        assertThat(expanded.fallbackWeeks).isEqualTo(8)
        assertThat(expanded.expiresAt).isEqualTo(Instant.parse("2026-11-04T23:00:00Z"))
        assertThat(service().refresh(c.id)).isFalse()
        weeks = 1
        assertThat(view()!!.archived).isTrue()
        stored.clear()
        assertThat(view()!!.assessment).isEqualTo("PENDING")
        assertThat(view()!!.expiresAt).isEqualTo(Instant.parse("2026-09-16T22:00:00Z"))
        verify(exactly = 1) { model.complete(any(), any(), any(), any()) }
        verify(exactly = 1) { estimates.save(any<StoredCaseRelevance>()) }
    }

    @Test fun `policy changes retain confident estimates and protect future dates`() {
        every { model.complete(any(), any(), any(), any()) } returns """{"relevantUntil":"2026-12-01","confidence":0.95,"reason":"The guidance ends on 1 December."}"""
        service().refresh(c.id)
        val estimated = view()!!.expiresAt
        weeks = 1
        assertThat(view()!!.expiresAt).isEqualTo(estimated)
        assertThat(service().refresh(c.id)).isFalse()
        stored.clear()
        ee += SchoolEvent(caseId = c.id, title = "Concert", startTime = Instant.parse("2026-12-01T17:00:00Z"), eventType = EventType.MUSIC_EVENT, dedupKey = "protected")
        assertThat(view()!!.expiresAt).isEqualTo(estimated)
        verify(exactly = 1) { model.complete(any(), any(), any(), any()) }
    }

    @Test fun `four week fallback crosses month end in calendar days`() {
        source.originalSentAt = Instant.parse("2026-01-31T12:00:00Z")
        assertThat(view()!!.expiresAt).isEqualTo(Instant.parse("2026-02-27T23:00:00Z"))
    }
}
