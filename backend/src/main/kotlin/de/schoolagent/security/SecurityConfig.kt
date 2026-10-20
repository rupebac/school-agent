package de.schoolagent.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import javax.sql.DataSource

/**
 * Account security: session login with persistent remember-me, roles
 * ADMIN/PARENT for subaccount management. Basic auth stays enabled for API
 * clients/tests. CSRF is enforced for cookie-auth (browser) requests via the
 * XSRF cookie Angular handles automatically; Authorization-header requests
 * are stateless and exempt. Provider webhooks are exempt and verified by
 * their own tokens/signatures.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val dataSource: DataSource,
    private val users: de.schoolagent.domain.repo.AppUserRepository,
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun tokenRepository(): PersistentTokenRepository {
        val ds: DataSource = checkNotNull(dataSource)
        return JdbcTokenRepositoryImpl().apply { setDataSource(ds) }
    }

    @Bean
    fun filterChain(http: HttpSecurity, rememberMeServices: AbstractRememberMeServices): SecurityFilterChain {
        http
            .csrf { csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    // plain handler: browser clients echo the raw cookie value (Angular style)
                    .csrfTokenRequestHandler(org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler())
                    .requireCsrfProtectionMatcher { r ->
                        // only state-changing, cookie-auth requests need the token;
                        // Authorization-header API calls are stateless
                        r.getHeader("Authorization") == null &&
                            !r.method.equals("GET", ignoreCase = true) &&
                            !r.method.equals("HEAD", ignoreCase = true) &&
                            !r.method.equals("OPTIONS", ignoreCase = true)
                    }
                    .ignoringRequestMatchers(
                        "/api/whatsapp/webhook/**",
                        "/api/google/callback",
                        "/api/auth/login",
                        "/api/auth/logout",
                    )
            }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                    .requestMatchers("/api/auth/login", "/api/auth/logout").permitAll()
                    .requestMatchers("/api/whatsapp/webhook/**").permitAll()
                    .requestMatchers("/api/google/callback").permitAll()
                    .requestMatchers("/api/whatsapp/**").hasRole("ADMIN")
                    .requestMatchers("/api/admin/**", "/api/reason/pending", "/api/reason/fallback-cases", "/api/reason/scores").hasRole("ADMIN")
                    .anyRequest().authenticated()
            }
            .httpBasic(Customizer.withDefaults())
            .rememberMe { rememberMe -> rememberMe.rememberMeServices(rememberMeServices) }
            .exceptionHandling { handling ->
                // XHR gets a clean 401 (no redirect); page navigations go to the SPA login
                handling.authenticationEntryPoint { request, response, _ ->
                    if (request.requestURI.startsWith("/api") || request.requestURI == "/error") {
                        respondUnauthorized(request, response)
                    } else {
                        response.sendRedirect("/login")
                    }
                }
            }
        http.addFilterBefore(CurrentAccountFilter(users), org.springframework.security.web.access.intercept.AuthorizationFilter::class.java)
        http.addFilterAfter(CsrfCookieFilter(), org.springframework.security.web.authentication.www.BasicAuthenticationFilter::class.java)
        return http.build()
    }

    /** Renders the deferred CSRF token so browsers receive the XSRF-TOKEN cookie. */
    class CsrfCookieFilter : org.springframework.web.filter.OncePerRequestFilter() {
        override fun doFilterInternal(
            request: HttpServletRequest,
            response: HttpServletResponse,
            chain: jakarta.servlet.FilterChain,
        ) {
            (request.getAttribute("_csrf") as? org.springframework.security.web.csrf.CsrfToken)?.getToken()
            chain.doFilter(request, response)
        }
    }

    private fun respondUnauthorized(request: HttpServletRequest, response: HttpServletResponse) {
        val xhr = request.getHeader("X-Requested-With") == "XMLHttpRequest" ||
            (request.getHeader("Accept") ?: "").contains("application/json")
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        if (!xhr) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"school-agent\"")
        }
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.writer.write("""{"status":401,"error":"Unauthorized"}""")
    }

    @Bean
    fun authenticationManager(
        config: org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration,
    ): AuthenticationManager = config.authenticationManager
}
