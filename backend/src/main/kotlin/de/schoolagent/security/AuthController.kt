package de.schoolagent.security

import org.springframework.context.annotation.Configuration

import de.schoolagent.domain.repo.AppUserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Explicit session login (predictable JSON API, no filter-chain magic):
 * POST /api/auth/login {username, password, rememberMe} → JSESSIONID plus,
 * optionally, a 14-day remember-me cookie persisted in the database.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authManager: AuthenticationManager,
    private val rememberMeServices: org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices,
    private val users: AppUserRepository,
) {

    private val contextRepository: SecurityContextRepository = HttpSessionSecurityContextRepository()

    data class LoginRequest(val username: String, val password: String, val rememberMe: Boolean? = false)

    data class MeDto(
        val name: String,
        val displayName: String,
        val role: String,
        val whatsappNumber: String?,
        val language: String,
        val briefingEnabled: Boolean = true,
        val briefingTime: String = "19:00",
        val weeklyEnabled: Boolean = true,
        val weeklyTime: String = "10:00",
        val instantEnabled: Boolean = true,
    )

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest, httpRequest: HttpServletRequest, httpResponse: HttpServletResponse) {
        try {
            val auth = authManager.authenticate(
                UsernamePasswordAuthenticationToken(request.username.trim(), request.password),
            )
            val context: SecurityContext = SecurityContextHolder.createEmptyContext()
            context.authentication = auth
            SecurityContextHolder.setContext(context)
            contextRepository.saveContext(context, httpRequest, httpResponse)
            if (request.rememberMe == true) {
                // loginSuccess() looks for a form parameter, not the JSON body.
                rememberMeServices.loginSuccess(RememberMeRequest(httpRequest), httpResponse, auth)
            }
        } catch (e: Exception) {
            // answer directly — no exception through the filter chain (avoids redirect handling)
            httpResponse.status = 401
            httpResponse.contentType = "application/json"
            httpResponse.characterEncoding = "UTF-8"
            httpResponse.writer.write("""{"status":401,"error":"wrong username or password"}""")
        }
    }

    @PostMapping("/logout")
    fun logout(httpRequest: HttpServletRequest, httpResponse: HttpServletResponse) {
        val auth = SecurityContextHolder.getContext().authentication
        rememberMeServices.logout(httpRequest, httpResponse, auth)
        SecurityContextLogoutHandler().logout(httpRequest, httpResponse, auth)
    }

    @GetMapping("/me")
    fun me(@org.springframework.security.core.annotation.AuthenticationPrincipal user: UserDetails): MeDto {
        val account = users.findByName(user.username)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "user not found")
        return MeDto(
            account.name, account.displayName, account.role, account.whatsappNumber, account.language,
            account.briefingEnabled, account.briefingTime, account.weeklyEnabled, account.weeklyTime,
            account.instantEnabled,
        )
    }
}

@Configuration
class RememberMeConfig {
    @Bean
    fun rememberMeServices(
        @org.springframework.beans.factory.annotation.Value("\${schoolagent.rememberme-key:family-rememberme-key-change-me}") key: String,
        userDetailsService: UserDetailsService,
        tokenRepository: PersistentTokenRepository,
): PersistentTokenBasedRememberMeServices =
        PersistentTokenBasedRememberMeServices(key, userDetailsService, tokenRepository).apply {
            setTokenValiditySeconds(14 * 24 * 3600)
            setCookieName("schoolagent-rememberme")
        }
}

/** Makes JSON `rememberMe: true` look like the form field Spring Security expects. */
private class RememberMeRequest(request: HttpServletRequest) : jakarta.servlet.http.HttpServletRequestWrapper(request) {
    override fun getParameter(name: String): String? =
        if (name == AbstractRememberMeServices.DEFAULT_PARAMETER) "true" else super.getParameter(name)
}
