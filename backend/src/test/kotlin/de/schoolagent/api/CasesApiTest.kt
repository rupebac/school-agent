package de.schoolagent.api

import io.mockk.*
import de.schoolagent.common.DedupKeys
import de.schoolagent.connectors.school.*
import de.schoolagent.ingestion.SchoolRecordInterpreter
import de.schoolagent.ingestion.SchoolRecordIngestionService
import de.schoolagent.domain.enums.*
import de.schoolagent.documents.DocumentStore
import de.schoolagent.domain.enums.CaseCategory
import de.schoolagent.domain.enums.CaseStatus
import de.schoolagent.domain.enums.SourceType
import de.schoolagent.domain.model.ParentAction
import de.schoolagent.domain.model.Source
import de.schoolagent.domain.repo.ParentActionRepository
import de.schoolagent.domain.repo.SchoolCaseRepository
import de.schoolagent.domain.repo.SourceRepository
import de.schoolagent.domain.service.CaseService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

/**
 * Full HTTP stack against real PostgreSQL: security boundary (401 anonymous),
 * seeded-user basic auth, and the case read API over data written through
 * CaseService — the vertically usable slice of M1.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CasesApiTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17")
    }

    @Autowired lateinit var caseService: CaseService
    @Autowired lateinit var caseRepo: SchoolCaseRepository
    @Autowired lateinit var sourceRepo: SourceRepository
    @Autowired lateinit var actionRepo: ParentActionRepository
    @Autowired lateinit var documents: DocumentStore
    @Autowired lateinit var settings: de.schoolagent.domain.repo.SettingRepository
    @Autowired lateinit var translations: de.schoolagent.domain.repo.SourceTranslationRepository
    @Autowired lateinit var usage: de.schoolagent.domain.repo.AiUsageRepository
    @Autowired lateinit var relevance: de.schoolagent.inbox.CaseRelevanceService

    @Autowired lateinit var requirementRepo: de.schoolagent.domain.repo.ChildRequirementRepository
    @Autowired lateinit var kidRepo: de.schoolagent.domain.repo.ChildRepository
    @Autowired lateinit var userRepo: de.schoolagent.domain.repo.AppUserRepository

    private val json = com.fasterxml.jackson.databind.ObjectMapper()
    private fun mutate(id: UUID, operation: String, body: String = "{}", username: String = morgan.first): ResponseEntity<String> {
        val headers = HttpHeaders().apply { setBasicAuth(username, morgan.second); contentType = org.springframework.http.MediaType.APPLICATION_JSON }
        return try { rest.exchange(url("/api/cases/$id/$operation"), HttpMethod.PUT, HttpEntity(body, headers), String::class.java) }
        catch (e: HttpClientErrorException) { ResponseEntity(e.responseBodyAsString, e.statusCode) }
    }
    private fun detail(id: UUID, username: String = morgan.first) = json.readTree(get("/api/cases/$id", username, morgan.second).body)

    @Autowired lateinit var nativeRecords: de.schoolagent.domain.repo.SchoolRecordRepository
    @Autowired lateinit var events: de.schoolagent.domain.repo.SchoolEventRepository
    @Autowired lateinit var changes: de.schoolagent.domain.repo.CaseChangeRepository
    @Autowired lateinit var histories: de.schoolagent.domain.repo.CaseStatusEntryRepository
    @Autowired lateinit var transactions: org.springframework.transaction.support.TransactionTemplate
    @Autowired lateinit var clock: java.time.Clock
    @Autowired lateinit var diagnostics: de.schoolagent.diagnostics.IntegrationDiagnostics
    @Autowired lateinit var auditEntries: de.schoolagent.domain.repo.AuditEntryRepository

    @Test fun `native exams persist once update in place preserve done and only complete snapshots withdraw dates`() {
        val kid = kidRepo.save(de.schoolagent.domain.model.Child(name = "Jamie", className = "7C"))
        val credentials = de.schoolagent.connectors.school.SchoolCredentials(de.schoolagent.connectors.school.SchoolProvider.SCHULMANAGER,
            "https://school.example", "parent", "password", "77", "88")
        val config = io.mockk.mockk<de.schoolagent.connectors.school.SchoolConnectionService>(relaxed = true)
        io.mockk.every { config.credentials(kid.id) } returns credentials
        io.mockk.every { config.hasRecordBaseline(any(), any(), any()) } returns true
        val interpreter = io.mockk.mockk<de.schoolagent.ingestion.SchoolRecordInterpreter>()
        io.mockk.every { interpreter.interpret(any()) } returns de.schoolagent.ingestion.SchoolRecordInterpreter.Copy("Mathematics exam", "A written mathematics assessment.", null, null)
        val notify = io.mockk.mockk<de.schoolagent.whatsapp.NotificationService>(relaxed = true)
        val oauth = io.mockk.mockk<de.schoolagent.connectors.google.GoogleOAuthService>()
        io.mockk.every { oauth.isConfigured(any()) } returns false
        val service = de.schoolagent.ingestion.SchoolRecordIngestionService(nativeRecords, sourceRepo, caseRepo, events, actionRepo, changes,
            caseService, kidRepo, config, documents, interpreter, notify, io.mockk.mockk(relaxed = true), oauth, transactions, clock)
        val scope = de.schoolagent.connectors.school.SchoolStudentScope(kid.name, kid.className, "77")
        val from = java.time.LocalDate.now().minusDays(7); val to = from.plusYears(1)
        val record = de.schoolagent.connectors.school.SchoolRecord("123", de.schoolagent.connectors.school.SchoolRecordKind.EXAM, "Schulaufgabe Mathematik",
            "Schulaufgabe Mathematik", from.plusDays(50), url = "https://school.example/exams")
        fun sync(rows: List<de.schoolagent.connectors.school.SchoolRecord>, status: de.schoolagent.connectors.school.SchoolFeedStatus = de.schoolagent.connectors.school.SchoolFeedStatus.AVAILABLE,
                 complete: Boolean = true) = service.sync(kid.id, credentials, scope,
            listOf(de.schoolagent.connectors.school.SchoolRecordFeed(record.kind, status, rows, complete)), from, to, false)
        assertThat(sync(listOf(record)).changed).isEqualTo(1)
        val stored = nativeRecords.findByNamespaceAndKindAndActiveTrue(service.namespace(kid.id, credentials, scope), record.kind).single()
        val c = caseRepo.findById(stored.caseId).orElseThrow()
        val event = events.findByCaseId(c.id).single()
        assertThat(event.eventType).isEqualTo(de.schoolagent.domain.enums.EventType.EXAM)
        assertThat(c.informationOnly).isTrue(); assertThat(c.importanceScore).isEqualTo(4); assertThat(c.investigationComplete).isTrue()
        assertThat(histories.findByCaseIdOrderByCreatedAtAsc(c.id).map { it.toStatus }).containsExactly(CaseStatus.NEW, CaseStatus.INGESTING,
            CaseStatus.READY_FOR_REASONING, CaseStatus.INFORMATION_ONLY)
        assertThat(sync(listOf(record)).changed).isZero()
        io.mockk.verify(exactly = 1) { interpreter.interpret(any()) }
        io.mockk.verify(exactly = 1) { notify.notifyDecision(any()) }
        caseService.transition(c, CaseStatus.COMPLETED)
        assertThat(sync(listOf(record)).changed).isZero()
        assertThat(caseRepo.findById(c.id).get().status).isEqualTo(CaseStatus.COMPLETED)
        assertThat(sync(listOf(record.copy(date = record.date!!.plusDays(1)))).changed).isEqualTo(1)
        assertThat(events.findByCaseId(c.id).single().id).isEqualTo(event.id)
        assertThat(events.findByCaseId(c.id).single().startTime).isEqualTo(record.date!!.plusDays(1).atStartOfDay(java.time.ZoneId.of("Europe/Berlin")).toInstant())
        assertThat(changes.findByCaseIdOrderByCreatedAtAsc(c.id)).anyMatch { it.beforeJson != null && it.afterJson != null }
        sync(emptyList(), de.schoolagent.connectors.school.SchoolFeedStatus.UNAVAILABLE)
        sync(emptyList(), complete = false)
        assertThat(caseRepo.findById(c.id).get().status).isEqualTo(CaseStatus.INFORMATION_ONLY)
        // A change of class/account namespace retires old future exams without touching historical ones.
        val newScope = scope.copy(studentId = "78")
        service.sync(kid.id, credentials, newScope, listOf(de.schoolagent.connectors.school.SchoolRecordFeed(record.kind,
            de.schoolagent.connectors.school.SchoolFeedStatus.AVAILABLE, emptyList(), complete = false)), from, to, false)
        assertThat(caseRepo.findById(c.id).get().status).isEqualTo(CaseStatus.INFORMATION_ONLY)
        assertThat(service.sync(kid.id, credentials, newScope, listOf(de.schoolagent.connectors.school.SchoolRecordFeed(record.kind,
            de.schoolagent.connectors.school.SchoolFeedStatus.AVAILABLE, emptyList(), complete = true)), from, to, false).changed).isEqualTo(1)
        assertThat(caseRepo.findById(c.id).get().status).isEqualTo(CaseStatus.CANCELLED)
        assertThat(events.findByCaseId(c.id).single().status).isEqualTo(de.schoolagent.domain.enums.EventStatus.CANCELLED)
    }

    private inner class NativeFixture(diagnostics: de.schoolagent.diagnostics.IntegrationDiagnostics? = null) {
        val kid = kidRepo.save(de.schoolagent.domain.model.Child(name = "Jamie", className = "7C"))
        val credentials = SchoolCredentials(SchoolProvider.SCHULMANAGER, "https://school.example", "parent", "password", "77", "88")
        val config = mockk<SchoolConnectionService>(relaxed = true)
        val baselines = mutableSetOf<Pair<String, SchoolRecordKind>>()
        val suppression = mutableListOf<Boolean>()
        val interpreter = mockk<SchoolRecordInterpreter>()
        val notify = mockk<de.schoolagent.whatsapp.NotificationService>(relaxed = true)
        val oauth = mockk<de.schoolagent.connectors.google.GoogleOAuthService>()
        val calendar = mockk<de.schoolagent.events.CalendarProjectionService>(relaxed = true)
        val today = java.time.LocalDate.of(2026, 10, 11)
        val fixedClock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-11T10:00:00Z"), java.time.ZoneOffset.UTC)
        val service: SchoolRecordIngestionService
        init {
            every { config.credentials(kid.id) } returns credentials
            every { config.hasRecordBaseline(kid.id, any(), any()) } answers { (secondArg<String>() to thirdArg<SchoolRecordKind>()) in baselines }
            every { config.recordBaseline(kid.id, any(), any()) } answers { baselines += secondArg<String>() to thirdArg<SchoolRecordKind>(); Unit }
            every { interpreter.interpret(any()) } answers {
                val record = firstArg<SchoolRecord>()
                SchoolRecordInterpreter.Copy(record.title, "An important school record.", if (record.kind == SchoolRecordKind.PAYMENT) "Pay copying fee" else null, record.dueDate)
            }
            every { notify.notifyDecision(any()) } answers {
                val c = firstArg<de.schoolagent.domain.model.SchoolCase>()
                suppression += c.suppressInitialNotification
                c.suppressInitialNotification = false; caseRepo.save(c); Unit
            }
            every { oauth.isConfigured(any()) } returns false
            service = SchoolRecordIngestionService(nativeRecords, sourceRepo, caseRepo, events, actionRepo, changes,
                caseService, kidRepo, config, documents, interpreter, notify, calendar, oauth, transactions, fixedClock, diagnostics)
        }
        fun scope() = SchoolStudentScope(kid.name, kid.className, "77")
        fun sync(vararg feeds: SchoolRecordFeed) = service.sync(kid.id, credentials, scope(), feeds.toList(), today, today.plusYears(1), false)
        fun row(kind: SchoolRecordKind = SchoolRecordKind.EXAM, id: String = "123", date: java.time.LocalDate = today.plusDays(20)) =
            SchoolRecord(id, kind, "Mathematics", "School record", date, url = "https://school.example/records")
        fun stored(kind: SchoolRecordKind) = nativeRecords.findByNamespaceAndKindAndActiveTrue(service.namespace(kid.id, credentials, scope()), kind).single()
        fun feed(vararg rows: SchoolRecord, complete: Boolean = false) = SchoolRecordFeed(rows.first().kind, SchoolFeedStatus.AVAILABLE, rows.toList(), complete)
    }

    @Test fun `diagnostics preserve observed past exams without importing them or changing quiet baselines`() {
        val f = NativeFixture(diagnostics)
        val run = UUID.randomUUID()
        val past = f.row(id = "past", date = f.today.minusDays(1))
        val future = f.row(id = "future")
        val result = f.service.sync(f.kid.id, f.credentials, f.scope(), listOf(f.feed(past, future, complete = true)),
            f.today, f.today.plusYears(1), false, run)
        val entries = auditEntries.findAll().filter { it.detail?.contains(run.toString()) == true }
        val snapshot = json.readTree(entries.single { it.action == "DIAGNOSTIC_SCHOOL_SNAPSHOT" }.detail)
        assertThat(snapshot.path("recordRefs").size()).isEqualTo(2)
        assertThat(snapshot.path("complete").asBoolean()).isTrue()
        assertThat(entries.map { json.readTree(it.detail).path("outcome").asText() })
            .contains("BEFORE_WINDOW", "APPLIED", "INITIAL_SNAPSHOT")
        val applied = json.readTree(entries.single { json.readTree(it.detail).path("outcome").asText() == "APPLIED" }.detail)
        assertThat(applied.path("caseRef").asText()).matches("[a-f0-9]{64}")
        assertThat(result.seen).isEqualTo(1)
        assertThat(nativeRecords.findByNamespaceAndKindAndActiveTrue(f.service.namespace(f.kid.id, f.credentials, f.scope()), SchoolRecordKind.EXAM)).hasSize(1)
        assertThat(f.suppression).containsExactly(true)
    }

    @Test fun `diagnostic retention removes only old diagnostics and preserves normal audit history`() {
        val cutoff = clock.instant().minus(java.time.Duration.ofDays(60))
        val oldDiagnostic = auditEntries.saveAndFlush(de.schoolagent.domain.model.AuditEntry(
            action = "DIAGNOSTIC_SCHOOL_RECORD", createdAt = cutoff.minusSeconds(1)))
        val oldHistory = auditEntries.saveAndFlush(de.schoolagent.domain.model.AuditEntry(
            action = "CASE_CREATED", createdAt = cutoff.minusSeconds(1)))
        val recentDiagnostic = auditEntries.saveAndFlush(de.schoolagent.domain.model.AuditEntry(
            action = "DIAGNOSTIC_SCHOOL_RECORD", createdAt = cutoff.plusSeconds(120)))
        diagnostics.prune()
        assertThat(auditEntries.existsById(oldDiagnostic.id)).isFalse()
        assertThat(auditEntries.existsById(oldHistory.id)).isTrue()
        assertThat(auditEntries.existsById(recentDiagnostic.id)).isTrue()
        assertThat(auditEntries.findRecentHistory(
            org.springframework.data.domain.PageRequest.of(0, 200)))
            .allMatch { !it.action.startsWith("DIAGNOSTIC_") }
    }

    @Test fun `failed business transactions do not erase diagnostic evidence`() {
        val run = UUID.randomUUID()
        transactions.executeWithoutResult { status ->
            diagnostics.record(de.schoolagent.diagnostics.DiagnosticTrace(de.schoolagent.diagnostics.DiagnosticStage.SCHOOL_SYNC,
                de.schoolagent.diagnostics.DiagnosticOutcome.FAILED, run = run))
            status.setRollbackOnly()
        }
        assertThat(auditEntries.findAll().any { it.detail?.contains(run.toString()) == true }).isTrue()
    }

    @Test fun `native section baselines are quiet independently of old letter sync and duplicates fail only their feed`() {
        val f = NativeFixture()
        val exam = f.row(); val report = f.row(SchoolRecordKind.FORGOTTEN_HOMEWORK, "report", f.today.minusDays(50))
        f.sync(f.feed(exam), f.feed(report))
        assertThat(f.suppression).containsExactly(true, true)
        assertThat(f.baselines.map { it.second }).containsExactlyInAnyOrder(SchoolRecordKind.EXAM, SchoolRecordKind.FORGOTTEN_HOMEWORK)
        clearMocks(f.notify, answers = false)
        f.suppression.clear()
        val duplicate = f.row(SchoolRecordKind.PAYMENT, "same")
        val addedExam = exam.copy(id = "124", date = exam.date!!.plusDays(1))
        val result = f.sync(f.feed(duplicate, duplicate), f.feed(exam, addedExam))
        assertThat(result.feeds.single { it.kind == SchoolRecordKind.PAYMENT }.status).isEqualTo(SchoolFeedStatus.FAILED)
        assertThat(result.feeds.single { it.kind == SchoolRecordKind.EXAM }.status).isEqualTo(SchoolFeedStatus.AVAILABLE)
        assertThat(f.suppression).containsExactly(false)
        val oldReport = report.copy(id = "report-2")
        f.sync(f.feed(report, oldReport))
        assertThat(f.suppression).containsExactly(false, true)
        assertThat(f.baselines.map { it.second }).doesNotContain(SchoolRecordKind.PAYMENT)
    }

    @Test fun `school year class changes preserve native payment and report cases without AI or notifications`() {
        val f = NativeFixture(); val payment = f.row(SchoolRecordKind.PAYMENT); val report = f.row(SchoolRecordKind.FORGOTTEN_MATERIAL)
        f.sync(f.feed(payment), f.feed(report))
        val before = listOf(f.stored(payment.kind).caseId, f.stored(report.kind).caseId)
        clearMocks(f.interpreter, f.notify, answers = false)
        f.kid.className = "8C"; kidRepo.save(f.kid)
        assertThat(f.sync(f.feed(payment), f.feed(report)).changed).isZero()
        assertThat(listOf(f.stored(payment.kind).caseId, f.stored(report.kind).caseId)).isEqualTo(before)
        verify(exactly = 0) { f.interpreter.interpret(any()) }
        verify(exactly = 0) { f.notify.notifyDecision(any()) }
    }

    @Test fun `native calendar failures retry active events and withdrawals without repeated notifications or AI`() {
        val f = NativeFixture(); val row = f.row()
        every { f.oauth.isConfigured(any()) } returns true
        every { f.calendar.syncEvent(any()) } answers {
            val event = firstArg<de.schoolagent.domain.model.SchoolEvent>(); event.googleEventId = "google-test"; event.googleSyncStatus = GoogleSyncStatus.ERROR; events.save(event); Unit
        }
        f.sync(f.feed(row, complete = true))
        val saved = f.stored(row.kind)
        assertThat(saved.projectionPending).isTrue(); assertThat(saved.notificationPending).isFalse()
        every { f.calendar.syncEvent(any()) } answers {
            val event = firstArg<de.schoolagent.domain.model.SchoolEvent>(); event.googleSyncStatus = GoogleSyncStatus.SYNCED; events.save(event); Unit
        }
        f.service.retryPendingEffects()
        assertThat(nativeRecords.findById(saved.recordKey).get().projectionPending).isFalse()
        every { f.calendar.removeProjection(any()) } answers {
            val event = firstArg<de.schoolagent.domain.model.SchoolEvent>(); event.googleSyncStatus = GoogleSyncStatus.ERROR; events.save(event); Unit
        }
        f.sync(SchoolRecordFeed(row.kind, SchoolFeedStatus.AVAILABLE, complete = true))
        assertThat(nativeRecords.findById(saved.recordKey).get().active).isFalse()
        assertThat(nativeRecords.findById(saved.recordKey).get().projectionPending).isTrue()
        every { f.calendar.removeProjection(any()) } answers {
            val event = firstArg<de.schoolagent.domain.model.SchoolEvent>(); event.googleEventId = null; event.googleSyncStatus = GoogleSyncStatus.NOT_APPLICABLE; events.save(event); Unit
        }
        f.service.retryPendingEffects()
        assertThat(nativeRecords.findById(saved.recordKey).get().projectionPending).isFalse()
        verify(exactly = 1) { f.interpreter.interpret(any()) }
        verify(exactly = 1) { f.notify.notifyDecision(any()) }
        verify(exactly = 1) { f.notify.notifySchoolChange(any(), "Cancelled", any()) }
    }

    @Test fun `authoritative exam survives AI outage and later wording repair preserves Done and notifications`() {
        val f = NativeFixture(); val row = f.row()
        every { f.interpreter.interpret(any()) } returns SchoolRecordInterpreter.Copy("School exam: Mathematics", "Original school wording", null, null, ai = false)
        f.sync(f.feed(row))
        val saved = f.stored(row.kind)
        assertThat(saved.wordingPending).isTrue()
        assertThat(events.findByCaseId(saved.caseId).single().startTime).isEqualTo(row.date!!.atStartOfDay(java.time.ZoneId.of("Europe/Berlin")).toInstant())
        val c = caseRepo.findById(saved.caseId).get(); caseService.transition(c, CaseStatus.COMPLETED)
        every { f.interpreter.interpret(any()) } returns SchoolRecordInterpreter.Copy("Mathematics exam", "A written assessment", null, null)
        f.sync(f.feed(row))
        assertThat(nativeRecords.findById(saved.recordKey).get().wordingPending).isFalse()
        assertThat(caseRepo.findById(saved.caseId).get().status).isEqualTo(CaseStatus.COMPLETED)
        assertThat(caseRepo.findById(saved.caseId).get().title).isEqualTo("Mathematics exam")
        verify(exactly = 1) { f.notify.notifyDecision(any()) }
        f.sync(f.feed(row))
        verify(exactly = 2) { f.interpreter.interpret(any()) }
    }

    @Test fun `old wording is refreshed once without changing completed exams source facts or notifications`() {
        val f = NativeFixture(); val row = f.row().copy(title = "Deutsch (XYZ)")
        every { f.interpreter.interpret(any()) } returns SchoolRecordInterpreter.Copy("German exam (Invented Track)", "Invented wording", null, null)
        f.sync(f.feed(row))
        val saved = f.stored(row.kind); val payload = saved.payload
        val event = events.findByCaseId(saved.caseId).single()
        saved.wordingVersion = 0; nativeRecords.save(saved)
        caseService.transition(caseRepo.findById(saved.caseId).get(), CaseStatus.COMPLETED)
        every { f.interpreter.interpret(any()) } returns SchoolRecordInterpreter.Copy("German exam", "A German assessment", null, null)
        assertThat(f.sync(f.feed(row)).changed).isZero()
        val updated = nativeRecords.findById(saved.recordKey).get()
        assertThat(updated.wordingVersion).isEqualTo(SchoolRecordInterpreter.WORDING_VERSION)
        assertThat(updated.wordingPending).isFalse()
        assertThat(updated.payload).isEqualTo(payload)
        assertThat(updated.caseId).isEqualTo(saved.caseId)
        assertThat(caseRepo.findById(saved.caseId).get().title).isEqualTo("German exam")
        assertThat(caseRepo.findById(saved.caseId).get().status).isEqualTo(CaseStatus.COMPLETED)
        val repairedEvent = events.findByCaseId(saved.caseId).single()
        assertThat(repairedEvent.id).isEqualTo(event.id)
        assertThat(repairedEvent.startTime).isEqualTo(event.startTime)
        assertThat(repairedEvent.title).isEqualTo("German exam")
        assertThat(json.readTree(changes.findByCaseIdOrderByCreatedAtAsc(saved.caseId).last().afterJson).path("wordingVersion").asInt())
            .isEqualTo(SchoolRecordInterpreter.WORDING_VERSION)
        f.sync(f.feed(row))
        verify(exactly = 2) { f.interpreter.interpret(any()) }
        verify(exactly = 1) { f.notify.notifyDecision(any()) }
        verify(exactly = 0) { f.notify.notifySchoolChange(any(), any(), any()) }
    }

    @Test fun `wording revision removes old generated copy even during AI outage and retries enrichment only`() {
        val f = NativeFixture(); val row = f.row()
        f.sync(f.feed(row))
        val saved = f.stored(row.kind); saved.wordingVersion = 0; nativeRecords.save(saved)
        caseService.transition(caseRepo.findById(saved.caseId).get(), CaseStatus.COMPLETED)
        every { f.interpreter.interpret(any()) } returns SchoolRecordInterpreter.Copy("School exam: Mathematics", "Original source", null, null, ai = false)
        f.sync(f.feed(row))
        assertThat(caseRepo.findById(saved.caseId).get().title).isEqualTo("School exam: Mathematics")
        assertThat(f.stored(row.kind).wordingPending).isTrue()
        assertThat(f.stored(row.kind).wordingVersion).isEqualTo(SchoolRecordInterpreter.WORDING_VERSION)
        every { f.interpreter.interpret(any()) } returns SchoolRecordInterpreter.Copy("Mathematics exam", "A scheduled assessment", null, null)
        f.sync(f.feed(row)); f.sync(f.feed(row))
        assertThat(caseRepo.findById(saved.caseId).get().status).isEqualTo(CaseStatus.COMPLETED)
        assertThat(f.stored(row.kind).wordingPending).isFalse()
        verify(exactly = 3) { f.interpreter.interpret(any()) }
        verify(exactly = 1) { f.notify.notifyDecision(any()) }
    }

    @Test fun `wording revision during outage cannot erase an already extracted payment deadline`() {
        val f = NativeFixture(); val row = f.row(SchoolRecordKind.PAYMENT)
        val dueDate = f.today.plusDays(9)
        every { f.interpreter.interpret(any()) } returns SchoolRecordInterpreter.Copy("Copying fee", "Pay the fee", "Pay €17.20", dueDate)
        f.sync(f.feed(row))
        val saved = f.stored(row.kind); saved.wordingVersion = 0; nativeRecords.save(saved)
        val action = actionRepo.findByCaseId(saved.caseId).single()
        action.status = ActionStatus.COMPLETED; actionRepo.save(action)
        caseService.transition(caseRepo.findById(saved.caseId).get(), CaseStatus.COMPLETED)
        every { f.interpreter.interpret(any()) } returns SchoolRecordInterpreter.Copy("School payment", "Original source", "Pay the request", null, ai = false)
        f.sync(f.feed(row))
        val updated = actionRepo.findById(action.id).get()
        assertThat(updated.dueAt).isEqualTo(dueDate.atStartOfDay(java.time.ZoneId.of("Europe/Berlin")).toInstant())
        assertThat(updated.status).isEqualTo(ActionStatus.COMPLETED)
        verify(exactly = 1) { f.notify.notifyDecision(any()) }
    }

    @Test fun `payment wording repair retains completed actions while recording an explicitly recovered deadline`() {
        val f = NativeFixture(); val row = f.row(SchoolRecordKind.PAYMENT).copy(text = "Zahlbar bis 20.10.2026.")
        every { f.interpreter.interpret(any()) } returns SchoolRecordInterpreter.Copy("School payment", row.text, "Pay the request", null, ai = false)
        f.sync(f.feed(row))
        val saved = f.stored(row.kind)
        val c = caseRepo.findById(saved.caseId).get(); caseService.transition(c, CaseStatus.COMPLETED)
        val action = actionRepo.findByCaseId(saved.caseId).single(); action.status = ActionStatus.COMPLETED; actionRepo.save(action)
        every { f.interpreter.interpret(any()) } returns SchoolRecordInterpreter.Copy("Copying fee", "Pay the copying fee", "Pay €17.20", f.today.plusDays(9))
        f.sync(f.feed(row))
        val updated = actionRepo.findById(action.id).get()
        assertThat(updated.status).isEqualTo(ActionStatus.COMPLETED)
        assertThat(updated.dueAt).isEqualTo(f.today.plusDays(9).atStartOfDay(java.time.ZoneId.of("Europe/Berlin")).toInstant())
        assertThat(updated.portalProvider).isEqualTo("SCHULMANAGER")
        assertThat(events.findByCaseId(saved.caseId)).isEmpty()
        verify(exactly = 1) { f.notify.notifyDecision(any()) }
    }

    @Test fun `a crash consuming quiet import suppression cannot announce the backlog on retry`() {
        val f = NativeFixture(); val row = f.row()
        every { f.notify.notifyDecision(any()) } answers {
            val c = firstArg<de.schoolagent.domain.model.SchoolCase>()
            c.suppressInitialNotification = false; caseRepo.save(c)
            throw IllegalStateException("Crash before outbox acknowledgement")
        }
        f.sync(f.feed(row))
        val saved = f.stored(row.kind)
        assertThat(saved.notificationPending).isTrue()
        assertThat(caseRepo.findById(saved.caseId).get().suppressInitialNotification).isTrue()
        every { f.notify.notifyDecision(any()) } answers {
            val c = firstArg<de.schoolagent.domain.model.SchoolCase>()
            f.suppression += c.suppressInitialNotification
            c.suppressInitialNotification = false; caseRepo.save(c); Unit
        }
        f.service.retryPendingEffects()
        assertThat(f.suppression).containsExactly(true)
        assertThat(nativeRecords.findById(saved.recordKey).get().notificationPending).isFalse()
    }

    @Test fun `inbox and detail expose the same portal navigation without rewriting stored form URLs`() {
        val kid = kidRepo.findAll().first()
        settings.save(de.schoolagent.domain.model.Setting(key = "elternportal.${kid.id}.url", valueJson = "https://family-school.eltern-portal.org"))
        val c = caseService.createCase(kid.id, "Data sharing in the parent portal")
        c.status = CaseStatus.ACTION_REQUIRED; caseRepo.save(c)
        val action = actionRepo.save(ParentAction(caseId = c.id, title = "Update Datenfreigabe in Elternportal under Meldungen",
            externalUrl = "https://school.example/", dedupKey = "portal-link-${c.id}"))
        val wrongForm = actionRepo.save(ParentAction(caseId = c.id, title = "Return attached application",
            externalUrl = "https://school.example", dedupKey = "paper-link-${c.id}"))
        val apiActions = json.readTree(get("/api/actions", morgan.first, morgan.second).body)
        val fromInbox = apiActions.first { it.path("id").asText() == action.id.toString() }
        val fromDetail = detail(c.id).path("actions").first { it.path("id").asText() == action.id.toString() }
        assertThat(fromInbox.path("externalLink")).isEqualTo(fromDetail.path("externalLink"))
        assertThat(fromInbox.path("externalLink").path("kind").asText()).isEqualTo("PORTAL")
        assertThat(fromInbox.path("externalLink").path("url").asText()).isEqualTo("https://family-school.eltern-portal.org/meldungen/datenfreigabe")
        assertThat(apiActions.first { it.path("id").asText() == wrongForm.id.toString() }.path("externalLink").isNull).isTrue()
        assertThat(actionRepo.findById(action.id).orElseThrow().externalUrl).isEqualTo("https://school.example/")
    }

    @Test fun `expiry is visible in list and detail without completing work or calling AI on reads`() {
        val c = caseService.createCase(null, "Old undated request")
        c.status = CaseStatus.ACTION_REQUIRED; caseRepo.save(c)
        val original = Instant.now().minusSeconds(90L * 86400)
        sourceRepo.save(Source(caseId = c.id, sourceType = SourceType.EMAIL, externalId = "expiry-${c.id}",
            dedupKey = "expiry-${c.id}", contentHash = "expiry", originalSentAt = original, receivedAt = Instant.now()))
        val action = actionRepo.save(ParentAction(caseId = c.id, title = "Return form", dedupKey = "expiry-action-${c.id}"))
        relevance.refresh(c.id)
        val before = usage.count()
        val first = detail(c.id).path("relevance")
        assertThat(first.path("archived").asBoolean()).isTrue()
        assertThat(first.path("assessment").asText()).isEqualTo("UNAVAILABLE")
        val list = json.readTree(get("/api/cases", morgan.first, morgan.second).body)
        assertThat(list.first { it.path("id").asText() == c.id.toString() }.path("relevance")).isEqualTo(first)
        assertThat(detail(c.id).path("relevance")).isEqualTo(first)
        assertThat(usage.count()).isEqualTo(before)
        assertThat(caseRepo.findById(c.id).get().status).isEqualTo(CaseStatus.ACTION_REQUIRED)
        assertThat(actionRepo.findById(action.id).get().status).isEqualTo(de.schoolagent.domain.enums.ActionStatus.OPEN)
        action.dueAt = Instant.now().plusSeconds(86400); actionRepo.save(action)
        assertThat(detail(c.id).path("relevance").isNull).isTrue()
    }

    @Test
    fun `personal reading does not complete the family matter and a new source is unread`() {
        val c = caseService.createCase(null, "Reading isolation")
        c.status = CaseStatus.INFORMATION_ONLY; c.informationOnly = true; caseRepo.save(c)
        val revision = detail(c.id).path("revision").asText()
        assertThat(mutate(c.id, "read", "{\"read\":true,\"revision\":\"$revision\"}").statusCode.value()).isEqualTo(200)
        assertThat(detail(c.id).path("read").asBoolean()).isTrue()
        assertThat(detail(c.id, casey.first).path("read").asBoolean()).isFalse()
        assertThat(caseRepo.findById(c.id).get().status).isEqualTo(CaseStatus.INFORMATION_ONLY)
        assertThat(mutate(c.id, "complete").statusCode.value()).isEqualTo(409)
        sourceRepo.save(Source(caseId = c.id, sourceType = SourceType.EMAIL, externalId = "reading-${c.id}", dedupKey = "reading-${c.id}", contentHash = "new-information"))
        assertThat(detail(c.id).path("read").asBoolean()).isFalse()
        assertThat(mutate(c.id, "read", "{\"read\":true,\"revision\":\"$revision\"}").statusCode.value()).isEqualTo(409)
    }

    @Test
    fun `one Done closes all obligations and Undo restores their previous states`() {
        val c = caseService.createCase(null, "Whole family completion")
        c.status = CaseStatus.ACTION_REQUIRED; caseRepo.save(c)
        val a = actionRepo.save(ParentAction(caseId = c.id, title = "Sign form", dedupKey = "done-a-${c.id}"))
        val b = actionRepo.save(ParentAction(caseId = c.id, title = "Pay", status = de.schoolagent.domain.enums.ActionStatus.OVERDUE, dedupKey = "done-b-${c.id}"))
        val r = requirementRepo.save(de.schoolagent.domain.model.ChildRequirement(caseId = c.id, description = "Bring boots", dedupKey = "done-r-${c.id}"))
        assertThat(mutate(c.id, "complete").statusCode.value()).isEqualTo(200)
        assertThat(caseRepo.findById(c.id).get().status).isEqualTo(CaseStatus.COMPLETED)
        assertThat(actionRepo.findByCaseId(c.id).map { it.status }).containsOnly(de.schoolagent.domain.enums.ActionStatus.COMPLETED)
        assertThat(requirementRepo.findById(r.id).get().status).isEqualTo(de.schoolagent.domain.enums.RequirementStatus.DONE)
        assertThat(detail(c.id, casey.first).path("canUndo").asBoolean()).isTrue()
        assertThat(mutate(c.id, "undo-complete", username = casey.first).statusCode.value()).isEqualTo(200)
        assertThat(caseRepo.findById(c.id).get().status).isEqualTo(CaseStatus.ACTION_REQUIRED)
        assertThat(actionRepo.findById(a.id).get().status).isEqualTo(de.schoolagent.domain.enums.ActionStatus.OPEN)
        assertThat(actionRepo.findById(b.id).get().status).isEqualTo(de.schoolagent.domain.enums.ActionStatus.OVERDUE)
        assertThat(requirementRepo.findById(r.id).get().status).isEqualTo(de.schoolagent.domain.enums.RequirementStatus.OPEN)
    }

    @Test
    fun `Undo refuses to restore an obsolete snapshot after a new source arrives`() {
        val c = caseService.createCase(null, "Safe Undo")
        c.status = CaseStatus.ACTION_REQUIRED; caseRepo.save(c)
        actionRepo.save(ParentAction(caseId = c.id, title = "Return form", dedupKey = "undo-${c.id}"))
        mutate(c.id, "complete")
        sourceRepo.save(Source(caseId = c.id, sourceType = SourceType.EMAIL, externalId = "undo-${c.id}", dedupKey = "undo-${c.id}", contentHash = "changed"))
        assertThat(mutate(c.id, "undo-complete").statusCode.value()).isEqualTo(409)
        assertThat(actionRepo.findByCaseId(c.id).single().status).isEqualTo(de.schoolagent.domain.enums.ActionStatus.COMPLETED)
    }

    @Test
    fun `Info correction is shared and does not delete extracted obligations`() {
        val c = caseService.createCase(null, "Classifier correction")
        c.status = CaseStatus.ACTION_REQUIRED; caseRepo.save(c)
        actionRepo.save(ParentAction(caseId = c.id, title = "Optional reading", dedupKey = "info-${c.id}"))
        assertThat(mutate(c.id, "classification", "{\"informationOnly\":true}").statusCode.value()).isEqualTo(200)
        assertThat(detail(c.id, casey.first).path("informationOnly").asBoolean()).isTrue()
        assertThat(caseRepo.findById(c.id).get().infoOverride).isTrue()
        assertThat(actionRepo.findByCaseId(c.id)).hasSize(1)
        assertThat(mutate(c.id, "classification", "{\"informationOnly\":false}").statusCode.value()).isEqualTo(200)
        assertThat(caseRepo.findById(c.id).get().status).isEqualTo(CaseStatus.ACTION_REQUIRED)
    }

    @Test
    fun `unassigned child cannot be read searched or changed`() {
        val kid = kidRepo.save(de.schoolagent.domain.model.Child(name = "Private child"))
        val c = caseService.createCase(kid.id, "Boundary-${kid.id}")
        c.status = CaseStatus.ACTION_REQUIRED; caseRepo.save(c)
        val a = actionRepo.save(ParentAction(caseId = c.id, title = "Private obligation", dedupKey = "scope-${c.id}"))
        assertThat(get("/api/cases/${c.id}", casey.first, casey.second).statusCode.value()).isEqualTo(403)
        assertThat(mutate(c.id, "complete", username = casey.first).statusCode.value()).isEqualTo(403)
        assertThat(get("/api/cases?kid=${kid.id}", casey.first, casey.second).statusCode.value()).isEqualTo(403)
        assertThat(get("/api/events?kid=${kid.id}", casey.first, casey.second).statusCode.value()).isEqualTo(403)
        assertThat(get("/api/google/status?kid=${kid.id}", casey.first, casey.second).statusCode.value()).isEqualTo(403)
        assertThat(get("/api/search?q=Boundary", casey.first, casey.second).body).doesNotContain(c.id.toString())
        assertThat(put("/api/actions/${a.id}/complete", casey.first, casey.second).statusCode.value()).isEqualTo(403)
    }

    @Value("\${local.server.port}")
    lateinit var port: String

    private val rest = RestTemplate()

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    private fun get(path: String, user: String? = null, pass: String? = null): ResponseEntity<String> {
        val headers = HttpHeaders()
        if (user != null) headers.setBasicAuth(user, pass!!)
        val entity = HttpEntity<Void>(headers)
        return try {
            rest.exchange(url(path), HttpMethod.GET, entity, String::class.java)
        } catch (e: HttpClientErrorException) {
            ResponseEntity<String>(e.responseBodyAsString, e.statusCode)
        }
    }

    private fun put(path: String, user: String? = null, pass: String? = null): ResponseEntity<String> {
        val headers = HttpHeaders()
        if (user != null) headers.setBasicAuth(user, pass!!)
        return try {
            rest.exchange(url(path), HttpMethod.PUT, HttpEntity<Void>(headers), String::class.java)
        } catch (e: HttpClientErrorException) {
            ResponseEntity<String>(e.responseBodyAsString, e.statusCode)
        }
    }

    private val morgan = "morgan" to "test-only-password"
    private val casey = "casey" to "test-only-password"

    @Test
    fun `anonymous requests are rejected with 401`() {
        assertThat(get("/api/cases").statusCode.value()).isEqualTo(401)
    }

    @Test
    fun `XHR 401 omits WWW-Authenticate so browsers show our login page, not the native popup`() {
        val headers = HttpHeaders().apply {
            set("X-Requested-With", "XMLHttpRequest")
        }
        val response = try {
            rest.exchange(url("/api/cases"), HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        } catch (e: HttpClientErrorException) {
            ResponseEntity<String>(null, e.responseHeaders, e.statusCode)
        }
        assertThat(response.statusCode.value()).isEqualTo(401)
        assertThat(response.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull()
    }

    @Test
    fun `non-XHR 401 keeps the standard Basic challenge for API clients`() {
        val headers = HttpHeaders().apply {
            accept = listOf(org.springframework.http.MediaType.TEXT_PLAIN)
        }
        val response = try {
            rest.exchange(url("/api/cases"), HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        } catch (e: HttpClientErrorException) {
            ResponseEntity<String>(null, e.responseHeaders, e.statusCode)
        }
        assertThat(response.statusCode.value()).isEqualTo(401)
        assertThat(response.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Basic realm=\"school-agent\"")
    }

    @Test
    fun `case list and detail are served for authenticated parents`() {
        val now = Instant.now()
        val schoolCase = caseService.createCase(null, "Schullandheim Oktober", CaseCategory.TRIP)
        val source = sourceRepo.save(
            Source(
                sourceType = SourceType.PORTAL_LETTER,
                externalId = "api-test-letter",
                dedupKey = DedupKeys.source(SourceType.PORTAL_LETTER, "api-test-letter"),
                contentHash = "hash",
                title = "Elternbrief",
                createdAt = now,
                updatedAt = now,
            ),
        )
        caseService.attachSource(schoolCase, source)
        caseService.transition(schoolCase, CaseStatus.INGESTING)
        actionRepo.save(
            ParentAction(
                caseId = schoolCase.id,
                title = "Teilnahme bestätigen",
                dedupKey = DedupKeys.action(schoolCase.id, "Teilnahme bestätigen"),
                createdAt = now,
                updatedAt = now,
            ),
        )

        val list = get("/api/cases", morgan.first, morgan.second)
        assertThat(list.statusCode.value()).isEqualTo(200)
        assertThat(list.body).contains("Schullandheim Oktober")
        assertThat(list.body).contains("\"openActions\":1")

        val detail = get("/api/cases/${schoolCase.id}", casey.first, casey.second)
        assertThat(detail.statusCode.value()).isEqualTo(200)
        assertThat(detail.body).contains("Elternbrief")
        assertThat(detail.body).contains("Teilnahme bestätigen")
        assertThat(detail.body).contains("INGESTING")

        val missing = get("/api/cases/${UUID.randomUUID()}", morgan.first, morgan.second)
        assertThat(missing.statusCode.value()).isEqualTo(404)
    }

    @Test
    fun `status filter narrows the list`() {
        val active = caseService.createCase(null, "Aktiver Fall ${System.nanoTime()}", CaseCategory.INFO)
        caseService.transition(active, CaseStatus.INGESTING)
        caseService.createCase(null, "Bleibt neu ${System.nanoTime()}", CaseCategory.INFO)

        val filtered = get("/api/cases?status=INGESTING", morgan.first, morgan.second)
        assertThat(filtered.statusCode.value()).isEqualTo(200)
        assertThat(filtered.body).contains("Aktiver Fall")
        assertThat(filtered.body).doesNotContain("Bleibt neu")
    }

    @Test
    fun `unknown status filter is a 400, not a silent full list`() {
        assertThat(get("/api/cases?status=BOGUS", morgan.first, morgan.second).statusCode.value()).isEqualTo(400)
    }

    @Test
    fun `source reading returns extracted text and is not anonymous`() {
        val now = Instant.now()
        val schoolCase = caseService.createCase(null, "Sportschuhe Freitag", CaseCategory.MATERIAL)
        val body = "Jamie muss am Freitag Turnschuhe mitbringen."
        val ref = documents.put(body.toByteArray())
        val source = sourceRepo.save(
            Source(
                sourceType = SourceType.PORTAL_LETTER,
                externalId = "read-letter-${System.nanoTime()}",
                dedupKey = DedupKeys.source(SourceType.PORTAL_LETTER, "read-letter-${System.nanoTime()}"),
                contentHash = ref,
                title = "Elternbrief Sport",
                storageRef = ref,
                mimeType = "text/plain",
                createdAt = now,
                updatedAt = now,
            ),
        )
        caseService.attachSource(schoolCase, source)

        assertThat(get("/api/cases/${schoolCase.id}/sources/${source.id}").statusCode.value()).isEqualTo(401)

        val reading = get("/api/cases/${schoolCase.id}/sources/${source.id}?lang=en", morgan.first, morgan.second)
        assertThat(reading.statusCode.value()).isEqualTo(200)
        assertThat(reading.body).contains("Turnschuhe")
        assertThat(reading.body).contains("\"language\":\"en\"")
        assertThat(reading.body).contains("Elternbrief Sport")

        val missing = get("/api/cases/${schoolCase.id}/sources/${UUID.randomUUID()}", morgan.first, morgan.second)
        assertThat(missing.statusCode.value()).isEqualTo(404)
    }

    @Test
    fun `marking a case done hides it for every parent`() {
        val schoolCase = caseService.createCase(null, "Herbstkonzert ${System.nanoTime()}", CaseCategory.INFO)
        caseService.transition(schoolCase, CaseStatus.INGESTING)
        caseService.transition(schoolCase, CaseStatus.READY_FOR_REASONING)
        caseService.transition(schoolCase, CaseStatus.INFORMATION_ONLY)

        assertThat(put("/api/cases/${schoolCase.id}/complete").statusCode.value()).isEqualTo(401)

        val done = put("/api/cases/${schoolCase.id}/complete", morgan.first, morgan.second)
        assertThat(done.statusCode.value()).isEqualTo(200)
        assertThat(done.body).contains("COMPLETED")

        val asOtherParent = get("/api/cases/${schoolCase.id}", casey.first, casey.second)
        assertThat(asOtherParent.statusCode.value()).isEqualTo(200)
        assertThat(asOtherParent.body).contains("COMPLETED")
    }
    private fun searchFor(query: String, kid: UUID? = null): com.fasterxml.jackson.databind.JsonNode {
        val response = get("/api/search?q=" + java.net.URLEncoder.encode(query, Charsets.UTF_8) +
            (kid?.let { "&kid=$it" } ?: ""), morgan.first, morgan.second)
        assertThat(response.statusCode.value()).isEqualTo(200)
        return json.readTree(response.body)
    }

    @Test
    fun `search matches English word variants reordered terms and cross-field content once per case`() {
        val kid = kidRepo.save(de.schoolagent.domain.model.Child(name = "Search child"))
        val c = caseService.createCase(kid.id, "Copying charge for autumn")
        c.summary = "School fee payment information"; caseRepo.save(c)
        actionRepo.save(ParentAction(caseId = c.id, title = "Pay the copying fee", description = "Bring a sealed envelope", dedupKey = "search-${c.id}"))
        val before = usage.findAll().sumOf { it.calls }
        for (q in listOf("copy fees", "fees copying", "copy", "cop", "autumn envelope", "SEALED envelope")) {
            assertThat(searchFor(q, kid.id).map { it.path("caseId").asText() }).containsExactly(c.id.toString())
        }
        assertThat(searchFor("nonexistent zebra", kid.id)).isEmpty()
        assertThat(usage.findAll().sumOf { it.calls }).isEqualTo(before)
    }

    @Test
    fun `search includes child requirements and current saved English translations`() {
        val kid = kidRepo.save(de.schoolagent.domain.model.Child(name = "Translation search"))
        val c = caseService.createCase(kid.id, "Brief zur Klasse")
        requirementRepo.save(de.schoolagent.domain.model.ChildRequirement(caseId = c.id,
            description = "Bring waterproof boots", dedupKey = "search-kit-${c.id}"))
        val source = sourceRepo.save(Source(caseId = c.id, sourceType = SourceType.PORTAL_LETTER,
            externalId = "search-${c.id}", dedupKey = "search-source-${c.id}", contentHash = "current", title = "Elternbrief"))
        translations.save(de.schoolagent.domain.model.SourceTranslation(sourceId = source.id, language = "en",
            contentHash = "current", translatedText = "An astronomy workshop will take place at the observatory."))
        translations.save(de.schoolagent.domain.model.SourceTranslation(sourceId = source.id, language = "en",
            contentHash = "old", translatedText = "Obsolete dragon announcement"))
        assertThat(searchFor("waterproof boot", kid.id).single().path("match").asText()).contains("waterproof boots")
        assertThat(searchFor("astronomy", kid.id).single().path("match").asText()).contains("astronomy workshop")
        assertThat(searchFor("Elternbrief", kid.id)).hasSize(1)
        assertThat(searchFor("obsolete dragon", kid.id)).isEmpty()
    }

    @Test
    fun `search respects selected child and edits without a stale cache`() {
        val one = kidRepo.save(de.schoolagent.domain.model.Child(name = "Search one"))
        val two = kidRepo.save(de.schoolagent.domain.model.Child(name = "Search two"))
        val first = caseService.createCase(one.id, "Orchestra concert")
        val second = caseService.createCase(two.id, "Orchestra tickets")
        assertThat(searchFor("orchestra", one.id).map { it.path("caseId").asText() }).containsExactly(first.id.toString())
        second.title = "Planetarium visit"; caseRepo.save(second)
        assertThat(searchFor("orchestra", two.id)).isEmpty()
        assertThat(searchFor("planetarium", two.id)).hasSize(1)
        assertThat(searchFor("' ; --", one.id)).isEmpty()
        assertThat(get("/api/search?q=x", morgan.first, morgan.second).statusCode.value()).isEqualTo(400)
    }


    @Test fun `manual actions and requirements accept quotes backslashes and line breaks`() {
        val c = caseService.createCase(title="Case\twith control characters", childId=UUID.fromString("00000000-0000-0000-0000-000000000003"))
        val text = "Bring \"signed\" form\nCheck C:\\notes"
        val headers = HttpHeaders().apply { setBasicAuth(morgan.first, morgan.second); contentType = org.springframework.http.MediaType.APPLICATION_JSON }
        for ((path, field) in listOf("actions" to "title", "requirements" to "description")) {
            val response = rest.exchange(url("/api/$path/case/${c.id}"), HttpMethod.POST,
                HttpEntity(json.writeValueAsString(mapOf(field to text)), headers), String::class.java)
            assertThat(response.statusCode.value()).isEqualTo(200)
            assertThat(json.readTree(response.body).path(field).asText()).isEqualTo(text)
            val change = changes.findByCaseIdOrderByCreatedAtAsc(c.id).last()
            assertThat(json.readTree(change.afterJson).path(field).asText()).isEqualTo(text)
        }
    }

    @Test fun `repeated feedback of the same type persists including multiline notes`() {
        val c = caseService.createCase(title="Example feedback", childId=UUID.fromString("00000000-0000-0000-0000-000000000003"))
        val headers = HttpHeaders().apply { setBasicAuth(morgan.first, morgan.second); contentType = org.springframework.http.MediaType.APPLICATION_JSON }
        repeat(2) {
            val response = rest.exchange(url("/api/cases/${c.id}/feedback"), HttpMethod.POST,
                HttpEntity(json.writeValueAsString(mapOf("type" to "WRONG_DATE", "note" to "Check \"Friday\"\nNot Thursday")), headers), String::class.java)
            assertThat(response.statusCode.value()).isEqualTo(200)
        }
        assertThat(auditEntries.findByCaseIdOrderByCreatedAtDesc(c.id).count { it.action == "FEEDBACK_WRONG_DATE" }).isEqualTo(2)
    }

}
