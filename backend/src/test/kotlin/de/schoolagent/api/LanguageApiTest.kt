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

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LanguageApiTest {

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

    private fun exchange(method: HttpMethod, path: String, body: String? = null): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            setBasicAuth("morgan", "test-only-password")
            contentType = MediaType.APPLICATION_JSON
        }
        return try {
            rest.exchange(url(path), method, HttpEntity(body, headers), String::class.java)
        } catch (e: HttpClientErrorException) {
            ResponseEntity(e.responseBodyAsString, e.responseHeaders, e.statusCode)
        }
    }

    @Test
    fun `me includes language and self-service switches to spanish`() {
        exchange(HttpMethod.POST, "/api/account/language", """{"language":"en"}""")
        val me = exchange(HttpMethod.GET, "/api/auth/me")
        assertThat(me.statusCode.value()).isEqualTo(200)
        assertThat(me.body).contains("\"language\":\"en\"")

        val updated = exchange(HttpMethod.POST, "/api/account/language", """{"language":"es"}""")
        assertThat(updated.statusCode.value()).isEqualTo(200)
        assertThat(updated.body).contains("\"language\":\"es\"")

        val after = exchange(HttpMethod.GET, "/api/auth/me")
        assertThat(after.body).contains("\"language\":\"es\"")
    }

    @Test
    fun `self-service can set a whatsapp number`() {
        val updated = exchange(HttpMethod.POST, "/api/account/whatsapp", """{"whatsappNumber":"+12025550110"}""")
        assertThat(updated.statusCode.value()).isEqualTo(200)
        assertThat(updated.body).contains("+12025550110")
        assertThat(exchange(HttpMethod.GET, "/api/auth/me").body).contains("+12025550110")
    }

    @Test
    fun `spanish-prefixed codes collapse to es`() {
        val updated = exchange(HttpMethod.POST, "/api/account/language", """{"language":"es-MX"}""")
        assertThat(updated.statusCode.value()).isEqualTo(200)
        assertThat(updated.body).contains("\"language\":\"es\"")
    }
}
