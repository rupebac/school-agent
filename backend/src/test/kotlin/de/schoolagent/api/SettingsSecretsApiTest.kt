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

    @org.springframework.beans.factory.annotation.Autowired
    lateinit var secretRefs: de.schoolagent.domain.repo.SecretRefRepository

    @Test
    fun `Google client settings are admin only encrypted and never return the secret`() {
        val path = "/api/admin/google/client"
        assertThat(exchange(HttpMethod.GET, path, auth = false).statusCode.value()).isEqualTo(401)
        assertThat(exchange(HttpMethod.PUT, path, "{}", auth = false).statusCode.value()).isEqualTo(401)
        val parent = "oauth-parent"
        val created = exchange(HttpMethod.POST, "/api/admin/users",
            """{"name":"$parent","displayName":"Example Parent","password":"test-parent-password","role":"PARENT"}""")
        assertThat(created.statusCode.value()).isEqualTo(200)
        for (method in listOf(HttpMethod.GET, HttpMethod.PUT)) {
            val headers = HttpHeaders().apply { setBasicAuth(parent, "test-parent-password"); contentType = MediaType.APPLICATION_JSON }
            val result = try { rest.exchange(url(path), method, HttpEntity("{}", headers), String::class.java) }
                catch (e: HttpClientErrorException) { ResponseEntity(e.responseBodyAsString, e.statusCode) }
            assertThat(result.statusCode.value()).isEqualTo(403)
        }
        val saved = exchange(HttpMethod.PUT, path,
            """{"clientId":"example.apps.googleusercontent.com","clientSecret":"synthetic-oauth-secret","confirmClientChange":true}""")
        assertThat(saved.statusCode.value()).describedAs(saved.body).isEqualTo(200)
        assertThat(saved.headers.cacheControl).isEqualTo("no-store")
        assertThat(saved.body).contains("\"hasSecret\":true", "\"source\":\"settings\"").doesNotContain("synthetic-oauth-secret", "clientSecret")
        assertThat(exchange(HttpMethod.GET, path).body).isEqualTo(saved.body)
        val stored = secretRefs.findById(de.schoolagent.connectors.google.GoogleClientConfigService.CONFIG_KEY).orElseThrow()
        assertThat(stored.cipherText).doesNotContain("synthetic-oauth-secret", "example.apps.googleusercontent.com")
        val retained = exchange(HttpMethod.PUT, path, """{"clientId":"example.apps.googleusercontent.com","clientSecret":""}""")
        assertThat(retained.body).isEqualTo(saved.body)
        val conflict = exchange(HttpMethod.PUT, path, """{"clientId":"changed.apps.googleusercontent.com","clientSecret":"replacement-private-value"}""")
        assertThat(conflict.statusCode.value()).isEqualTo(409)
        assertThat(conflict.body).doesNotContain("replacement-private-value")
        assertThat(exchange(HttpMethod.GET, path).body).isEqualTo(saved.body)
        val invalid = exchange(HttpMethod.PUT, path, """{"clientId":"invalid-private-value","clientSecret":"replacement-private-value"}""")
        assertThat(invalid.statusCode.value()).isEqualTo(400)
        assertThat(invalid.body).doesNotContain("invalid-private-value", "replacement-private-value")
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
    fun `elternportal status preserves the password without exposing it`() {
        val kid = "00000000-0000-0000-0000-000000000003"
        val saved = exchange(
            HttpMethod.PUT,
            "/api/elternportal/config?kid=$kid",
            """{"url":"https://school.eltern-portal.org","username":"parent@example.com","password":"portal-secret"}""",
        )
        assertThat(saved.statusCode.value()).isEqualTo(200)
        assertThat(saved.body).doesNotContain("portal-secret").contains("parent@example.com", "\"hasPassword\":true")

        val status = exchange(HttpMethod.GET, "/api/elternportal/status?kid=$kid")
        assertThat(status.statusCode.value()).isEqualTo(200)
        assertThat(status.body).doesNotContain("portal-secret").contains("\"hasPassword\":true")
    }
    @Test
    fun `receipt and family sharing permissions belong to the connection and survive secret rotation only`() {
        val created = exchange(HttpMethod.POST, "/api/admin/kids",
            """{"name":"Permission test child","color":"#7c3aed","accountNames":["morgan"]}""")
        val kid = com.fasterxml.jackson.databind.ObjectMapper().readTree(created.body).path("id").asText()
        val path = "/api/school-connection/config?kid=$kid"
        val saved = exchange(HttpMethod.PUT, path,
            """{"provider":"ELTERNPORTAL","url":"https://school.eltern-portal.org","username":"parent@example.test","password":"synthetic-secret","allowReceiptDownloads":true,"shareConnectedMessages":true}""")
        assertThat(saved.statusCode.value()).isEqualTo(200)
        assertThat(saved.body).contains("\"allowReceiptDownloads\":true", "\"shareConnectedMessages\":true")
        val rotated = exchange(HttpMethod.PUT, path,
            """{"provider":"ELTERNPORTAL","url":"https://school.eltern-portal.org","username":"parent@example.test","password":"synthetic-rotation"}""")
        assertThat(rotated.body).contains("\"allowReceiptDownloads\":true", "\"shareConnectedMessages\":true")
        val replaced = exchange(HttpMethod.PUT, path,
            """{"provider":"ELTERNPORTAL","url":"https://school.eltern-portal.org","username":"other@example.test","password":"synthetic-replacement"}""")
        assertThat(replaced.body).contains("\"allowReceiptDownloads\":false", "\"shareConnectedMessages\":false")
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


    @Test fun `ordinary parents cannot administer the shared WhatsApp integration`() {
        for ((method, path) in listOf(HttpMethod.GET to "/status", HttpMethod.PUT to "/config", HttpMethod.POST to "/test",
            HttpMethod.POST to "/templates/sync", HttpMethod.POST to "/subscribe-waba")) {
            val headers = HttpHeaders().apply { setBasicAuth("casey", "test-only-password"); contentType = MediaType.APPLICATION_JSON }
            val response = try { rest.exchange(url("/api/whatsapp$path"), method, HttpEntity("{}", headers), String::class.java) }
                catch (e: HttpClientErrorException) { ResponseEntity(e.responseBodyAsString, e.statusCode) }
            assertThat(response.statusCode.value()).describedAs("$method $path").isEqualTo(403)
        }
    }

    @Test fun `parents cannot bypass child scoping through the legacy Google connection`() {
        for ((method, path) in listOf(HttpMethod.GET to "/status", HttpMethod.GET to "/authorize", HttpMethod.DELETE to "/connection",
            HttpMethod.GET to "/calendar-setup", HttpMethod.POST to "/calendar-setup")) {
            val headers = HttpHeaders().apply { setBasicAuth("casey", "test-only-password"); contentType=MediaType.APPLICATION_JSON }
            val response = try { rest.exchange(url("/api/google$path"), method, HttpEntity("{}", headers), String::class.java) }
                catch (e: HttpClientErrorException) { ResponseEntity(e.responseBodyAsString, e.statusCode) }
            assertThat(response.statusCode.value()).describedAs("$method $path").isEqualTo(403)
        }
    }

}
