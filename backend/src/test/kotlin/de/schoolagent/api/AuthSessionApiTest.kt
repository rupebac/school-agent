package de.schoolagent.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Redeploy restarts Tomcat and drops in-memory JSESSIONIDs. Login must come
 * back from the remember-me cookie (persistent_logins), not the dead session.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthSessionApiTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17")
    }

    @Value("\${local.server.port}")
    lateinit var port: String

    private val rest = RestTemplate()

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    private fun exchange(
        method: HttpMethod,
        path: String,
        body: String? = null,
        cookies: List<String> = emptyList(),
    ): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Requested-With", "XMLHttpRequest")
            if (cookies.isNotEmpty()) set(HttpHeaders.COOKIE, cookies.joinToString("; "))
        }
        return try {
            rest.exchange(url(path), method, HttpEntity(body, headers), String::class.java)
        } catch (e: HttpClientErrorException) {
            ResponseEntity(e.responseBodyAsString, e.responseHeaders, e.statusCode)
        }
    }

    private fun cookieValues(response: ResponseEntity<*>, name: String): List<String> =
        (response.headers[HttpHeaders.SET_COOKIE] ?: emptyList())
            .filter { it.startsWith("$name=") }
            .map { it.substringBefore(';') }

    @Test
    fun `remember-me cookie restores login after the session cookie is gone`() {
        val login = exchange(
            HttpMethod.POST,
            "/api/auth/login",
            """{"username":"morgan","password":"test-only-password","rememberMe":true}""",
        )
        assertThat(login.statusCode.value()).isEqualTo(200)
        val remember = cookieValues(login, "schoolagent-rememberme")
        assertThat(remember).isNotEmpty()

        val restored = exchange(HttpMethod.GET, "/api/auth/me", cookies = remember)
        assertThat(restored.statusCode.value()).isEqualTo(200)
        assertThat(restored.body).contains("\"name\":\"morgan\"")
    }

    @Test
    fun `session-only login does not issue a remember-me cookie`() {
        val login = exchange(
            HttpMethod.POST,
            "/api/auth/login",
            """{"username":"morgan","password":"test-only-password","rememberMe":false}""",
        )
        assertThat(login.statusCode.value()).isEqualTo(200)
        assertThat(cookieValues(login, "schoolagent-rememberme")).isEmpty()

        val me = exchange(HttpMethod.GET, "/api/auth/me")
        assertThat(me.statusCode.value()).isEqualTo(401)
    }
}
