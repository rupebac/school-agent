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
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["schoolagent.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="],
)
class SettingsSecretsApiTest {

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

    private fun exchange(method: HttpMethod, path: String, body: String? = null, auth: Boolean = true): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            if (auth) setBasicAuth("morgan", "test-only-password")
            contentType = MediaType.APPLICATION_JSON
        }
        return try {
            rest.exchange(url(path), method, HttpEntity(body, headers), String::class.java)
        } catch (e: HttpClientErrorException) {
            ResponseEntity(e.responseBodyAsString, e.statusCode)
        }
    }

    @Test
    fun `school email domains are child scoped validated and authenticated`() {
        val kid = "00000000-0000-0000-0000-000000000003"
        assertThat(exchange(HttpMethod.GET, "/api/google/email-settings?kid=$kid", auth = false).statusCode.value()).isEqualTo(401)
        val saved = exchange(HttpMethod.PUT, "/api/google/email-settings?kid=$kid", """{"domains":["@School.DE","*.school.example","teachers.example"]}""")
        assertThat(saved.statusCode.value()).isEqualTo(200)
        assertThat(saved.body).contains("school.example", "teachers.example")
        assertThat(exchange(HttpMethod.GET, "/api/google/email-settings?kid=$kid").body).isEqualTo(saved.body)
        assertThat(exchange(HttpMethod.PUT, "/api/google/email-settings?kid=$kid", """{"domains":["https://school.example"]}""").statusCode.value()).isEqualTo(400)
        assertThat(exchange(HttpMethod.GET, "/api/google/email-settings?kid=${java.util.UUID.randomUUID()}").statusCode.value()).isEqualTo(403)
        assertThat(exchange(HttpMethod.GET, "/api/cases/${java.util.UUID.randomUUID()}/email-thread", auth = false).statusCode.value()).isEqualTo(401)
    }

    @Test
    fun `whatsapp status hides secrets from anonymous callers and returns them after save`() {
        assertThat(exchange(HttpMethod.GET, "/api/whatsapp/status", auth = false).statusCode.value()).isEqualTo(401)

        val kid = "00000000-0000-0000-0000-000000000003"
        val saved = exchange(
            HttpMethod.PUT,
            "/api/whatsapp/config?kid=$kid",
            """{"apiUrl":"http://127.0.0.1:9","phoneNumberId":"111","wabaId":"222","token":"EAA-test-token","verifyToken":"school-verify","appSecret":"app-secret"}""",
        )
        assertThat(saved.statusCode.value()).isEqualTo(200)
        assertThat(saved.body).contains("EAA-test-token", "school-verify", "app-secret", "222")

        val status = exchange(HttpMethod.GET, "/api/whatsapp/status?kid=$kid")
        assertThat(status.statusCode.value()).isEqualTo(200)
        assertThat(status.body).contains("EAA-test-token", "school-verify", "app-secret", "222")
        assertThat(status.body).contains("\"hasToken\":true", "\"hasVerifyToken\":true")
        assertThat(status.body).contains("\"name\":\"morgan\"", "\"displayName\":\"morgan\"")
        assertThat(status.body).contains("\"recipients\":[")
    }

    @Test
    fun `whatsapp test requires a selected account that has a number`() {
        val kid = "00000000-0000-0000-0000-000000000003"
        exchange(
            HttpMethod.PUT,
            "/api/whatsapp/config?kid=$kid",
            """{"phoneNumberId":"111","token":"tok","verifyToken":"v"}""",
        )
        val missingTo = exchange(HttpMethod.POST, "/api/whatsapp/test?kid=$kid", """{}""")
        assertThat(missingTo.statusCode.value()).isEqualTo(200)
        assertThat(missingTo.body).contains("select an account")

        val noNumber = exchange(HttpMethod.POST, "/api/whatsapp/test?kid=$kid", """{"to":"morgan"}""")
        assertThat(noNumber.statusCode.value()).isEqualTo(200)
        assertThat(noNumber.body).contains("no WhatsApp number")
    }

    @Test
    fun `elternportal status returns the stored password in plaintext`() {
        val kid = "00000000-0000-0000-0000-000000000003"
        val saved = exchange(
            HttpMethod.PUT,
            "/api/elternportal/config?kid=$kid",
            """{"url":"https://school.eltern-portal.org","username":"parent@example.com","password":"portal-secret"}""",
        )
        assertThat(saved.statusCode.value()).isEqualTo(200)
        assertThat(saved.body).contains("portal-secret", "parent@example.com")

        val status = exchange(HttpMethod.GET, "/api/elternportal/status?kid=$kid")
        assertThat(status.statusCode.value()).isEqualTo(200)
        assertThat(status.body).contains("portal-secret")
    }
    @Test
    fun `school provider is exclusive and Schulmanager credentials stay masked`() {
        val created = exchange(HttpMethod.POST, "/api/admin/kids", """{"name":"School connection test","color":"#7c3aed","accountNames":["morgan"]}""")
        assertThat(created.statusCode.value()).describedAs(created.body).isEqualTo(200)
        val kid = com.fasterxml.jackson.databind.ObjectMapper().readTree(created.body).path("id").asText()
        val query = "?kid=$kid"
        assertThat(exchange(HttpMethod.GET, "/api/school-connection/status$query", auth = false).statusCode.value()).isEqualTo(401)
        val old = exchange(HttpMethod.PUT, "/api/elternportal/config$query", """{"url":"https://school.eltern-portal.org","username":"parent@example.test","password":"old-secret"}""")
        assertThat(old.statusCode.value()).isEqualTo(200)
        val saved = exchange(HttpMethod.PUT, "/api/school-connection/config$query", """{"provider":"SCHULMANAGER","username":"parent@example.test","password":"new-secret","studentId":"7","institutionId":"1","studentName":"Taylor Example"}""")
        assertThat(saved.statusCode.value()).isEqualTo(200)
        assertThat(saved.body).contains("SCHULMANAGER", "\"hasPassword\":true", "\"studentId\":\"7\"").doesNotContain("new-secret", "old-secret")
        assertThat(exchange(HttpMethod.POST, "/api/elternportal/test$query", "{}").statusCode.is4xxClientError).isTrue()
        val retained = exchange(HttpMethod.PUT, "/api/school-connection/config$query", """{"provider":"SCHULMANAGER","username":"parent@example.test","studentId":"7","institutionId":"1"}""")
        assertThat(retained.body).contains("\"configured\":true")
        val switched = exchange(HttpMethod.PUT, "/api/elternportal/config$query", """{"url":"https://school.eltern-portal.org","username":"parent@example.test","password":"replacement"}""")
        assertThat(switched.body).contains("ELTERNPORTAL").doesNotContain("SCHULMANAGER", "Taylor Example")
        assertThat(exchange(HttpMethod.DELETE, "/api/school-connection/config$query").statusCode.is2xxSuccessful).isTrue()
        assertThat(exchange(HttpMethod.GET, "/api/school-connection/status$query").body).contains("\"configured\":false")
    }

}
