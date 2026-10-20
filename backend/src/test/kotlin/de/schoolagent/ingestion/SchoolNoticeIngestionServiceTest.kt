package de.schoolagent.ingestion

import de.schoolagent.connectors.school.*
import de.schoolagent.documents.DocumentStore
import de.schoolagent.documents.FileSystemDocumentStore
import de.schoolagent.domain.enums.*
import de.schoolagent.domain.model.*
import de.schoolagent.domain.repo.*
import de.schoolagent.domain.service.CaseService
import de.schoolagent.domain.service.FamilyInboxService
import io.mockk.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.*
import java.util.UUID

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class SchoolNoticeIngestionServiceTest {
    companion object {
        @Container @ServiceConnection @JvmStatic val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17")
    }
    @Autowired lateinit var items: SchoolItemRepository
    @Autowired lateinit var revisions: SchoolItemRevisionRepository
    @Autowired lateinit var sources: SourceRepository
    @Autowired lateinit var evidence: EvidenceRepository
    @Autowired lateinit var cases: SchoolCaseRepository
    @Autowired lateinit var kids: ChildRepository
    @Autowired lateinit var changes: CaseChangeRepository
    @Autowired lateinit var lifecycle: CaseService
    @Autowired lateinit var inbox: FamilyInboxService
    @Autowired lateinit var responses: SchoolResponseReconciliation
    @Autowired lateinit var actions: ParentActionRepository
    @Autowired lateinit var requirements: ChildRequirementRepository
    @Autowired lateinit var events: SchoolEventRepository
    @Autowired lateinit var audit: AuditEntryRepository
    @Autowired lateinit var transactionManager: PlatformTransactionManager
    @Autowired lateinit var nativeRows: SchoolRecordRepository
    private val config = mockk<SchoolConnectionService>()
    private val bytes = mutableMapOf<String, ByteArray>()
    private val documents = object : DocumentStore {
        override fun put(content: ByteArray) = FileSystemDocumentStore.sha256(content).also { bytes[it] = content }
        override fun get(ref: String) = bytes[ref]
        override fun exists(ref: String) = ref in bytes
    }
    private var now = Instant.parse("2026-10-01T12:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant() = now
    }
    private lateinit var service: SchoolNoticeIngestionService
    private lateinit var child: Child
    private val credentials = SchoolCredentials(SchoolProvider.SCHULMANAGER, "https://school.example.test",
        "synthetic-parent", "synthetic-test-password", "7", "1")
    private var content = listOf(SchoolDocument("letter", "School trip", "text/plain", "Bring a workbook.".toByteArray()))
    private var failure = false
    private var response: SchoolResponse? = null
    private val notice = SchoolNotice("42", "School trip", now, "https://school.example.test/letters/42")
    private val session = object : SchoolSession {
        override val identity = SchoolIdentity("1", null, listOf(SchoolStudent("7", "Synthetic Child")))
        override val canRefreshDocuments = true
        override fun notices(studentId: String?) = listOf(notice)
        override fun documents(notice: SchoolNotice, studentId: String?): List<SchoolDocument> {
            if (failure) throw SchoolConnectionException("Synthetic failed attachment")
            return content
        }
        override fun details(notice: SchoolNotice, studentId: String?) = SchoolNoticeDetails(documents(notice, studentId), response)
    }

    @BeforeEach fun setup() {
        child = kids.save(Child(name = "Synthetic Child"))
        every { config.credentials(any()) } returns credentials
        service = SchoolNoticeIngestionService(items, revisions, sources, cases, kids, changes, lifecycle, config,
            documents, TransactionTemplate(transactionManager), clock, responses)
    }
    private fun sync(childId: UUID = child.id, entry: SchoolNotice = notice): SchoolNoticeIngestionService.Result {
        now = now.plusSeconds(1801)
        return service.sync(childId, credentials, session, listOf(entry), true)
    }
    private fun item() = items.findByChildId(child.id).single()

    private fun nativeService(): SchoolRecordIngestionService {
        every { config.hasRecordBaseline(any(), any(), any()) } returns false
        every { config.recordBaseline(any(), any(), any()) } just Runs
        val interpreter = mockk<SchoolRecordInterpreter>()
        every { interpreter.interpret(any()) } answers {
            val r = firstArg<SchoolRecord>()
            SchoolRecordInterpreter.Copy(r.title, r.text, if (r.kind == SchoolRecordKind.PAYMENT) "Check payment" else null, r.dueDate)
        }
        return SchoolRecordIngestionService(nativeRows, sources, cases, events, actions, changes, lifecycle, kids, config,
            documents, interpreter, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            TransactionTemplate(transactionManager), clock, requirements = requirements)
    }
    private fun nativeSync(service: SchoolRecordIngestionService, record: SchoolRecord) {
        val result = service.sync(child.id, credentials, SchoolStudentScope(child.name, child.className, "7"),
            listOf(SchoolRecordFeed(record.kind, SchoolFeedStatus.AVAILABLE, listOf(record))),
            LocalDate.of(2026, 10, 1), LocalDate.of(2027, 10, 1), true)
        assertThat(result.failed).isZero()
    }

    @Test fun `typed payment updates preserve local Done until a new balance or reversed payment requires action`() {
        val service = nativeService()
        val invoice = SchoolRecord("invoice-1", SchoolRecordKind.PAYMENT, "Trip fee", "Trip fee: 20 EUR",
            url = "https://school.example.test/payments",
            payment = SchoolPayment("EUR", 2000, 0, SchoolPaymentStatus.OUTSTANDING))
        nativeSync(service, invoice)
        val action = actions.findAll().single { it.title == "Check payment" && cases.findById(it.caseId).orElseThrow().childId == child.id }
        val c = cases.findById(action.caseId).orElseThrow()
        inbox.complete(c.id, org.springframework.security.core.userdetails.User.withUsername("morgan").password("unused").roles("ADMIN").build())
        nativeSync(service, invoice.copy(payment = SchoolPayment("EUR", 2000, 1000, SchoolPaymentStatus.PARTIAL)))
        assertThat(actions.findById(action.id).orElseThrow().status).isEqualTo(ActionStatus.COMPLETED)
        assertThat(cases.findById(c.id).orElseThrow().status).isEqualTo(CaseStatus.COMPLETED)
        nativeSync(service, invoice.copy(text = "Trip fee increased to 30 EUR",
            payment = SchoolPayment("EUR", 3000, 1000, SchoolPaymentStatus.PARTIAL)))
        assertThat(actions.findById(action.id).orElseThrow().status).isEqualTo(ActionStatus.OPEN)
        nativeSync(service, invoice.copy(payment = SchoolPayment("EUR", 3000, 3000, SchoolPaymentStatus.SETTLED)))
        assertThat(actions.findById(action.id).orElseThrow().status).isEqualTo(ActionStatus.COMPLETED)
        nativeSync(service, invoice.copy(payment = SchoolPayment("EUR", 3000, 2000, SchoolPaymentStatus.PARTIAL)))
        assertThat(actions.findById(action.id).orElseThrow().status).isEqualTo(ActionStatus.OPEN)
        assertThat(sources.findAllByCaseId(c.id)).hasSize(5)
        assertThat(sources.findByCaseId(c.id)).hasSize(1)
    }

    @Test fun `undated homework creates a requirement not an event and changed completed instructions require review`() {
        val service = nativeService()
        val homework = SchoolRecord("assignment-1", SchoolRecordKind.HOMEWORK, "Mathematics homework",
            "Complete exercise 2", date = LocalDate.of(2026, 10, 1), url = "https://school.example.test/homework")
        nativeSync(service, homework)
        val requirement = requirements.findAll().single { it.description == homework.text }
        assertThat(requirement.relevantOn).isNull()
        assertThat(events.findByCaseId(requirement.caseId)).isEmpty()
        inbox.complete(requirement.caseId, org.springframework.security.core.userdetails.User.withUsername("morgan").password("unused").roles("ADMIN").build())
        nativeSync(service, homework.copy(dueDate = LocalDate.of(2026, 10, 8)))
        assertThat(cases.findById(requirement.caseId).orElseThrow().status).isEqualTo(CaseStatus.COMPLETED)
        nativeSync(service, homework.copy(text = "Complete exercises 2 and 3"))
        assertThat(cases.findById(requirement.caseId).orElseThrow().schoolUpdatePending).isTrue()
        assertThat(requirements.findById(requirement.id).orElseThrow().status).isEqualTo(RequirementStatus.DONE)
    }
    private fun case() = sources.findById(item().sourceId).orElseThrow().caseId!!.let { cases.findById(it).orElseThrow() }
    private fun complete(c: SchoolCase) {
        lifecycle.transition(c, CaseStatus.READY_FOR_REASONING)
        lifecycle.transition(c, CaseStatus.ACTION_REQUIRED)
        lifecycle.transition(c, CaseStatus.COMPLETED)
    }
    private fun corrections(answer: String?): de.schoolagent.reasoning.SchoolCorrectionService {
        val router = mockk<de.schoolagent.ai.ModelRouter>()
        if (answer == null) every { router.complete(any(), any(), any(), any()) } throws IllegalStateException("Synthetic model outage")
        else every { router.complete(any(), any(), any(), any()) } returns answer
        val readings = mockk<de.schoolagent.api.SourceReadingService>()
        every { readings.original(any()) } answers { firstArg<Source>().storageRef?.let(documents::get)?.decodeToString() }
        return de.schoolagent.reasoning.SchoolCorrectionService(cases, sources, actions, requirements, events, changes,
            audit, documents, router, lifecycle, TransactionTemplate(transactionManager), clock, mockk(relaxed = true), readings)
    }

    @Test fun `same native identity updates in place including A to B to A and preserves originals`() {
        assertThat(sync().changed).isEqualTo(1)
        val c = case()
        val first = item().sourceId
        evidence.save(Evidence(caseId = c.id, sourceId = first, kind = EvidenceKind.FACT,
            subject = "Original workbook instruction", dedupKey = UUID.randomUUID().toString()))
        complete(c)
        content = listOf(content.single().copy(bytes = "Bring a different workbook.".toByteArray()))
        assertThat(sync().changed).isEqualTo(1)
        assertThat(c.schoolUpdatePending).isTrue()
        assertThat(c.schoolPreviousStatus).isEqualTo("COMPLETED")
        assertThat(evidence.findByCaseId(c.id)).isEmpty()
        assertThat(evidence.findAllByCaseId(c.id)).hasSize(1)
        content = listOf(content.single().copy(bytes = "Bring a workbook.".toByteArray()))
        assertThat(sync().changed).isEqualTo(1)
        assertThat(item().version).isEqualTo(3)
        assertThat(item().sourceId).isNotEqualTo(first)
        assertThat(revisions.findByItemKeyOrderByVersionDesc(item().itemKey)).hasSize(3)
        assertThat(sources.findByCaseId(c.id)).hasSize(1)
        assertThat(sources.findAllByCaseId(c.id).filter { it.superseded }).hasSize(2)
        assertThat(cases.findAll().count { it.childId == child.id }).isEqualTo(1)
    }

    @Test fun `cosmetic bytes preserve Done and the existing family revision`() {
        content = listOf(content.single().copy(bytes = "Bring a workbook.\n".toByteArray()))
        sync()
        val c = case(); complete(c)
        val revision = inbox.revision(c.id)
        content = listOf(content.single().copy(bytes = "Bring a workbook.\r\n".toByteArray()))
        assertThat(sync().changed).isZero()
        assertThat(c.status).isEqualTo(CaseStatus.COMPLETED)
        assertThat(c.schoolUpdatePending).isFalse()
        assertThat(inbox.revision(c.id)).isEqualTo(revision)
        assertThat(item().version).isEqualTo(2)
        assertThat(revisions.findByItemKeyOrderByVersionDesc(item().itemKey).first().material).isFalse()
    }

    @Test fun `an incomplete replacement keeps all previously applied parts`() {
        content = content + SchoolDocument("attachment", "Instructions", "text/plain", "Original attached details.".toByteArray())
        sync()
        val before = item().manifestHash
        val ids = sources.findByCaseId(case().id).map { it.id }
        failure = true
        assertThat(sync(entry = notice.copy(changeMarker = "updated")).failed).isEqualTo(1)
        assertThat(item().manifestHash).isEqualTo(before)
        assertThat(sources.findByCaseId(case().id).map { it.id }).containsExactlyInAnyOrderElementsOf(ids)
        assertThat(item().failureCode).isEqualTo("CONTENT_CHECK_FAILED")
        assertThat(case().schoolUpdatePending).isTrue()
    }

    @Test fun `sibling identities do not share cases or current documents`() {
        val sibling = kids.save(Child(name = "Synthetic Sibling"))
        sync(); sync(sibling.id)
        val other = items.findByChildId(sibling.id).single()
        assertThat(item().itemKey).isNotEqualTo(other.itemKey)
        assertThat(sources.findById(item().sourceId).orElseThrow().caseId)
            .isNotEqualTo(sources.findById(other.sourceId).orElseThrow().caseId)
    }

    @Test fun `school response submission completes only its linked duty and receipt alone completes nothing`() {
        response = SchoolResponse(receipt = "ACKNOWLEDGED", response = "PENDING", questionIds = listOf("consent"))
        sync()
        val c = case()
        val form = actions.findByDedupKey("school-response:${item().itemKey}")!!
        val payment = actions.save(ParentAction(caseId = c.id, title = "Pay the trip fee", dedupKey = UUID.randomUUID().toString()))
        assertThat(form.status).isEqualTo(ActionStatus.OPEN)
        response = response!!.copy(response = "SUBMITTED")
        sync()
        assertThat(actions.findById(form.id).orElseThrow().status).isEqualTo(ActionStatus.COMPLETED)
        assertThat(actions.findById(payment.id).orElseThrow().status).isEqualTo(ActionStatus.OPEN)
        assertThat(c.status).isNotEqualTo(CaseStatus.COMPLETED)
    }

    @Test fun `a corrected event preserves its id duration and unrelated completed consent`() {
        sync()
        val c = case()
        val consent = actions.save(ParentAction(caseId = c.id, title = "Submit consent",
            status = ActionStatus.COMPLETED, dedupKey = UUID.randomUUID().toString()))
        val event = events.save(SchoolEvent(caseId = c.id, childId = child.id, title = "School trip", eventType = EventType.SCHOOL_EVENT,
            startTime = Instant.parse("2026-10-15T08:00:00Z"), endTime = Instant.parse("2026-10-15T09:00:00Z"),
            dedupKey = UUID.randomUUID().toString()))
        complete(c)
        val text = "The trip now starts on 20.10.2026 at 12:00."
        content = listOf(content.single().copy(bytes = text.toByteArray()))
        sync()
        lifecycle.transition(c, CaseStatus.READY_FOR_REASONING)
        val answer = """{"reviewed":true,"summary":"The trip starts later; submitted consent remains valid.","operations":[
            {"kind":"EVENT","operation":"UPDATE","id":"${event.id}","title":"School trip","date":"2026-10-20",
            "time":"12:00","required":true,"sourceId":"${item().sourceId}","quote":"$text"}]}"""
        assertThat(corrections(answer).process(c.id)).isTrue()
        assertThat(c.status).isEqualTo(CaseStatus.COMPLETED)
        assertThat(c.schoolUpdatePending).isFalse()
        assertThat(c.schoolNotificationPending).isTrue()
        assertThat(actions.findById(consent.id).orElseThrow().status).isEqualTo(ActionStatus.COMPLETED)
        val current = events.findById(event.id).orElseThrow()
        assertThat(current.startTime).isEqualTo(Instant.parse("2026-10-20T10:00:00Z"))
        assertThat(current.endTime).isEqualTo(Instant.parse("2026-10-20T11:00:00Z"))
        assertThat(current.googleSyncStatus).isEqualTo(GoogleSyncStatus.PENDING)
        assertThat(events.findByCaseId(c.id)).hasSize(1)
    }

    @Test fun `AI outage keeps a correction pending for review without changing existing duties`() {
        sync()
        val c = case()
        val action = actions.save(ParentAction(caseId = c.id, title = "Bring the workbook",
            dueAt = Instant.parse("2026-10-15T00:00:00Z"), dedupKey = UUID.randomUUID().toString()))
        complete(c)
        content = listOf(content.single().copy(bytes = "Bring the workbook on 21.10.2026.".toByteArray()))
        sync()
        lifecycle.transition(c, CaseStatus.READY_FOR_REASONING)
        assertThat(corrections(null).process(c.id)).isFalse()
        assertThat(c.status).isEqualTo(CaseStatus.NEEDS_REVIEW)
        assertThat(c.schoolUpdatePending).isTrue()
        assertThat(actions.findById(action.id).orElseThrow().dueAt).isEqualTo(Instant.parse("2026-10-15T00:00:00Z"))
    }

    @Test fun `a new required duty promotes a formerly quiet notice and flags a conflicting family classification`() {
        sync()
        val c = case()
        c.importanceScore = 1; c.informationOnly = true; c.infoOverride = true
        lifecycle.transition(c, CaseStatus.READY_FOR_REASONING)
        lifecycle.transition(c, CaseStatus.INFORMATION_ONLY)
        val text = "Return the required permission form by 20.10.2026."
        content = listOf(content.single().copy(bytes = text.toByteArray()))
        sync()
        lifecycle.transition(c, CaseStatus.READY_FOR_REASONING)
        val answer = """{"reviewed":true,"summary":"The school now requires a permission form.","operations":[
            {"kind":"ACTION","operation":"ADD","title":"Return the permission form","date":"2026-10-20",
            "required":true,"sourceId":"${item().sourceId}","quote":"$text"}]}"""
        assertThat(corrections(answer).process(c.id)).isTrue()
        assertThat(c.importanceScore).isGreaterThanOrEqualTo(4)
        assertThat(c.classificationReview).isTrue()
        assertThat(actions.findByCaseId(c.id)).hasSize(1)
    }
}
