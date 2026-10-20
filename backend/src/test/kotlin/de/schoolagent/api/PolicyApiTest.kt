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
class PolicyApiTest {

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

    private fun get(path: String, auth: Boolean = true): ResponseEntity<String> {
        val headers = HttpHeaders()
        if (auth) headers.setBasicAuth("morgan", "test-only-password")
        return try {
            rest.exchange(url(path), HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        } catch (e: HttpClientErrorException) {
            ResponseEntity(e.responseBodyAsString, e.statusCode)
        }
    }

    private fun exchange(method: HttpMethod, path: String, body: String? = null, username: String = "morgan"): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            setBasicAuth(username, "test-only-password")
            contentType = MediaType.APPLICATION_JSON
        }
        return try {
            rest.exchange(url(path), method, HttpEntity(body, headers), String::class.java)
        } catch (e: HttpClientErrorException) {
            ResponseEntity(e.responseBodyAsString, e.statusCode)
        }
    }

    @org.springframework.beans.factory.annotation.Autowired lateinit var settings: de.schoolagent.domain.repo.SettingRepository
    @org.springframework.beans.factory.annotation.Autowired lateinit var audit: de.schoolagent.domain.repo.AuditEntryRepository

    @Test
    fun `archive policy is persistent validated family configuration with admin writes`() {
        val path = "/api/inbox/settings"
        val key = de.schoolagent.inbox.InboxSettingsService.KEY
        settings.deleteById(key)
        try {
            assertThat(get(path, auth = false).statusCode.value()).isEqualTo(401)
            assertThat(get(path).body).contains("\"archiveAfterWeeks\":4")
            assertThat(exchange(HttpMethod.PUT, path, """{"archiveAfterWeeks":8}""").statusCode.value()).isEqualTo(200)
            assertThat(get(path).body).contains("\"archiveAfterWeeks\":8")
            assertThat(settings.findById(key).get().valueJson).isEqualTo("8")
            assertThat(audit.findAll().any { it.action == "INBOX_ARCHIVE_SETTINGS_CHANGED" && it.detail?.contains("8") == true }).isTrue()
            for (value in listOf(0, 53, -1)) {
                assertThat(exchange(HttpMethod.PUT, path, """{"archiveAfterWeeks":$value}""").statusCode.value()).isEqualTo(400)
            }
            assertThat(exchange(HttpMethod.PUT, path, """{"archiveAfterWeeks":2}""", "casey").statusCode.value()).isEqualTo(403)
            assertThat(get(path).body).contains("\"archiveAfterWeeks\":8")
            assertThat(exchange(HttpMethod.GET, path, username = "casey").statusCode.value()).isEqualTo(200)
        } finally { settings.deleteById(key) }
    }

    @Test
    fun `models require auth and list seeded roles`() {
        assertThat(get("/api/models", auth = false).statusCode.value()).isEqualTo(401)
        val body = get("/api/models").body!!
        assertThat(get("/api/models").statusCode.value()).isEqualTo(200)
        assertThat(body).contains("TEXT", "VISION")
        assertThat(body).doesNotContain("EXTRACTION", "FALLBACK")
    }

    @Test
    fun `model update changes the name and can disable a role`() {
        val updated = exchange(HttpMethod.PUT, "/api/models/TEXT", """{"modelName":"glm-test","enabled":true}""")
        assertThat(updated.statusCode.value()).isEqualTo(200)
        assertThat(updated.body).contains("glm-test")
        val disabled = exchange(HttpMethod.PUT, "/api/models/VISION", """{"enabled":false}""")
        assertThat(disabled.statusCode.value()).isEqualTo(200)
        assertThat(disabled.body).contains("\"enabled\":false")
    }

    @Test
    fun `family rules can be created listed toggled and deleted`() {
        val created = exchange(HttpMethod.POST, "/api/family-rules", """{"ruleKey":"ignore-sports-day","text":"Sports day recaps are noise"}""")
        assertThat(created.statusCode.value()).isEqualTo(200)
        assertThat(created.body).contains("ignore-sports-day", "Sports day recaps are noise")

        val list = get("/api/family-rules").body!!
        assertThat(list).contains("ignore-sports-day")

        val id = Regex("\"id\":\"([^\"]+)\"").find(created.body!!)!!.groupValues[1]
        val toggled = exchange(HttpMethod.PUT, "/api/family-rules/$id", """{"enabled":false}""")
        assertThat(toggled.statusCode.value()).isEqualTo(200)
        assertThat(toggled.body).contains("\"enabled\":false")

        val deleted = exchange(HttpMethod.DELETE, "/api/family-rules/$id")
        assertThat(deleted.statusCode.value()).isEqualTo(200)
        assertThat(get("/api/family-rules").body).doesNotContain("ignore-sports-day")
    }

    @Test fun `rule text is preserved as text and safely round trips punctuation`() {
        for (text in listOf("Bring \"signed\" form\nThen pay", "[Monday]", "{not JSON}")) {
            assertThat(FamilyRulesController.displayText(FamilyRulesController.asJsonText(text))).isEqualTo(text)
        }
    }

}
