package de.schoolagent.connectors.calendar

import de.schoolagent.connectors.google.GoogleOAuthService
import de.schoolagent.connectors.google.GoogleAuthException
import de.schoolagent.connectors.google.RestTokenHttpClient
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestTemplate

class GoogleCalendarRestClientTest {
    @Test fun `a retried accepted insert reconciles its marker and updates the existing event`() {
        val rest = RestTemplate(); val server = MockRestServiceServer.createServer(rest)
        val oauth = mockk<GoogleOAuthService>()
        every { oauth.accessToken(any()) } returns "synthetic-token"
        val base = "https://www.googleapis.com/calendar/v3/calendars/example/events"
        val payload = """{"id":"sa0123456789","summary":"Exam","extendedProperties":{"private":{"schoolAgentEvent":"synthetic-event"}}}"""
        server.expect(requestTo(base)).andExpect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.CONFLICT))
        server.expect(requestTo("$base/sa0123456789")).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(payload, MediaType.APPLICATION_JSON))
        server.expect(requestTo("$base/sa0123456789")).andExpect(method(HttpMethod.PATCH))
            .andRespond(withSuccess(payload, MediaType.APPLICATION_JSON))
        org.assertj.core.api.Assertions.assertThat(GoogleCalendarRestClient(oauth, rest).insertEvent("example", payload)).isEqualTo("sa0123456789")
        server.verify()
    }

    @Test fun `a conflicting event owned by another source is never overwritten`() {
        val rest = RestTemplate(); val server = MockRestServiceServer.createServer(rest)
        val oauth = mockk<GoogleOAuthService>()
        every { oauth.accessToken(any()) } returns "synthetic-token"
        val base = "https://www.googleapis.com/calendar/v3/calendars/example/events"
        server.expect(requestTo(base)).andRespond(withStatus(HttpStatus.CONFLICT))
        server.expect(requestTo("$base/sa0123456789")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))
        assertThatThrownBy { GoogleCalendarRestClient(oauth, rest).insertEvent("example",
            """{"id":"sa0123456789","extendedProperties":{"private":{"schoolAgentEvent":"synthetic-event"}}}""") }
            .isInstanceOf(CalendarApiException::class.java).hasMessageContaining("requires review")
        server.verify()
    }

    @Test fun `already absent calendar events are successful deletions`() {
        for (status in listOf(HttpStatus.NOT_FOUND, HttpStatus.GONE)) {
            val rest = RestTemplate(); val server = MockRestServiceServer.createServer(rest)
            val oauth = mockk<GoogleOAuthService>()
            every { oauth.accessToken(any()) } returns "synthetic-token"
            server.expect(requestTo("https://www.googleapis.com/calendar/v3/calendars/example/events/event"))
                .andExpect(method(HttpMethod.DELETE)).andRespond(withStatus(status))
            GoogleCalendarRestClient(oauth, rest).deleteEvent("example", "event")
            server.verify()
        }
    }

    @Test fun `token endpoint errors become safe typed failures that projections can retry`() {
        val rest = RestTemplate(); val server = MockRestServiceServer.createServer(rest)
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("""{"error":"invalid_grant","detail":"synthetic-private-provider-response"}"""))
        assertThatThrownBy { RestTokenHttpClient(rest).postToken(mapOf("grant_type" to "refresh_token")) }
            .isInstanceOf(GoogleAuthException::class.java).hasMessageNotContaining("synthetic-private-provider-response")
        server.verify()
    }
}
