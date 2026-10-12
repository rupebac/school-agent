package de.schoolagent.connectors.google

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.schoolagent.security.SecretVault
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class GoogleOAuthServiceTest {

    private val props = GoogleProperties().apply {
        clientId = "test-client"
        clientSecret = "test-secret"
    }
    private val clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC)
    private val mapper = ObjectMapper()

    private class FakeVault : SecretVault {
        val map = mutableMapOf<String, String>()
        override fun store(key: String, value: String) { map[key] = value }
        override fun read(key: String): String? = map[key]
        override fun exists(key: String): Boolean = map.containsKey(key)
        override fun delete(key: String) { map.remove(key) }
    }

    private class FakeTokenClient : TokenHttpClient {
        private val mapper = ObjectMapper()
        val calls = mutableListOf<Map<String, String>>()
        var nextResponse: String =
            """{"access_token":"acc-1","expires_in":3600,"refresh_token":"ref-1"}"""
        override fun postToken(form: Map<String, String>): JsonNode = mapper.readTree(nextResponse).also { calls.add(form) }
    }

    private lateinit var vault: FakeVault
    private lateinit var tokens: FakeTokenClient
    private lateinit var oauth: GoogleOAuthService

    @BeforeEach
    fun setUp() {
        vault = FakeVault()
        tokens = FakeTokenClient()
        oauth = GoogleOAuthService(props, vault, clock, tokens)
    }

    @Test
    fun `authorization url carries consent params and a state`() {
        val url = oauth.authorizationUrl("morgan")

        assertThat(url).contains("client_id=test-client")
        assertThat(url).contains("access_type=offline")
        assertThat(url).contains("prompt=consent")
        assertThat(url).contains("gmail.readonly")
        assertThat(url).contains("state=")
        assertThat(oauth.isConfigured()).isFalse() // not connected yet
    }

    @Test
    fun `callback exchanges code, stores refresh token and reports the bound user`() {
        val url = oauth.authorizationUrl("casey")
        val state = url.substringAfter("state=").substringBefore('&')

        val user = oauth.handleCallback("auth-code", state)

        assertThat(user.user).isEqualTo("casey")
        assertThat(vault.map[GoogleOAuthService.LEGACY_REFRESH_KEY]).isEqualTo("ref-1")
        assertThat(tokens.calls.single()["grant_type"]).isEqualTo("authorization_code")
        assertThat(oauth.isConfigured()).isTrue()
    }

    @Test
    fun `state is single-use — replay is rejected`() {
        val url = oauth.authorizationUrl("morgan")
        val state = url.substringAfter("state=").substringBefore('&')
        oauth.handleCallback("code-1", state)

        assertThatThrownBy { oauth.handleCallback("code-2", state) }
            .isInstanceOf(GoogleAuthException::class.java)
    }

    @Test
    fun `unknown state is rejected`() {
        assertThatThrownBy { oauth.handleCallback("code", "forged-state") }
            .isInstanceOf(GoogleAuthException::class.java)
    }

    @Test
    fun `accessToken refreshes via stored refresh token when cache is empty`() {
        vault.map[GoogleOAuthService.LEGACY_REFRESH_KEY] = "stored-refresh"

        val token = oauth.accessToken()

        assertThat(token).isEqualTo("acc-1")
        assertThat(tokens.calls.single()["refresh_token"]).isEqualTo("stored-refresh")
        assertThat(tokens.calls.single()["grant_type"]).isEqualTo("refresh_token")
    }

    @Test
    fun `revoke clears connection`() {
        vault.map[GoogleOAuthService.LEGACY_REFRESH_KEY] = "x"
        assertThat(oauth.revoke()).isTrue()
        assertThat(oauth.hasRefreshToken()).isFalse()
    }
}
