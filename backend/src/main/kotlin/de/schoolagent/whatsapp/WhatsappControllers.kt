package de.schoolagent.whatsapp

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Settings endpoints for the generic WhatsApp provider configuration. */
@RestController
@RequestMapping("/api/whatsapp")
class WhatsappAdminController(
    private val config: WhatsAppConfigService,
    private val client: GraphWhatsappClient,
    private val health: de.schoolagent.health.HealthTracker,
    private val templateSync: WhatsAppTemplateSync,
    private val outbox: WhatsappOutbox,
    private val notifications: de.schoolagent.domain.repo.NotificationRepository,
    private val cases: de.schoolagent.domain.repo.SchoolCaseRepository,
) {

    data class StatusDto(
        val configured: Boolean,
        val apiUrl: String,
        val phoneNumberId: String?,
        val wabaId: String?,
        val token: String?,
        val verifyToken: String?,
        val appSecret: String?,
        val hasToken: Boolean,
        val hasVerifyToken: Boolean,
        val hasAppSecret: Boolean,
        val frontendUrl: String,
        val webhookUrl: String,
        val lastWebhookAt: String?,
        val recipients: List<RecipientDto>,
        val templates: List<TemplateStatusDto> = emptyList(),
        val deliveries: List<DeliveryDto> = emptyList(),
    )

    data class DeliveryDto(val recipient: String, val status: String, val createdAt: String,
                           val message: String, val error: String?, val nextAttemptAt: String?, val tracked: Boolean)

    data class RecipientDto(val name: String, val displayName: String, val number: String?)

    data class TestRequest(val to: String? = null)

    data class SaveRequest(
        val apiUrl: String? = null,
        val phoneNumberId: String? = null,
        val wabaId: String? = null,
        val token: String? = null,
        val verifyToken: String? = null,
        val appSecret: String? = null,
        val frontendUrl: String? = null,
        val parentNumbers: Map<String, String>? = null,
    )

    @GetMapping("/status")
    fun status(@RequestParam(required = false) kid: java.util.UUID?): StatusDto {
        val c = config.config(kid)
        val accounts = config.accounts()
        val viewer = accounts.firstOrNull { it.name == org.springframework.security.core.context.SecurityContextHolder.getContext().authentication?.name }
        val recent = notifications.findTop20ByChannelOrderByCreatedAtDesc(de.schoolagent.domain.enums.NotificationChannel.WHATSAPP)
            .filter { row ->
                if (viewer?.role == "ADMIN") true
                else if (viewer == null || row.recipientId != viewer.id) false
                else if (row.caseId != null) {
                    cases.findById(row.caseId!!).orElse(null)?.let { schoolCase ->
                        schoolCase.childId == null || viewer.kids.any { it.id == schoolCase.childId }
                    } ?: false
                } else if (row.templatePayload != null) {
                    runCatching { outbox.decode(row.templatePayload!!).childId }.getOrNull()?.let { child ->
                        viewer.kids.any { it.id.toString() == child }
                    } ?: row.dedupKey.startsWith("test:")
                } else row.dedupKey.startsWith("test:")
            }
        return StatusDto(
            configured = config.isConfigured(kid),
            apiUrl = c.apiUrl,
            phoneNumberId = c.phoneNumberId,
            wabaId = c.wabaId,
            token = c.token,
            verifyToken = c.verifyToken,
            appSecret = c.appSecret,
            hasToken = !c.token.isNullOrBlank(),
            hasVerifyToken = !c.verifyToken.isNullOrBlank(),
            hasAppSecret = !c.appSecret.isNullOrBlank(),
            frontendUrl = c.frontendUrl,
            webhookUrl = config.webhookUrl(),
            lastWebhookAt = config.lastWebhookAt(),
            recipients = config.accounts().map { RecipientDto(it.name, it.displayName, it.whatsappNumber) },
            templates = templateSync.list(),
            deliveries = recent.map { row ->
                val recipient = accounts.firstOrNull { it.id == row.recipientId }?.displayName ?: "Removed account"
                DeliveryDto(recipient, row.status.name, row.createdAt.toString(), row.message, row.lastError,
                    row.nextAttemptAt?.toString(), row.messageId != null)
            },
        )
    }

    @PutMapping("/config")
    fun save(
        @RequestParam(required = false) kid: java.util.UUID?,
        @RequestBody request: SaveRequest,
    ): StatusDto {
        config.save(
            childId = kid,
            apiUrl = request.apiUrl,
            phoneNumberId = request.phoneNumberId,
            wabaId = request.wabaId,
            token = request.token,
            verifyToken = request.verifyToken,
            appSecret = request.appSecret,
            frontendUrl = request.frontendUrl,
            parentNumbers = request.parentNumbers,
        )
        return status(kid)
    }

    /** Sends a test template to one account chosen in Settings. */
    @PostMapping("/test")
    fun test(
        @RequestParam(required = false) kid: java.util.UUID?,
        @RequestBody(required = false) request: TestRequest?,
    ): ResponseEntity<Map<String, Any>> {
        if (!config.isConfigured(kid)) {
            return ResponseEntity.ok(mapOf("ok" to false, "error" to "phone-number-id or token missing"))
        }
        val to = request?.to?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: return ResponseEntity.ok(mapOf("ok" to false, "error" to "select an account"))
        val account = config.accounts().firstOrNull { it.name == to }
            ?: return ResponseEntity.ok(mapOf("ok" to false, "error" to "unknown account"))
        val number = account.whatsappNumber?.trim()?.takeIf { it.isNotBlank() }
            ?: return ResponseEntity.ok(mapOf("ok" to false, "error" to "${account.displayName} has no WhatsApp number — set it under Accounts"))
        return try {
            outbox.sendTest(account)
            ResponseEntity.ok(mapOf("ok" to true, "sent" to 1, "to" to account.displayName))
        } catch (e: Exception) {
            health.failure(de.schoolagent.domain.enums.ConnectorType.WHATSAPP, e.message ?: "test failed")
            ResponseEntity.ok(mapOf("ok" to false, "error" to (e.message ?: "failed")))
        }
    }

    /** Create any missing School Agent utility templates on the WABA. */
    @PostMapping("/templates/sync")
    fun syncTemplates(@RequestParam(required = false) kid: java.util.UUID?): ResponseEntity<Map<String, Any>> {
        if (!config.isConfigured(kid)) {
            return ResponseEntity.ok(mapOf("ok" to false, "error" to "phone-number-id or token missing"))
        }
        return try {
            val rows = templateSync.ensure()
            ResponseEntity.ok(mapOf("ok" to rows.none { it.status in setOf("ERROR", "MISMATCH", "REJECTED", "PAUSED", "DISABLED") }, "templates" to rows,
                "error" to (rows.firstOrNull { it.error != null }?.error ?: "Check template approval status")))
        } catch (e: Exception) {
            ResponseEntity.ok(mapOf("ok" to false, "error" to (e.message ?: "failed")))
        }
    }

    /** POST {WABA}/subscribed_apps so live inbound chats are delivered. */
    @PostMapping("/subscribe-waba")
    fun subscribeWaba(@RequestParam(required = false) kid: java.util.UUID?): ResponseEntity<Map<String, Any>> {
        if (!config.isConfigured(kid)) {
            return ResponseEntity.ok(mapOf("ok" to false, "error" to "phone-number-id or token missing"))
        }
        return try {
            val result = config.withChild(kid) { client.subscribeWaba() }
            ResponseEntity.ok(
                buildMap<String, Any> {
                    put("ok", true)
                    putAll(result)
                },
            )
        } catch (e: Exception) {
            ResponseEntity.ok(mapOf("ok" to false, "error" to (e.message ?: "failed")))
        }
    }
}

/**
 * Meta-style webhook (works for any compatible provider): GET verifies the
 * subscription via the shared verify token; POST receives inbound messages,
 * verified with X-Hub-Signature-256 and durably queued before acknowledgment.
 */
@RestController
@RequestMapping("/api/whatsapp/webhook")
class WhatsappWebhookController(
    private val config: WhatsAppConfigService,
    private val outbox: WhatsappOutbox,
    private val inbox: WhatsappInbox,
) {

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun verify(
        @RequestParam("hub.mode") mode: String?,
        @RequestParam("hub.verify_token") verifyToken: String?,
        @RequestParam("hub.challenge") challenge: String?,
    ): ResponseEntity<String> {
        return if (mode == "subscribe" && config.matchesVerifyToken(verifyToken)) {
            ResponseEntity.ok(challenge ?: "")
        } else {
            ResponseEntity.status(HttpStatus.FORBIDDEN).body("forbidden")
        }
    }

    @PostMapping
    fun inbound(
        @RequestBody payload: String,
        @RequestHeader(value = "X-Hub-Signature-256", required = false) signature: String?,
    ): ResponseEntity<String> {
        val secrets = config.appSecrets()
        if (secrets.isEmpty() || signature == null || secrets.none { hmacMatches(it, payload, signature) }) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("bad signature")
        }
        try {
            config.recordWebhook()
            val root = com.fasterxml.jackson.databind.ObjectMapper().readTree(payload)
            for (entry in root.path("entry")) for (change in entry.path("changes")) {
                val value = change.path("value")
                for (status in value.path("statuses")) outbox.record(status)
                for (message in value.path("messages")) inbox.enqueue(value, message)
            }
        } catch (e: Exception) {
            log.warn("whatsapp inbound failed: {}", e.javaClass.simpleName)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("retry")
        }
        return ResponseEntity.ok("ok")
    }

    private fun hmacMatches(secret: String, payload: String, signature: String): Boolean {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val expected = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return java.security.MessageDigest.isEqual(
            signature.toByteArray(Charsets.UTF_8), "sha256=$expected".toByteArray(Charsets.UTF_8))
    }
}
