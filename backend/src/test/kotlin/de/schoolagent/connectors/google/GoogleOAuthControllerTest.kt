package de.schoolagent.connectors.google

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GoogleOAuthControllerTest {
    private val oauth = mockk<GoogleOAuthService>()
    private val controller = GoogleOAuthController(oauth, mockk(), GoogleProperties(), mockk(), mockk())

    @Test
    fun `token exchange failures never reflect provider payloads into browser URLs`() {
        every { oauth.handleCallback(any(), any()) } throws IllegalStateException("synthetic-private-token")
        val response = controller.callback("example-code", "example-state", null)
        assertThat(response.statusCode.value()).isEqualTo(302)
        assertThat(response.headers.location.toString()).contains("googleError=")
            .doesNotContain("synthetic-private-token", "example-code", "example-state")
    }

    @Test
    fun `provider errors never reflect arbitrary query input into browser URLs`() {
        val response = controller.callback(null, "example-state", "synthetic-private-error")
        assertThat(response.headers.location.toString()).contains("googleError=").doesNotContain("synthetic-private-error")
    }
}
