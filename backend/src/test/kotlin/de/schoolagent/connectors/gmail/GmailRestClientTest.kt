package de.schoolagent.connectors.gmail

import de.schoolagent.connectors.google.GoogleOAuthService
import de.schoolagent.connectors.google.GoogleProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestTemplate
import java.util.UUID

class GmailRestClientTest {
    @Test
    fun `follows next page using child bearer and configured query with Boot 4 HTTP decoding`() {
        val rest = RestTemplate()
        val server = MockRestServiceServer.bindTo(rest).build()
        val oauth = mockk<GoogleOAuthService>()
        val kid = UUID.randomUUID()
        every { oauth.accessToken(kid) } returns "test-token"
        server.expect { request ->
            assertThat(request.uri.path).endsWith("/messages")
            assertThat(request.uri.query).contains("q=in:inbox")
            assertThat(request.headers.getFirst("Authorization")).isEqualTo("Bearer test-token")
        }.andRespond(withSuccess("""{"messages":[{"id":"first"}],"nextPageToken":"page2"}""", MediaType.APPLICATION_JSON))
        server.expect { request -> assertThat(request.uri.query).contains("pageToken=page2") }
            .andRespond(withSuccess("""{"messages":[{"id":"second"}]}""", MediaType.APPLICATION_JSON))
        val client = GmailRestClient(oauth, rest, GoogleProperties())
        client.withChild(kid)
        assertThat(client.listMessageIds(25)).containsExactly("first", "second")
        verify(exactly = 2) { oauth.accessToken(kid) }
        server.verify()
    }
}
