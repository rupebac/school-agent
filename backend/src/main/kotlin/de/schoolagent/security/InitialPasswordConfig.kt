package de.schoolagent.security

import de.schoolagent.domain.model.AppUser
import de.schoolagent.domain.repo.AppUserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Clock

/**
 * Users are seeded by migration with the sentinel hash PENDING; on first boot
 * we replace it with a real bcrypt hash of SCHOOLAGENT_INITIAL_PASSWORD so no
 * plaintext or fixed hash ever lives in the repository.
 */
@Configuration
class InitialPasswordConfig {

    @Bean
    fun initialPasswordSetter(
        users: AppUserRepository,
        encoder: PasswordEncoder,
        clock: Clock,
        @Value("\${schoolagent.initial-username:}") initialUsername: String,
        @Value("\${schoolagent.initial-password:}") initialPassword: String,
    ): ApplicationRunner = ApplicationRunner {
        val pending = users.findAll().filter { it.passwordHash == "PENDING" }
        if (pending.isNotEmpty()) {
            DeploymentValidation.password(initialPassword, "SCHOOLAGENT_INITIAL_PASSWORD")
            val bootstrap = pending.firstOrNull { it.name == "bootstrap-admin" }
            if (bootstrap != null) {
                require(initialUsername.matches(Regex("[a-z][a-z0-9._-]{2,63}"))) {
                    "Set SCHOOLAGENT_INITIAL_USERNAME to a lowercase login name (3–64 characters)"
                }
                require(users.findByName(initialUsername) == null) { "Initial login name is already in use" }
                bootstrap.name = initialUsername
                bootstrap.displayName = initialUsername
            }
            pending.forEach { user: AppUser ->
                val hash = encoder.encode(initialPassword) ?: return@forEach
                user.passwordHash = hash
                user.updatedAt = clock.instant()
                users.save(user)
            }
        }
    }
}
