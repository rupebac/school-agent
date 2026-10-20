package de.schoolagent.connectors.google

import com.fasterxml.jackson.databind.JsonNode
import de.schoolagent.security.SecretVault
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** HTTP layer of the token endpoints; faked in tests. */
interface TokenHttpClient {
    fun postToken(form: Map<String, String>): JsonNode
}

@Service
class RestTokenHttpClient(private val rest: RestTemplate) : TokenHttpClient {
    override fun postToken(form: Map<String, String>): JsonNode {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }
        val body: MultiValueMap<String, String> = LinkedMultiValueMap()
        form.forEach { (k, v) -> body.add(k, v) }
        return try {
            com.fasterxml.jackson.databind.ObjectMapper().readTree(rest.postForObject("https://oauth2.googleapis.com/token", HttpEntity(body, headers), String::class.java)!!)
        } catch (e: org.springframework.web.client.RestClientException) {
            throw GoogleAuthException("Google token request failed (${e.javaClass.simpleName})")
        }
    }
}

class GoogleAuthException(message: String) : RuntimeException(message)

/**
 * Google OAuth for each child's account (spec §4/§5): consent screen → callback →
 * refresh token into the SecretVault; access tokens cached in memory only.
 * The callback is protected by a single-use `state` bound to the user who
 * started the flow (CSRF).
 */
@Service
class GoogleOAuthService(
    private val props: GoogleProperties,
    private val vault: SecretVault,
    private val clock: Clock,
    private val tokenClient: TokenHttpClient,
    private val config: GoogleClientConfigService = GoogleClientConfigService(props, vault),
    private val settings: de.schoolagent.domain.repo.SettingRepository? = null,
) {
    private val random = SecureRandom()
    private val pendingStates = ConcurrentHashMap<String, StateEntry>()

    data class StateEntry(val user: String, val childId: java.util.UUID?, val expiresAt: Instant, val fingerprint: String)

    private data class CachedToken(val token: String, val expires: Instant, val fingerprint: String)
    private val tokens = ConcurrentHashMap<String, CachedToken>()

    fun clientConfigured(): Boolean = config.withClient { it.configured }

    fun hasRefreshToken(childId: java.util.UUID? = null): Boolean =
        config.withClient { it.configured && vault.exists(it.refreshKey(childId)) }

    fun isConfigured(childId: java.util.UUID? = null): Boolean = hasRefreshToken(childId)

    /** Build the consent URL; the state is remembered for [stateTtl] and bound to [user]. */
    fun authorizationUrl(user: String, childId: java.util.UUID? = null): String = config.withClient { client ->
        require(client.configured) {
            "Google OAuth client not configured — ask an administrator to open Family settings"
        }
        val state = newState()
        pendingStates.entries.removeIf { !clock.instant().isBefore(it.value.expiresAt) }
        pendingStates[state] = StateEntry(user, childId, clock.instant().plusSeconds(STATE_TTL_SECONDS), client.fingerprint)
        UriComponentsBuilder.fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
            .queryParam("client_id", client.id)
            .queryParam("redirect_uri", client.redirectUri)
            .queryParam("response_type", "code")
            .queryParam("scope", props.scopes.joinToString(" "))
            .queryParam("access_type", "offline")
            .queryParam("prompt", "consent")
            .queryParam("state", state)
            .build().toUriString()
    }

    data class CallbackResult(val user: String, val childId: java.util.UUID?)

    /** Exchanges the consent code for tokens; single-use state required. Returns the bound user + kid. */
    fun handleCallback(code: String, state: String): CallbackResult = config.withClient { client ->
        val entry = pendingStates.remove(state)
            ?: throw GoogleAuthException("unknown or already-used state — start the flow again")
        require(clock.instant().isBefore(entry.expiresAt)) { "state expired — start the flow again" }
        if (entry.fingerprint != client.fingerprint) throw GoogleAuthException("Google setup changed — start the flow again")

        val response = tokenClient.postToken(
            mapOf(
                "code" to code,
                "client_id" to client.id,
                "client_secret" to client.secret,
                "redirect_uri" to client.redirectUri,
                "grant_type" to "authorization_code",
            ),
        )
        val refresh = response.path("refresh_token")?.asText(null)
        val access = response.path("access_token")?.asText(null)
            ?: throw GoogleAuthException("token response without access_token")
        val expiresIn = response.path("expires_in").asLong(3600)

        // Pause projections before replacing credentials; setup will validate the destination.
        settings?.save(de.schoolagent.domain.model.Setting(key = GoogleCalendarSetupService.readyKey(entry.childId), valueJson = "false", updatedAt = clock.instant()))
        vault.store(client.refreshKey(entry.childId), refresh ?: error("no refresh_token in response — re-consent with prompt=consent"))
        cacheAccess(access, expiresIn, entry.childId, client)
        CallbackResult(entry.user, entry.childId)
    }

    /** Valid Bearer token; refreshes through the stored per-kid refresh token when expired. */
    fun accessToken(childId: java.util.UUID? = null): String = config.withClient { client ->
        if (!client.configured) throw GoogleAuthException("Google OAuth client not configured")
        tokens[client.refreshKey(childId)]?.let { cached ->
            if (cached.fingerprint == client.fingerprint && clock.instant().isBefore(cached.expires.minusSeconds(60))) return@withClient cached.token
        }
        val refresh = vault.read(client.refreshKey(childId))
            ?: throw GoogleAuthException("no Google refresh token stored for this kid — run the OAuth flow first")
        val response = tokenClient.postToken(
            mapOf(
                "refresh_token" to refresh,
                "client_id" to client.id,
                "client_secret" to client.secret,
                "grant_type" to "refresh_token",
            ),
        )
        val access = response.path("access_token")?.asText(null)
            ?: throw GoogleAuthException("refresh failed — no access_token in response")
        cacheAccess(access, response.path("expires_in").asLong(3600), childId, client)
        access
    }

    fun revoke(childId: java.util.UUID? = null): Boolean = config.withClient { client ->
        val had = vault.exists(client.refreshKey(childId))
        vault.delete(client.refreshKey(childId))
        tokens.remove(client.refreshKey(childId))
        had
    }

    private fun cacheAccess(token: String, expiresInSeconds: Long, childId: java.util.UUID?, client: GoogleClientConfigService.Client) {
        tokens[client.refreshKey(childId)] = CachedToken(token, clock.instant().plusSeconds(expiresInSeconds), client.fingerprint)
    }

    private fun newState(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val LEGACY_REFRESH_KEY = "google.refresh-token"
        fun refreshKey(childId: java.util.UUID?) = childId?.let { "google.$it.refresh-token" } ?: LEGACY_REFRESH_KEY
        private const val STATE_TTL_SECONDS = 10 * 60L
    }
}
