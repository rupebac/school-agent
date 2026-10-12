package de.schoolagent.connectors.calendar

import com.fasterxml.jackson.databind.JsonNode
import de.schoolagent.connectors.google.GoogleAuthException
import de.schoolagent.connectors.google.GoogleOAuthService
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

data class CalendarInfoDto(val id: String, val summary: String, val timeZone: String?)

/** Calendar management operations (list/create/share) for the setup flow. */
interface CalendarAdminService {
    fun listCalendars(): List<CalendarInfoDto>
    fun createCalendar(summary: String, timeZone: String): CalendarInfoDto
    fun shareWithUser(calendarId: String, email: String, role: String = "writer")
}

@Service
class CalendarAdminRestService(
    private val oauth: GoogleOAuthService,
    private val rest: RestTemplate,
) : CalendarAdminService {

    private val tokenChildId = ThreadLocal<java.util.UUID?>()

    fun withChild(childId: java.util.UUID?) { tokenChildId.set(childId) }

    override fun listCalendars(): List<CalendarInfoDto> {
        val items = get("https://www.googleapis.com/calendar/v3/users/me/calendarList").path("items")
        return items.map { toInfo(it) }
    }

    override fun createCalendar(summary: String, timeZone: String): CalendarInfoDto {
        val node = exchange(
            HttpMethod.POST,
            "https://www.googleapis.com/calendar/v3/calendars",
            com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(mapOf("summary" to summary, "timeZone" to timeZone)),
        )
        return toInfo(node)
    }

    override fun shareWithUser(calendarId: String, email: String, role: String) {
        val scope = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
        scope.put("type", "user")
        scope.put("value", email)
        val body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
        body.set<JsonNode>("scope", scope)
        body.put("role", role)
        exchange(
            HttpMethod.POST,
            "https://www.googleapis.com/calendar/v3/calendars/$calendarId/acl",
            body.toString(),
        )
    }

    private fun toInfo(node: JsonNode): CalendarInfoDto = CalendarInfoDto(
        id = node.path("id").asText(),
        summary = node.path("summary").asText(""),
        timeZone = node.path("timeZone").asText(null),
    )

    private fun get(url: String): JsonNode = exchange(HttpMethod.GET, url, null)

    private fun exchange(method: HttpMethod, url: String, body: String?): JsonNode {
        val token = try {
            oauth.accessToken(tokenChildId.get())
        } catch (e: GoogleAuthException) {
            throw CalendarApiException(e.message ?: "auth failure", authRelated = true)
        }
        val headers = HttpHeaders().apply {
            setBearerAuth(token)
            if (body != null) set("Content-Type", "application/json")
        }
        return try {
            com.fasterxml.jackson.databind.ObjectMapper().readTree(rest.exchange(url, method, HttpEntity(body, headers), String::class.java).body!!)
        } catch (e: org.springframework.web.client.HttpClientErrorException.Unauthorized) {
            throw CalendarApiException("calendar 401", authRelated = true)
        } catch (e: org.springframework.web.client.RestClientException) {
            throw CalendarApiException("calendar unreachable: ${e.message}", authRelated = false)
        }
    }
}
