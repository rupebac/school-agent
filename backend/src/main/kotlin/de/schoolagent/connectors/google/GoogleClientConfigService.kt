package de.schoolagent.connectors.google

import com.fasterxml.jackson.databind.ObjectMapper
import de.schoolagent.security.SecretVault
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.util.UUID

/** One encrypted, atomic configuration document; server configuration remains the fallback. */
@Service
class GoogleClientConfigService(private val props: GoogleProperties, private val vault: SecretVault) {
    // Shared with OAuth exchanges so credentials cannot change midway through an exchange.
    private val lock = Any()
    private val mapper = ObjectMapper()

    class Client(val id: String, val secret: String, val namespace: String, val redirectUri: String) {
        val configured get() = id.isNotBlank() && secret.isNotBlank()
        val fingerprint: String get() = MessageDigest.getInstance("SHA-256")
            .digest(listOf(id, secret, namespace, redirectUri).joinToString("\u0000").toByteArray())
            .joinToString("") { "%02x".format(it) }
        fun refreshKey(childId: UUID?) = if (namespace.isEmpty()) GoogleOAuthService.refreshKey(childId)
            else "google.connection.$namespace.${childId ?: "legacy"}.refresh-token"
    }

    data class Status(val clientId: String, val hasSecret: Boolean, val configured: Boolean,
                      val redirectUri: String, val source: String)
    // A regular class avoids generated toString accidentally exposing credentials in logs.
    class Update(val clientId: String? = null, val clientSecret: String? = null, val confirmClientChange: Boolean? = null)

    fun <T> withClient(action: (Client) -> T): T = synchronized(lock) { action(read()) }

    fun status(): Status = synchronized(lock) { status(read()) }

    fun save(update: Update): Status = synchronized(lock) {
        val old = read()
        val id = update.clientId?.trim().orEmpty()
        if (!id.matches(Regex("[A-Za-z0-9._-]+\\.apps\\.googleusercontent\\.com")) || id.length > 256)
            badRequest("Enter the Google OAuth client ID ending in .apps.googleusercontent.com")
        val changed = id != old.id
        if (changed && old.configured && update.confirmClientChange != true)
            throw ResponseStatusException(HttpStatus.CONFLICT, "Confirm that changing the client ID requires reconnecting every Google account")
        val supplied = update.clientSecret?.trim().orEmpty()
        if (supplied.length > 4096 || supplied.any { it.isWhitespace() || it.isISOControl() })
            badRequest("Enter a valid Google OAuth client secret")
        val secret = supplied.ifBlank { if (!changed) old.secret else "" }
        if (secret.isBlank()) badRequest("A client secret is required for this client ID")
        val namespace = if (changed) UUID.randomUUID().toString() else old.namespace
        val document = mapper.createObjectNode().put("clientId", id).put("clientSecret", secret).put("namespace", namespace)
        vault.store(CONFIG_KEY, document.toString())
        status(Client(id, secret, namespace, props.redirectUri))
    }

    private fun read(): Client {
        val stored = vault.read(CONFIG_KEY) ?: return Client(props.clientId, props.clientSecret, "", props.redirectUri)
        val doc = mapper.readTree(stored)
        return Client(doc.path("clientId").asText(), doc.path("clientSecret").asText(), doc.path("namespace").asText(), props.redirectUri)
    }

    private fun status(client: Client) = Status(client.id, client.secret.isNotBlank(), client.configured,
        client.redirectUri, if (vault.exists(CONFIG_KEY)) "settings" else if (client.id.isNotBlank() || client.secret.isNotBlank()) "environment" else "none")

    private fun badRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    companion object { const val CONFIG_KEY = "google.oauth-client" }
}
