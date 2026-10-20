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
    private lateinit var config: GoogleClientConfigService

    @BeforeEach
    fun setUp() {
        vault = FakeVault()
        tokens = FakeTokenClient()
        config = GoogleClientConfigService(props, vault)
        oauth = GoogleOAuthService(props, vault, clock, tokens, config)
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
    @Test
    fun `environment fallback and saved status never serialize the secret`() {
        assertThat(config.status().source).isEqualTo("environment")
        assertThat(config.status().clientId).isEqualTo(props.clientId)
        assertThat(mapper.writeValueAsString(config.status())).doesNotContain(props.clientSecret)
        val saved = config.save(GoogleClientConfigService.Update("example.apps.googleusercontent.com", "replacement-secret", true))
        assertThat(saved.source).isEqualTo("settings")
        assertThat(mapper.writeValueAsString(saved)).doesNotContain("replacement-secret")
        assertThat(oauth.authorizationUrl("morgan")).contains("client_id=example.apps.googleusercontent.com")
        val restarted = GoogleClientConfigService(props, vault)
        assertThat(restarted.status()).isEqualTo(saved)
    }

    @Test
    fun `changing client needs confirmation and its own secret without modifying existing configuration`() {
        val before = config.status()
        assertThatThrownBy { config.save(GoogleClientConfigService.Update("example.apps.googleusercontent.com", "new-secret")) }
            .isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThatThrownBy { config.save(GoogleClientConfigService.Update("example.apps.googleusercontent.com", null, true)) }
            .isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThat(config.status()).isEqualTo(before)
    }

    @Test
    fun `blank secret preserves current client connection and secret rotation clears cached access`() {
        props.clientId = "example.apps.googleusercontent.com"
        vault.store(GoogleOAuthService.LEGACY_REFRESH_KEY, "existing-refresh")
        assertThat(oauth.accessToken()).isEqualTo("acc-1")
        config.save(GoogleClientConfigService.Update(props.clientId))
        assertThat(oauth.hasRefreshToken()).isTrue()
        assertThat(oauth.accessToken()).isEqualTo("acc-1")
        assertThat(tokens.calls).hasSize(1)
        config.save(GoogleClientConfigService.Update(props.clientId, "rotated-secret"))
        oauth.accessToken()
        assertThat(tokens.calls).hasSize(2)
        assertThat(tokens.calls.last()["client_secret"]).isEqualTo("rotated-secret")
        assertThat(tokens.calls.last()["refresh_token"]).isEqualTo("existing-refresh")
    }

    @Test
    fun `changing client invalidates cached and stored connections for every child including legacy`() {
        val kid = java.util.UUID.randomUUID()
        vault.store(GoogleOAuthService.LEGACY_REFRESH_KEY, "legacy-refresh")
        vault.store(GoogleOAuthService.refreshKey(kid), "child-refresh")
        oauth.accessToken(kid)
        config.save(GoogleClientConfigService.Update("new.apps.googleusercontent.com", "new-secret", true))
        assertThat(oauth.hasRefreshToken()).isFalse()
        assertThat(oauth.hasRefreshToken(kid)).isFalse()
        assertThatThrownBy { oauth.accessToken(kid) }.isInstanceOf(GoogleAuthException::class.java)
        assertThat(tokens.calls).hasSize(1)
        val state = oauth.authorizationUrl("morgan", kid).substringAfter("state=").substringBefore('&')
        oauth.handleCallback("new-code", state)
        assertThat(oauth.hasRefreshToken(kid)).isTrue()
        assertThat(oauth.hasRefreshToken()).isFalse()
        assertThat(tokens.calls.last()["client_id"]).isEqualTo("new.apps.googleusercontent.com")
        val restarted = GoogleOAuthService(props, vault, clock, tokens, GoogleClientConfigService(props, vault))
        assertThat(restarted.hasRefreshToken(kid)).isTrue()
        assertThat(restarted.revoke(kid)).isTrue()
        assertThat(restarted.hasRefreshToken(kid)).isFalse()
    }

    @Test
    fun `pending consent cannot finish after client or secret changes`() {
        props.clientId = "example.apps.googleusercontent.com"
        val state = oauth.authorizationUrl("morgan").substringAfter("state=").substringBefore('&')
        config.save(GoogleClientConfigService.Update(props.clientId, "rotated-secret"))
        assertThatThrownBy { oauth.handleCallback("code", state) }.isInstanceOf(GoogleAuthException::class.java)
        assertThat(tokens.calls).isEmpty()
        assertThat(oauth.hasRefreshToken()).isFalse()
    }

    @Test
    fun `fresh setup validates input and does not reflect rejected values`() {
        props.clientId = ""; props.clientSecret = ""
        assertThat(config.status().source).isEqualTo("none")
        assertThat(oauth.clientConfigured()).isFalse()
        assertThatThrownBy { config.save(GoogleClientConfigService.Update("invalid-private-value", "secret")) }
            .hasMessageNotContaining("invalid-private-value").hasMessageNotContaining("secret")
        assertThatThrownBy { config.save(GoogleClientConfigService.Update("example.apps.googleusercontent.com", "bad secret")) }
            .hasMessageNotContaining("bad secret")
        config.save(GoogleClientConfigService.Update("example.apps.googleusercontent.com", "new-secret"))
        assertThat(oauth.clientConfigured()).isTrue()
        assertThat(oauth.hasRefreshToken()).isFalse()
    }

    @Test
    fun `switching back to a previous client cannot revive its abandoned tokens`() {
        props.clientId = "original.apps.googleusercontent.com"
        vault.store(GoogleOAuthService.LEGACY_REFRESH_KEY, "old-refresh")
        config.save(GoogleClientConfigService.Update("replacement.apps.googleusercontent.com", "new-secret", true))
        config.save(GoogleClientConfigService.Update(props.clientId, props.clientSecret, true))
        assertThat(oauth.hasRefreshToken()).isFalse()
        assertThatThrownBy { oauth.accessToken() }.isInstanceOf(GoogleAuthException::class.java)
        assertThat(tokens.calls).isEmpty()
    }

}
