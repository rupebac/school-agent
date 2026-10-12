package de.schoolagent.ingestion

import de.schoolagent.common.DedupKeys
import de.schoolagent.connectors.school.*
import de.schoolagent.documents.DocumentStore
import de.schoolagent.domain.enums.*
import de.schoolagent.domain.model.*
import de.schoolagent.domain.repo.*
import de.schoolagent.domain.service.CaseService
import de.schoolagent.health.HealthTracker
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.Optional
import java.util.UUID
import java.util.function.Consumer

class SchoolIngestionServiceTest {
    private val config = mockk<SchoolConnectionService>(relaxed = true)
    private val sources = mockk<SourceRepository>(relaxed = true)
    private val cases = mockk<SchoolCaseRepository>(relaxed = true)
    private val children = mockk<ChildRepository>()
    private val caseService = mockk<CaseService>(relaxed = true)
    private val health = mockk<HealthTracker>(relaxed = true)
    private val tx = mockk<TransactionTemplate>()
    private val stored = mutableListOf<Source>()
    private val created = mutableListOf<SchoolCase>()
    private var downloads = 0
    private var fail = false
    private var noticeId = "same-id"
    private var feeds = emptyList<SchoolRecordFeed>()
    private var recordsThrow = false
    private val lastSync = mutableMapOf<UUID, java.time.Instant>()
    private val connectors = SchoolProvider.entries.map { provider -> object : SchoolConnector {
        override val provider = provider
        override fun connect(credentials: SchoolCredentials) = object : SchoolSession {
            override val identity = SchoolIdentity("1", "School", emptyList())
            override fun notices(studentId: String?) = listOf(SchoolNotice(noticeId, "School letter", null, "https://school.example.test/letter"))
            override fun records(scope: SchoolStudentScope, from: java.time.LocalDate, to: java.time.LocalDate): List<SchoolRecordFeed> {
                if (recordsThrow) throw SchoolConnectionException("Module discovery failed")
                return feeds
            }
            override fun documents(notice: SchoolNotice, studentId: String?): List<SchoolDocument> {
                downloads++
                if (fail) throw SchoolConnectionException("Attachment unavailable")
                return listOf(SchoolDocument("letter", "School letter", "text/plain", "Bring workbook".toByteArray()),
                    SchoolDocument("pdf", "letter.pdf", "application/pdf", "%PDF-1.7".toByteArray()))
            }
        }
    } }
    private lateinit var service: SchoolIngestionService

    @BeforeEach
    fun setup() {
        every { sources.findByDedupKey(any()) } answers { stored.find { it.dedupKey == firstArg<String>() } }
        every { sources.save(any<Source>()) } answers { firstArg<Source>().also(stored::add) }
        every { cases.save(any<SchoolCase>()) } answers { firstArg() }
        every { cases.findById(any()) } answers { Optional.ofNullable(created.find { it.id == firstArg<UUID>() }) }
        every { children.findById(any()) } answers { Optional.of(Child(id = firstArg(), name = "Child")) }
        every { children.lockById(any()) } answers { Child(id = firstArg(), name = "Child") }
        every { caseService.createCase(any(), any(), any(), any(), any()) } answers { SchoolCase(childId = firstArg(), title = secondArg()).also(created::add) }
        every { caseService.attachSource(any(), any(), any()) } answers { secondArg<Source>().caseId = firstArg<SchoolCase>().id; CaseService.AttachResult.ATTACHED }
        every { tx.executeWithoutResult(any()) } answers { firstArg<Consumer<org.springframework.transaction.TransactionStatus>>().accept(mockk()); Unit }
        val documents = mockk<DocumentStore>()
        every { documents.put(any()) } answers { java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(firstArg())) }
        service = SchoolIngestionService(connectors, config, documents, caseService, sources, cases, children, mockk(), mockk(), health, tx, Clock.systemUTC())
    }

    private fun configure(child: UUID, provider: SchoolProvider) {
        every { config.credentials(child) } returns SchoolCredentials(provider, "https://school.example.test", "parent", "password", "7", "1")
        every { config.status(child) } answers { SchoolConnectionService.Status(provider, true, null, null, null, true, "7", "1", "Child", lastSync[child]) }
        every { config.recordSync(child) } answers { lastSync[child] = java.time.Instant.now(); Unit }
    }

    @Test
    fun `providers share ingestion while same remote ids remain isolated per child and provider`() {
        val jamie = UUID.randomUUID(); val taylor = UUID.randomUUID()
        configure(jamie, SchoolProvider.ELTERNPORTAL); configure(taylor, SchoolProvider.SCHULMANAGER)
        assertThat(service.syncOnce(jamie).ingested).isEqualTo(1)
        assertThat(service.syncOnce(taylor).ingested).isEqualTo(1)
        assertThat(service.syncOnce(jamie).ingested).isZero()
        assertThat(service.syncOnce(taylor).ingested).isZero()
        assertThat(downloads).isEqualTo(2)
        assertThat(created.map { it.childId }).containsExactly(jamie, taylor)
        assertThat(created).allMatch { it.suppressInitialNotification }
        assertThat(stored.map { it.sourceType }).containsExactly(SourceType.PORTAL_LETTER, SourceType.ATTACHMENT, SourceType.SCHULMANAGER_LETTER, SourceType.ATTACHMENT)
        assertThat(stored.map { it.dedupKey }).doesNotHaveDuplicates()
        verify(exactly = 2) { caseService.transition(any<SchoolCase>(), CaseStatus.INGESTING, Actor.SYSTEM, any()) }
    }

    @Test
    fun `failed attachments create no partial case and retry on the next poll`() {
        val child = UUID.randomUUID(); configure(child, SchoolProvider.SCHULMANAGER)
        fail = true
        assertThat(service.syncOnce(child).failed).isEqualTo(1)
        assertThat(created).isEmpty(); assertThat(stored).isEmpty()
        verify(exactly = 0) { config.recordSync(child) }
        fail = false
        assertThat(service.syncOnce(child).ingested).isEqualTo(1)
        assertThat(service.syncOnce(child).ingested).isZero()
    }

    @Test
    fun `legacy Elternportal imports are adopted without downloading or duplicating`() {
        val child = UUID.randomUUID(); configure(child, SchoolProvider.ELTERNPORTAL)
        val old = SchoolCase(childId = child, title = "Existing"); created.add(old)
        stored.add(Source(caseId = old.id, sourceType = SourceType.PORTAL_LETTER, externalId = "same-id", dedupKey = DedupKeys.source(SourceType.PORTAL_LETTER, "same-id"), contentHash = "old", fetchStatus = FetchStatus.FETCHED))
        assertThat(service.syncOnce(child).ingested).isZero()
        assertThat(downloads).isZero(); assertThat(created).hasSize(1)
    }

    @Test fun `failed optional sections save letter health and cannot keep new notifications in initial import`() {
        val child = UUID.randomUUID(); configure(child, SchoolProvider.SCHULMANAGER)
        feeds = listOf(SchoolRecordFeed.failed(SchoolRecordKind.PAYMENT))
        assertThat(service.syncOnce(child).failed).isEqualTo(1)
        assertThat(lastSync[child]).isNotNull()
        assertThat(created.single().suppressInitialNotification).isTrue()
        noticeId = "new-letter"
        recordsThrow = true
        assertThat(service.syncOnce(child).ingested).isEqualTo(1)
        assertThat(created.last().suppressInitialNotification).isFalse()
        verify(exactly = 2) { config.recordSync(child) }
        verify(exactly = 2) { health.success(SchoolProvider.SCHULMANAGER.healthType) }
        verify(exactly = 0) { health.failure(any(), any()) }
        verify { config.recordSections(child, match { it.size == 4 && it.all { feed -> feed.status == SchoolFeedStatus.FAILED } }) }
    }

    @Test fun `siblings and manual tests serialize the same portal account viewing context`() {
        val first = UUID.randomUUID(); val second = UUID.randomUUID()
        configure(first, SchoolProvider.ELTERNPORTAL); configure(second, SchoolProvider.ELTERNPORTAL)
        val active = java.util.concurrent.atomic.AtomicInteger()
        val maximum = java.util.concurrent.atomic.AtomicInteger()
        val portal = object : SchoolConnector {
            override val provider = SchoolProvider.ELTERNPORTAL
            override fun connect(credentials: SchoolCredentials): SchoolSession {
                maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                Thread.sleep(50)
                return object : SchoolSession {
                    override val identity = SchoolIdentity(null, null, emptyList())
                    override fun notices(studentId: String?) = emptyList<SchoolNotice>()
                    override fun documents(notice: SchoolNotice, studentId: String?) = emptyList<SchoolDocument>()
                    override fun close() { active.decrementAndGet() }
                }
            }
        }
        val serialized = SchoolIngestionService(listOf(portal), config, mockk(relaxed = true), caseService, sources, cases, children,
            mockk(relaxed = true), mockk(relaxed = true), health, tx, Clock.systemUTC())
        val start = java.util.concurrent.CountDownLatch(1)
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            val one = pool.submit { start.await(); serialized.syncOnce(first) }
            val two = pool.submit { start.await(); serialized.testConnection(second) }
            start.countDown()
            one.get(5, java.util.concurrent.TimeUnit.SECONDS); two.get(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        assertThat(maximum.get()).isEqualTo(1)
        assertThat(active.get()).isZero()
    }
}
