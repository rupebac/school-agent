package de.schoolagent.connectors.calendar

import com.fasterxml.jackson.databind.JsonNode
import de.schoolagent.connectors.google.GoogleAuthException
import de.schoolagent.connectors.google.GoogleOAuthService
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

/** Calendar projection port — Google Calendar is a projection, never the truth (spec §4). */
interface GoogleCalendarClient {
    fun insertEvent(calendarId: String, eventJson: String): String
    fun updateEvent(calendarId: String, googleEventId: String, eventJson: String)
    fun deleteEvent(calendarId: String, googleEventId: String)
}

class CalendarApiException(message: String, val authRelated: Boolean, val statusCode: Int? = null) : RuntimeException(message)

@Service
class GoogleCalendarRestClient(
    private val oauth: GoogleOAuthService,
    private val rest: RestTemplate,
) : GoogleCalendarClient {

    private val tokenChildId = ThreadLocal<java.util.UUID?>()

    fun withChild(childId: java.util.UUID?) { tokenChildId.set(childId) }

    override fun insertEvent(calendarId: String, eventJson: String): String {
        val json = com.fasterxml.jackson.databind.ObjectMapper()
        val payload = json.readTree(eventJson)
        val expectedId = payload.path("id").asText()
        require(expectedId.matches(Regex("[a-v0-9]{5,1024}"))) { "A stable calendar event ID is required" }
        try {
            val node = exchange(HttpMethod.POST, "https://www.googleapis.com/calendar/v3/calendars/$calendarId/events", eventJson)
            if (node.path("id").asText() != expectedId) throw CalendarApiException("Calendar returned an unexpected event ID", false)
        } catch (e: CalendarApiException) {
            if (e.statusCode != 409) throw e
            val existing = exchange(HttpMethod.GET, "https://www.googleapis.com/calendar/v3/calendars/$calendarId/events/$expectedId", null)
            val marker = payload.path("extendedProperties").path("private").path("schoolAgentEvent").asText()
            if (marker.isBlank() || existing.path("extendedProperties").path("private").path("schoolAgentEvent").asText() != marker)
                throw CalendarApiException("Calendar event ID conflict requires review", false)
            updateEvent(calendarId, expectedId, eventJson)
        }
        return expectedId
    }

    override fun updateEvent(calendarId: String, googleEventId: String, eventJson: String) {
        exchange(
            HttpMethod.PATCH,
            "https://www.googleapis.com/calendar/v3/calendars/$calendarId/events/$googleEventId",
            com.fasterxml.jackson.databind.ObjectMapper().readTree(eventJson).also { (it as com.fasterxml.jackson.databind.node.ObjectNode).remove("id") }.toString(),
        )
    }

    override fun deleteEvent(calendarId: String, googleEventId: String) {
        val token = try {
            oauth.accessToken(tokenChildId.get())
        } catch (e: GoogleAuthException) {
            throw CalendarApiException(e.message ?: "auth failure", authRelated = true)
        }
        try {
            rest.exchange(
                "https://www.googleapis.com/calendar/v3/calendars/$calendarId/events/$googleEventId",
                HttpMethod.DELETE,
                HttpEntity<Void>(bearer(token)),
                String::class.java,
            )
        } catch (e: org.springframework.web.client.HttpClientErrorException.Unauthorized) {
            throw CalendarApiException("calendar 401", authRelated = true)
        } catch (e: org.springframework.web.client.HttpStatusCodeException) {
            if (e.statusCode.value() in setOf(404, 410)) return // already absent: deletion is complete
            throw CalendarApiException("Google Calendar HTTP ${e.statusCode.value()}", authRelated = e.statusCode.value() == 403, statusCode = e.statusCode.value())
        } catch (e: org.springframework.web.client.RestClientException) {
            throw CalendarApiException("Google Calendar request failed (${e.javaClass.simpleName})", authRelated = false)
        }
    }

    private fun exchange(method: HttpMethod, url: String, body: String?): JsonNode {
        val token = try {
            oauth.accessToken(tokenChildId.get())
        } catch (e: GoogleAuthException) {
            throw CalendarApiException(e.message ?: "auth failure", authRelated = true)
        }
        val headers = bearer(token).apply { set("Content-Type", "application/json") }
        return try {
            com.fasterxml.jackson.databind.ObjectMapper().readTree(rest.exchange(url, method, HttpEntity(body, headers), String::class.java).body!!)
        } catch (e: org.springframework.web.client.HttpClientErrorException.Unauthorized) {
            throw CalendarApiException("calendar 401", authRelated = true)
        } catch (e: org.springframework.web.client.HttpStatusCodeException) {
            throw CalendarApiException("Google Calendar HTTP ${e.statusCode.value()}", authRelated = e.statusCode.value() == 403, statusCode = e.statusCode.value())
        } catch (e: org.springframework.web.client.RestClientException) {
            throw CalendarApiException("Google Calendar request failed (${e.javaClass.simpleName})", authRelated = false)
        }
    }

    private fun bearer(token: String): HttpHeaders =
        HttpHeaders().apply { setBearerAuth(token) }
}
