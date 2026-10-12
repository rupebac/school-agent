package de.schoolagent.email

import de.schoolagent.connectors.gmail.*
import de.schoolagent.connectors.google.GoogleProperties
import de.schoolagent.documents.DocumentStore
import de.schoolagent.domain.enums.*
import de.schoolagent.domain.model.*
import de.schoolagent.domain.repo.*
import de.schoolagent.domain.service.CaseService
import de.schoolagent.health.HealthTracker
import de.schoolagent.ingestion.GmailIngestionService
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["schoolagent.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="])
class EmailIntegrationTest {
    companion object {
        @Container @ServiceConnection @JvmStatic val postgres = PostgreSQLContainer("postgres:17")
    }
    @Autowired lateinit var aiCache: de.schoolagent.ai.AiResponseCache
    @Autowired lateinit var sources: SourceRepository
    @Autowired lateinit var children: ChildRepository
    @Autowired lateinit var cases: SchoolCaseRepository
    @Autowired lateinit var caseService: CaseService
    @Autowired lateinit var deliveries: EmailDeliveryRepository
    @Autowired lateinit var settings: SettingRepository
    @Autowired lateinit var emailSettings: SchoolEmailSettings
    @Autowired lateinit var transactions: TransactionTemplate
    @Autowired lateinit var status: CaseStatusEntryRepository
    @Autowired lateinit var audit: AuditEntryRepository

    private class Mailbox : GmailClient {
        val mail = linkedMapOf<String, String>()
        override fun listMessageIds(maxResults: Int) = mail.keys.toList()
        override fun fetchMetadata(id: String) = MessageMetadata("parent@example.org", "Forward", 1000)
        override fun fetchRaw(id: String) = mail.getValue(id).toByteArray()
        override fun fetchAttachments(id: String) = emptyList<AttachmentData>()
    }
    private val docs = object : DocumentStore {
        private val data = mutableMapOf<String, ByteArray>()
        override fun put(content: ByteArray) = EmailContent.hash(content.decodeToString()).also { data[it] = content }
        override fun get(ref: String) = data[ref]
        override fun exists(ref: String) = data.containsKey(ref)
    }
    private fun service(box: Mailbox) = GmailIngestionService(box, GoogleProperties(), docs, caseService,
        sources, children, settings, mockk<HealthTracker>(relaxed = true), Clock.systemUTC(), deliveries, emailSettings, cases)
    private fun mail(id: String, references: String = "", body: String = "The meeting is Tuesday at 15:00.") = """
From: teacher@school.example
To: parent@example.org
Subject: Meeting
Date: Mon, 28 Sep 2026 10:00:00 +0200
Message-ID: <$id@school.example>
References: $references
Content-Type: text/plain

$body
""".trimIndent()
    private fun forward(parent: String) = """
From: $parent
Subject: Fwd: Meeting
Content-Type: text/plain

---------- Forwarded message ---------
From: teacher@school.example
Date: Mon, 28 Sep 2026 10:00:00 +0200
Subject: Meeting
To: parent@example.org

The meeting is Tuesday at 15:00.
""".trimIndent()

    @Test fun `AI cache survives router recreation and case rollback and changes with input`() {
        val config = io.mockk.mockk<ModelConfigRepository>()
        io.mockk.every { config.findByRole(ModelRole.TEXT) } returns ModelConfig(role = ModelRole.TEXT, provider = "test", modelName = "test-model")
        val usage = io.mockk.mockk<AiUsageRepository>()
        io.mockk.every { usage.save(any<AiUsage>()) } answers { firstArg() }
        val chat = io.mockk.mockk<de.schoolagent.ai.ChatHttpClient>()
        io.mockk.every { chat.complete(any(), any(), any()) } returns com.fasterxml.jackson.databind.ObjectMapper().readTree("""{"choices":[{"message":{"content":"{\"text\":\"Saved answer\"}"}}]}""")
        fun router() = de.schoolagent.ai.ModelRouter(config, usage, chat, Clock.systemUTC(), "test", "fake-test-key", aiCache)
        transactions.executeWithoutResult { tx -> router().complete(ModelRole.TEXT, "Cache test", "original"); tx.setRollbackOnly() }
        assertThat(router().complete(ModelRole.TEXT, "Cache test", "original")).contains("Saved answer")
        io.mockk.verify(exactly = 1) { chat.complete(any(), any(), any()) }
        router().complete(ModelRole.TEXT, "Cache test", "updated")
        io.mockk.verify(exactly = 2) { chat.complete(any(), any(), any()) }
    }

    @Test fun `both parents forwarding the same original produce one source and retain both deliveries`() {
        val kid = children.save(Child(name = "Dedup test")); val box = Mailbox()
        box.mail["mum"] = forward("mum@example.org"); box.mail["dad"] = forward("dad@example.org")
        val service = service(box)
        val first = transactions.execute { service.pollOnce(kid.id) }!!
        val again = transactions.execute { service.pollOnce(kid.id) }!!
        assertThat(first.ingested).isEqualTo(1); assertThat(again.ingested).isZero()
        assertThat(deliveries.findByChildId(kid.id)).hasSize(2)
        assertThat(deliveries.findByChildId(kid.id).map { it.sourceId }.distinct()).hasSize(1)
        val schoolCase = cases.findAll().single { it.childId == kid.id }
        assertThat(sources.findByCaseId(schoolCase.id)).hasSize(1)
        assertThat(audit.findAll().filter { it.caseId == schoolCase.id }.map { it.action }).contains("SOURCE_ATTACHED")
    }

    @Test fun `reply attaches to information case and reopens through history without mixing children`() {
        val kid = children.save(Child(name = "Thread test")); val other = children.save(Child(name = "Other test"))
        val box = Mailbox(); box.mail["first"] = mail("first")
        val service = service(box)
        transactions.execute { service.pollOnce(kid.id) }
        val schoolCase = cases.findAll().single { it.childId == kid.id }
        caseService.transition(schoolCase.id, CaseStatus.READY_FOR_REASONING)
        caseService.transition(schoolCase.id, CaseStatus.INFORMATION_ONLY)
        box.mail["reply"] = mail("reply", "<first@school.example>", "Correction: Wednesday at 16:00.")
        transactions.execute { service.pollOnce(kid.id) }
        assertThat(cases.findAll().filter { it.childId == kid.id }).hasSize(1)
        assertThat(sources.findByCaseId(schoolCase.id)).hasSize(2)
        assertThat(cases.findById(schoolCase.id).get().status).isEqualTo(CaseStatus.INVESTIGATING)
        assertThat(status.findByCaseIdOrderByCreatedAtAsc(schoolCase.id).last().toStatus).isEqualTo(CaseStatus.INVESTIGATING)
        transactions.execute { service.pollOnce(other.id) }
        assertThat(cases.findAll().filter { it.childId == other.id }).hasSize(1)
        assertThat(deliveries.findByChildId(other.id).map { it.sourceId }).doesNotContainAnyElementsOf(deliveries.findByChildId(kid.id).map { it.sourceId })
    }

    @Test fun `reply arriving first still joins original later and same subject without references stays separate`() {
        val kid = children.save(Child(name = "Reverse order test")); val box = Mailbox()
        box.mail["reply"] = mail("reply", "<first@school.example>", "Wednesday is better.")
        box.mail["first"] = mail("first")
        box.mail["unrelated"] = mail("unrelated", body = "A different meeting.")
        transactions.execute { service(box).pollOnce(kid.id) }
        assertThat(cases.findAll().filter { it.childId == kid.id }).hasSize(2)
    }

    @Test fun `domain filtering reads original forward and excluded messages can be picked up after settings change`() {
        val kid = children.save(Child(name = "Filter test")); val box = Mailbox(); box.mail["forward"] = forward("mum@example.org")
        val service = service(box)
        emailSettings.save(kid.id, listOf("another.example"))
        assertThat(transactions.execute { service.pollOnce(kid.id) }!!.ingested).isZero()
        emailSettings.save(kid.id, listOf("@school.example"))
        assertThat(transactions.execute { service.pollOnce(kid.id) }!!.ingested).isEqualTo(1)
    }
}
