package de.schoolagent.security

import de.schoolagent.domain.repo.AppUserRepository
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.*
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.User
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices

class AuthControllerTest {
    private val manager = mockk<AuthenticationManager>()
    private val remember = mockk<AbstractRememberMeServices>()
    private val controller = AuthController(manager, remember, mockk<AppUserRepository>())
    @AfterEach fun clear() = SecurityContextHolder.clearContext()

    @Test fun `persistent login failure cannot leave an authenticated session`() {
        val principal = User.withUsername("example").password("unused").roles("PARENT").build()
        every { manager.authenticate(any()) } returns UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        every { remember.loginSuccess(any(), any(), any()) } throws IllegalStateException("private infrastructure detail")
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        controller.login(AuthController.LoginRequest("example", "synthetic-password", true), request, response)
        assertThat(response.status).isEqualTo(503)
        assertThat(response.contentAsString).doesNotContain("private infrastructure detail", "wrong username")
        assertThat(request.getSession(false)).isNull()
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test fun `invalid credentials and authentication outages have different outcomes`() {
        for ((failure, status) in listOf(BadCredentialsException("internal") to 401,
            AuthenticationServiceException("private database failure") to 503)) {
            every { manager.authenticate(any()) } throws failure
            val request = MockHttpServletRequest()
            val response = MockHttpServletResponse()
            controller.login(AuthController.LoginRequest("example", "synthetic-password"), request, response)
            assertThat(response.status).isEqualTo(status)
            assertThat(request.getSession(false)).isNull()
            assertThat(response.contentAsString).doesNotContain("internal", "private database failure")
        }
    }
}
