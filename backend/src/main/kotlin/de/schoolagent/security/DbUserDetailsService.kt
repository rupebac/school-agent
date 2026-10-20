package de.schoolagent.security

import de.schoolagent.domain.repo.AppUserRepository
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

/** Users live in the database (app_user); passwords are bcrypt hashes. */
@Service
class DbUserDetailsService(
    private val users: AppUserRepository,
) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {
        val user = users.findByName(username.lowercase().trim())
            ?: throw UsernameNotFoundException("unknown user: $username")
        return AccountPrincipal(user.name, user.passwordHash, user.role.ifBlank { "PARENT" })
    }
}
