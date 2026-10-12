package de.schoolagent.security

import de.schoolagent.domain.enums.Actor
import de.schoolagent.domain.repo.AppUserRepository
import de.schoolagent.domain.repo.AuditEntryRepository
import de.schoolagent.domain.repo.ChildRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/** Runs production bootstrap without the fictional-family test configuration. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = [
    "spring.profiles.active=publication-test",
    "schoolagent.initial-username=test-admin",
    "schoolagent.initial-password=synthetic-startup-password",
])
class FreshInstallationTest {
    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17")
    }
    @Autowired lateinit var users: AppUserRepository
    @Autowired lateinit var children: ChildRepository
    @Autowired lateinit var audit: AuditEntryRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test fun `fresh database has only the explicitly configured administrator and no children`() {
        assertThat(users.findAll()).hasSize(1)
        val admin = users.findAll().single()
        assertThat(admin.name).isEqualTo("test-admin")
        assertThat(admin.role).isEqualTo("ADMIN")
        assertThat(admin.passwordHash).isNotEqualTo("PENDING").isNotEqualTo("synthetic-startup-password")
        assertThat(children.count()).isZero()
    }

    @Test fun `legacy private actor history reads as a human action without rewriting the stored label`() {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO audit_entry(id,actor,action,created_at) VALUES (?, 'OLD_PARENT_LABEL', 'EXAMPLE', now())", id)
        assertThat(audit.findById(id).get().actor).isEqualTo(Actor.MANUAL)
        assertThat(jdbc.queryForObject("SELECT actor FROM audit_entry WHERE id=?", String::class.java, id)).isEqualTo("OLD_PARENT_LABEL")
    }
}
