package de.schoolagent

import de.schoolagent.domain.model.AppUser
import de.schoolagent.domain.model.Child
import de.schoolagent.domain.repo.AppUserRepository
import de.schoolagent.domain.repo.ChildRepository
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.UUID

/** Fictional integration-test family; never included in the production artifact. */
@Configuration(proxyBeanMethods = false)
@Profile("test")
class TestFamilyConfiguration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun testFamily(users: AppUserRepository, children: ChildRepository, encoder: PasswordEncoder) = ApplicationRunner {
        val admin = users.findByName("bootstrap-admin")!!
        val parent = users.save(AppUser(id = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            name = "casey", displayName = "Casey", passwordHash = encoder.encode("test-only-password")!!))
        val child = Child(id = UUID.fromString("00000000-0000-0000-0000-000000000003"), name = "Jamie")
        child.accounts.addAll(listOf(admin, parent))
        children.save(child)
    }
}
