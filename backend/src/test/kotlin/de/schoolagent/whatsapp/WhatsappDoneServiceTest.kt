package de.schoolagent.whatsapp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.schoolagent.domain.enums.*
import de.schoolagent.domain.model.*
import de.schoolagent.domain.repo.*
import de.schoolagent.domain.service.CaseService
import de.schoolagent.domain.service.FamilyInboxService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class WhatsappDoneServiceTest {
    companion object {
        @Container @ServiceConnection @JvmStatic val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17")
    }
    @Autowired lateinit var done: WhatsappDoneService
    @Autowired lateinit var inbox: FamilyInboxService
    @Autowired lateinit var lifecycle: CaseService
    @Autowired lateinit var users: AppUserRepository
    @Autowired lateinit var cases: SchoolCaseRepository
    @Autowired lateinit var kids: ChildRepository
    @Autowired lateinit var notifications: NotificationRepository
    @Autowired lateinit var actions: ParentActionRepository
    @Autowired lateinit var requirements: ChildRequirementRepository
    @Autowired lateinit var history: CaseStatusEntryRepository
    @Autowired lateinit var audit: AuditEntryRepository
    private val json = jacksonObjectMapper()
    private data class Fixture(val user: AppUser, val c: SchoolCase, val notice: Notification)

    private fun fixture(childId: UUID? = null): Fixture {
        val user = users.save(AppUser(name = "synthetic-${UUID.randomUUID()}", displayName = "Synthetic Parent",
            passwordHash = "synthetic", whatsappNumber = "+12025550111"))
        val c = lifecycle.createCase(childId, "Synthetic permission slip")
        lifecycle.transition(c, CaseStatus.INGESTING)
        lifecycle.transition(c, CaseStatus.READY_FOR_REASONING)
        lifecycle.transition(c, CaseStatus.ACTION_REQUIRED)
        actions.save(ParentAction(caseId = c.id, title = "Sign the slip", dedupKey = UUID.randomUUID().toString()))
        requirements.save(ChildRequirement(caseId = c.id, description = "Bring the slip", dedupKey = UUID.randomUUID().toString()))
        val notice = notifications.save(Notification(caseId = c.id, recipientId = user.id, channel = NotificationChannel.WHATSAPP,
            level = NotificationLevel.IMMEDIATE, message = "Synthetic notice", status = NotificationStatus.ACCEPTED,
            dedupKey = "decision:${c.id}:${inbox.revision(c.id)}:false:${user.id}:0", messageId = "synthetic-${UUID.randomUUID()}",
            templatePayload = json.writeValueAsString(mapOf("name" to WaTemplates.name(WaTemplates.Kind.ACTION, 1)))))
        return Fixture(user, c, notice)
    }

    private fun button(f: Fixture) = json.createObjectNode().apply {
        put("type", "button"); put("from", "12025550111")
        putObject("context").put("id", f.notice.messageId)
        putObject("button").put("text", "Mark as done").put("payload", WhatsappDoneService.payload(f.notice.id))
    }

    @Test fun `button completes the same family obligations and writes history only once`() {
        val f = fixture()
        assertThat(done.handle(button(f), f.user)).startsWith("Marked as done for the family")
        assertThat(done.handle(button(f), f.user)).startsWith("Already marked as done")
        assertThat(cases.findById(f.c.id).orElseThrow().status).isEqualTo(CaseStatus.COMPLETED)
        assertThat(actions.findByCaseId(f.c.id)).allMatch { it.status == ActionStatus.COMPLETED }
        assertThat(requirements.findByCaseId(f.c.id)).allMatch { it.status == RequirementStatus.DONE }
        assertThat(history.findByCaseIdOrderByCreatedAtAsc(f.c.id).filter { it.toStatus == CaseStatus.COMPLETED }).hasSize(1)
        assertThat(audit.findAll().filter { it.caseId == f.c.id && it.action == "FAMILY_DONE" }).hasSize(1)
        assertThat(f.c.completionSnapshot).isNotBlank()
    }

    @Test fun `changed facts reject an old button without completing any duty`() {
        val f = fixture()
        actions.save(ParentAction(caseId = f.c.id, title = "A new duty", dedupKey = UUID.randomUUID().toString()))
        assertThat(done.handle(button(f), f.user)).contains("has changed")
        assertThat(f.c.status).isEqualTo(CaseStatus.ACTION_REQUIRED)
        assertThat(actions.findByCaseId(f.c.id)).allMatch { it.status == ActionStatus.OPEN }
    }

    @Test fun `wrong recipient wrong context malformed payload and typed text cannot complete a case`() {
        val f = fixture()
        val other = users.save(AppUser(name = "other-${UUID.randomUUID()}", displayName = "Other Synthetic Parent", passwordHash = "synthetic"))
        assertThat(done.handle(button(f), other)).contains("unavailable")
        val wrongContext = button(f).apply { withObject("context").put("id", "another-notification") }
        assertThat(done.handle(wrongContext, f.user)).contains("unavailable")
        val invalid = button(f).apply { withObject("button").put("payload", "school-agent:done:invalid") }
        assertThat(done.handle(invalid, f.user)).contains("unavailable")
        assertThat(done.handle(button(f).put("type", "text"), f.user)).contains("unavailable")
        assertThat(f.c.status).isEqualTo(CaseStatus.ACTION_REQUIRED)
    }

    @Test fun `revoked child access and changed phone assignment cannot use the original button`() {
        val child = kids.save(Child(name = "Synthetic Child"))
        val f = fixture(child.id)
        assertThat(done.handle(button(f), f.user)).contains("unavailable")
        assertThat(f.c.status).isEqualTo(CaseStatus.ACTION_REQUIRED)
        val family = fixture()
        family.user.whatsappNumber = "+12025550112"
        users.save(family.user)
        assertThat(done.handle(button(family), family.user)).contains("unavailable")
        assertThat(family.c.status).isEqualTo(CaseStatus.ACTION_REQUIRED)
    }

    @Test fun `interactive reply ID works and confirmation uses the account language`() {
        val f = fixture()
        f.user.language = "es"
        val message = button(f).put("type", "interactive").apply {
            putObject("interactive").putObject("button_reply").put("id", WhatsappDoneService.payload(f.notice.id))
        }
        assertThat(done.handle(message, f.user)).startsWith("Marcado como hecho para la familia")
    }

    @Test fun `information cancelled and processing cases cannot be completed through a stale action button`() {
        for (status in listOf(CaseStatus.INFORMATION_ONLY, CaseStatus.CANCELLED, CaseStatus.INVESTIGATING)) {
            val f = fixture()
            f.c.status = status
            f.c.informationOnly = status == CaseStatus.INFORMATION_ONLY
            cases.save(f.c)
            assertThat(done.handle(button(f), f.user)).contains("has changed")
            assertThat(f.c.status).isEqualTo(status)
        }
    }
}
