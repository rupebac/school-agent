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

class CalendarApiException(message: String, val authRelated: Boolean) : RuntimeException(message)

@Service
class GoogleCalendarRestClient(
    private val oauth: GoogleOAuthService,
    private val rest: RestTemplate,
) : GoogleCalendarClient {

    private val tokenChildId = ThreadLocal<java.util.UUID?>()

    fun withChild(childId: java.util.UUID?) { tokenChildId.set(childId) }

    override fun insertEvent(calendarId: String, eventJson: String): String {
        val node = exchange(
            HttpMethod.POST,
            "https://www.googleapis.com/calendar/v3/calendars/$calendarId/events",
            eventJson,
        )
        return node.path("id").asText()
    }

    override fun updateEvent(calendarId: String, googleEventId: String, eventJson: String) {
        exchange(
            HttpMethod.PATCH,
            "https://www.googleapis.com/calendar/v3/calendars/$calendarId/events/$googleEventId",
            eventJson,
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
        } catch (e: org.springframework.web.client.RestClientException) {
            throw CalendarApiException("calendar unreachable: ${e.message}", authRelated = false)
        }
    }

    private fun exchange(method: HttpMethod, url: String, body: String): JsonNode {
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
        } catch (e: org.springframework.web.client.RestClientException) {
            throw CalendarApiException("calendar unreachable: ${e.message}", authRelated = false)
        }
    }

    private fun bearer(token: String): HttpHeaders =
        HttpHeaders().apply { setBearerAuth(token) }
}
