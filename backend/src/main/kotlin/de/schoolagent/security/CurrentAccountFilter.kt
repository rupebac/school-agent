package de.schoolagent.security

import de.schoolagent.domain.repo.AppUserRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.User
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/** An authenticated session must not retain privileges after an account change. */
class CurrentAccountFilter(private val users: AppUserRepository) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val authentication = SecurityContextHolder.getContext().authentication
        val principal = authentication?.principal as? AccountPrincipal
        if (principal != null) {
            val account = users.findByName(principal.username)
            if (account == null || principal.stamp != AccountPrincipal.stamp(account.passwordHash, account.role)) {
                SecurityContextLogoutHandler().logout(request, response, authentication)
                response.status = HttpServletResponse.SC_UNAUTHORIZED
                response.contentType = "application/json"
                response.writer.write("""{"status":401,"error":"Sign in again"}""")
                return
            }
        }
        chain.doFilter(request, response)
    }
}

/** The stamp survives Spring's credential erasure; it is never returned in an API response. */
class AccountPrincipal(name: String, passwordHash: String, role: String) :
    User(name, passwordHash, listOf(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_$role"))) {
    val stamp: String = stamp(passwordHash, role)
    companion object {
        fun stamp(passwordHash: String, role: String): String = MessageDigest.getInstance("SHA-256")
            .digest("$role:$passwordHash".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
