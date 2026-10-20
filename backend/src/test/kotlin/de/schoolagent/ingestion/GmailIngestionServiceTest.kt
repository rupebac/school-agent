package de.schoolagent.ingestion

import de.schoolagent.connectors.gmail.AttachmentData
import de.schoolagent.connectors.gmail.GmailApiException
import de.schoolagent.connectors.gmail.GmailClient
import de.schoolagent.connectors.gmail.MessageMetadata
import de.schoolagent.connectors.google.GoogleProperties
import de.schoolagent.documents.DocumentStore
import de.schoolagent.domain.enums.ConnectorType
import de.schoolagent.domain.model.Setting
import de.schoolagent.domain.model.Source
import de.schoolagent.domain.repo.ChildRepository
import de.schoolagent.domain.repo.SettingRepository
import de.schoolagent.domain.repo.SourceRepository
import de.schoolagent.domain.service.CaseService
import de.schoolagent.health.HealthTracker
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class GmailIngestionServiceTest {

    private val caseService = mockk<CaseService>(relaxed = true)
    private val sourceRepo = mockk<SourceRepository>(relaxed = true)
    private val childRepo = mockk<ChildRepository>(relaxed = true)
    private val settings = mockk<SettingRepository>(relaxed = true)
    private val health = mockk<HealthTracker>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC)

    private val knownDedupKeys = mutableSetOf<String>()
    private val savedSources = mutableListOf<Source>()
    private val savedSettings = mutableListOf<Setting>()

    private class FakeGmail : GmailClient {
        val messages = LinkedHashMap<String, MessageMetadata>()
        val raws = mutableMapOf<String, ByteArray>()
        val attachments = mutableMapOf<String, List<AttachmentData>>()
        var failWith: GmailApiException? = null

        override fun listMessageIds(maxResults: Int): List<String> {
            failWith?.let { throw it }
            return messages.keys.toList()
        }

        override fun listMessagePage(maxResults: Int, pageToken: String?): de.schoolagent.connectors.gmail.MessageIdPage {
            val all = listMessageIds(maxResults)
            val offset = pageToken?.toInt() ?: 0
            val next = (offset + maxResults).takeIf { it < all.size }?.toString()
            return de.schoolagent.connectors.gmail.MessageIdPage(all.drop(offset).take(maxResults), next)
        }

        override fun fetchMetadata(id: String): MessageMetadata = messages.getValue(id)

        override fun fetchRaw(id: String): ByteArray = raws.getValue(id)

        override fun fetchAttachments(id: String): List<AttachmentData> = attachments[id] ?: emptyList()
    }

    private class MapDocumentStore : DocumentStore {
        val map = mutableMapOf<String, ByteArray>()
        override fun put(content: ByteArray): String =
            content.size.toString(16) + "-" + content.hashCode().toString(16)
                .also { map[it] = content }

        override fun get(ref: String): ByteArray? = map[ref]
        override fun exists(ref: String): Boolean = map.containsKey(ref)
    }

    private lateinit var gmail: FakeGmail
    private lateinit var service: GmailIngestionService

    @BeforeEach
    fun setUp() {
        gmail = FakeGmail()
        knownDedupKeys.clear()
        savedSources.clear()
        savedSettings.clear()

        every { sourceRepo.findByDedupKey(any()) } answers { savedSources.find { it.dedupKey == firstArg<String>() } }
        every { sourceRepo.existsByDedupKey(any()) } answers { knownDedupKeys.contains(firstArg()) }
        every { sourceRepo.save(any<Source>()) } answers {
            val s = firstArg<Source>()
            savedSources.add(s)
            knownDedupKeys.add(s.dedupKey)
            s
        }
        every { settings.findById(any()) } answers {
            val key = firstArg<String>()
            savedSettings.find { it.key == key }?.let { Optional.of(it) } ?: Optional.empty()
        }
        every { settings.save(any<Setting>()) } answers {
            val s = firstArg<Setting>()
            savedSettings.removeAll { it.key == s.key }
            savedSettings.add(s)
            s
        }
        every { childRepo.findAll() } returns emptyList()

        service = GmailIngestionService(
            gmail = gmail,
            props = GoogleProperties(),
            documents = MapDocumentStore(),
            caseService = caseService,
            sourceRepo = sourceRepo,
            childRepo = childRepo,
            settings = settings,
            health = health,
            clock = clock,
            cases = mockk(relaxed = true),
            deliveries = mockk(relaxed = true) { every { save(any<de.schoolagent.email.EmailDelivery>()) } answers { firstArg() } },
            emailSettings = mockk { every { accepts(any(), any()) } returns true },
        )
    }

    private fun message(id: String, subject: String, internalMs: Long, attachment: AttachmentData? = null) {
        gmail.messages[id] = MessageMetadata(from = "school@example.org", subject = subject, internalDateMs = internalMs)
        gmail.raws[id] = "RAW-$id".toByteArray()
        if (attachment != null) gmail.attachments[id] = listOf(attachment)
    }

    @Test fun `large mailbox progresses in bounded pages and a repeated sweep remains idempotent`() {
        repeat(1000) { message("synthetic-$it", "School notice", it.toLong()) }
        repeat(40) {
            val result = service.pollOnce()
            assertThat(result.inspected).isEqualTo(25)
            assertThat(result.ingested).isEqualTo(25)
        }
        assertThat(savedSources).hasSize(1000)
        assertThat(service.pollOnce().ingested).isZero()
    }

    @Test
    fun `new messages become cases with EMAIL source and attachments`() {
        message("msg-1", "Elternbrief Schullandheim", 1_000, AttachmentData("brief.pdf", "application/pdf", byteArrayOf(1, 2, 3)))
        message("msg-2", "Newsletter", 2_000)

        val result = service.pollOnce()

        assertThat(result.ingested).isEqualTo(2)
        assertThat(result.attachments).isEqualTo(1)
        assertThat(savedSources.map { it.sourceType.name }).containsExactly("EMAIL", "ATTACHMENT", "EMAIL")
        assertThat(savedSources.first { it.sourceType.name == "EMAIL" }.title).isEqualTo("Elternbrief Schullandheim")
        verify(exactly = 2) { caseService.attachSource(any(), any()) }
        verify(exactly = 2) { caseService.transition(any<de.schoolagent.domain.model.SchoolCase>(), any(), any(), any()) }
        verify(exactly = 1) { health.success(ConnectorType.GMAIL) }
        assertThat(savedSettings.single { it.key == GmailIngestionService.CURSOR_KEY }.valueJson)
            .isEqualTo("2000")
    }

    @Test
    fun `re-poll is a no-op for already known messages (idempotency)`() {
        message("msg-1", "Elternbrief", 1_000)
        service.pollOnce()

        val second = service.pollOnce()

        assertThat(second.ingested).isEqualTo(0)
        assertThat(savedSources).hasSize(1)
        verify(exactly = 1) { caseService.attachSource(any(), any()) }
    }

    @Test
    fun `late arriving messages older than the cursor are still ingested`() {
        message("old-1", "Alt", 500)
        message("new-1", "Neu", 5_000)
        savedSettings.add(Setting(key = GmailIngestionService.CURSOR_KEY, valueJson = "1000"))

        val result = service.pollOnce()

        assertThat(result.ingested).isEqualTo(2)
        assertThat(savedSources.map { it.externalId }).containsExactlyInAnyOrder("old-1", "new-1")
    }

    @Test
    fun `same message id and filename in two child mailboxes remain separate`() {
        val first = UUID.randomUUID(); val second = UUID.randomUUID()
        message("same-id", "School notice", 5000, AttachmentData("letter.pdf", "application/pdf", byteArrayOf(1, 2)))
        assertThat(service.pollOnce(first).ingested).isEqualTo(1)
        assertThat(service.pollOnce(second).ingested).isEqualTo(1)
        assertThat(service.pollOnce(first).ingested).isZero()
        assertThat(savedSources).hasSize(4)
        assertThat(savedSources.map { it.dedupKey }).doesNotHaveDuplicates()
        verify { caseService.createCase(first, any(), any(), any(), any()) }
        verify { caseService.createCase(second, any(), any(), any(), any()) }
        assertThat(savedSettings.map { it.key }).contains("gmail.$first.cursor.internalDate", "gmail.$second.cursor.internalDate")
    }

    @Test
    fun `forwarded school email preserves provenance and does not skip portal sender`() {
        message("forwarded", "Fwd: Sports day", 5000)
        gmail.messages["forwarded"] = MessageMetadata("parent@example.org", "Fwd: Sports day", 5000)
        gmail.raws["forwarded"] = """
From: parent@example.org
Subject: Fwd: Sports day
Content-Type: text/plain; charset=UTF-8

---------- Forwarded message ---------
From: School <office@portal.school.example>
Subject: Sports day
Bring trainers on Friday.
""".trimIndent().toByteArray()
        service.pollOnce()
        assertThat(savedSources.single().forwardedBy).isEqualTo("parent@example.org")
        assertThat(savedSources.single().sender).contains("office@portal.school.example")
        assertThat(savedSources.single().sourceType).isEqualTo(de.schoolagent.domain.enums.SourceType.EMAIL)
    }

    @Test
    fun `gmail failure records connector health and propagates`() {
        gmail.failWith = GmailApiException("Gmail API 401", authRelated = true)

        assertThrows<GmailApiException> { service.pollOnce() }

        verify(exactly = 1) { health.failure(ConnectorType.GMAIL, match { it.contains("401") }) }
        verify(exactly = 0) { health.success(any()) }
    }
}
