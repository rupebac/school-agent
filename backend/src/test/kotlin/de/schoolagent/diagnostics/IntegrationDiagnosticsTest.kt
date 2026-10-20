package de.schoolagent.diagnostics

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import de.schoolagent.connectors.school.SchoolRecordKind
import de.schoolagent.domain.model.AuditEntry
import de.schoolagent.domain.repo.AuditEntryRepository
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import java.time.*
import java.util.UUID

class IntegrationDiagnosticsTest {
    private val audit = mockk<AuditEntryRepository>(relaxed = true)
    private val tx = mockk<PlatformTransactionManager>(relaxed = true)
    private val now = Instant.parse("2026-10-20T10:00:00Z")
    private var current = now
    private val clock = object : Clock() {
        override fun instant() = current
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private val diagnostics = IntegrationDiagnostics(audit, tx, clock)
    private val entries = mutableListOf<AuditEntry>()
    init {
        every { audit.saveAndFlush(any<AuditEntry>()) } answers { firstArg<AuditEntry>().also(entries::add) }
    }

    @Test fun `durable and console traces contain only opaque references and fixed metadata`() {
        val logger = LoggerFactory.getLogger(IntegrationDiagnostics::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val child = UUID.randomUUID()
            val account = UUID.randomUUID()
            val raw = "https://school.example/private-record?student=synthetic-student"
            diagnostics.record(DiagnosticTrace(DiagnosticStage.SCHOOL_RECORD, DiagnosticOutcome.BEFORE_WINDOW,
                childId = child, accountId = account, recordKey = raw, recordKeys = listOf(raw)))
            val detail = ObjectMapper().readTree(entries.single().detail)
            assertThat(detail.path("childRef").asText()).matches("[a-f0-9]{64}")
            assertThat(detail.path("recordRef").asText()).isEqualTo(detail.path("recordRefs")[0].asText())
            val serialized = entries.single().detail + appender.list.joinToString { it.formattedMessage }
            assertThat(serialized).doesNotContain(child.toString(), account.toString(), raw, "synthetic-student", "school.example")
            val definition = slot<TransactionDefinition>()
            verify { tx.getTransaction(capture(definition)) }
            assertThat(definition.captured.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
            assertThat(entries.single().caseId).isNull() // Global diagnostics are only visible to admins.
        } finally { logger.detachAppender(appender) }
    }

    @Test fun `snapshot checkpoints deduplicate repeated sets but preserve removal and reappearance`() {
        val a = DiagnosticTrace(DiagnosticStage.SCHOOL_SNAPSHOT, DiagnosticOutcome.AVAILABLE,
            childId = UUID.randomUUID(), kind = SchoolRecordKind.EXAM, complete = true, recordKeys = listOf("one", "two"))
        diagnostics.snapshot(a)
        diagnostics.snapshot(a.copy(recordKeys = listOf("two", "one")))
        diagnostics.snapshot(a.copy(recordKeys = listOf("two")))
        diagnostics.snapshot(a)
        assertThat(entries).hasSize(3)
        current = now.plus(Duration.ofDays(1))
        diagnostics.snapshot(a)
        assertThat(entries).hasSize(4)
    }

    @Test fun `scheduler gates are bounded per day without conflating different notifications or weekly plans`() {
        val trace = DiagnosticTrace(DiagnosticStage.WHATSAPP, DiagnosticOutcome.TEMPLATE_NOT_READY,
            accountId = UUID.randomUUID(), notificationId = UUID.randomUUID())
        repeat(100) { diagnostics.oncePerDay(trace) }
        diagnostics.oncePerDay(trace.copy(notificationId = UUID.randomUUID()))
        diagnostics.oncePerDay(trace.copy(weekly = true))
        assertThat(entries).hasSize(3)
        current = now.plus(Duration.ofDays(1))
        diagnostics.oncePerDay(trace)
        assertThat(entries).hasSize(4)
    }

    @Test fun `diagnostic write failures including commit failures do not break business work or expose error messages`() {
        every { tx.commit(any<TransactionStatus>()) } throws IllegalStateException("synthetic-private-error")
        diagnostics.record(DiagnosticTrace(DiagnosticStage.SCHOOL_SYNC, DiagnosticOutcome.FAILED))
        verify { audit.saveAndFlush(any<AuditEntry>()) }
    }

    @Test fun `retention is sixty days and runs in an independent transaction`() {
        diagnostics.prune()
        verify(exactly = 1) { audit.deleteDiagnosticsBefore(now.minus(Duration.ofDays(60))) }
        verify(exactly = 1) { tx.commit(any()) }
    }
}
