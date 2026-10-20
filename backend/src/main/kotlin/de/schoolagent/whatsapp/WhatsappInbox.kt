package de.schoolagent.whatsapp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/** Durable inbound delivery. Remote sends with an uncertain outcome are never blindly repeated. */
@Service
class WhatsappInbox(private val jdbc: JdbcTemplate, private val processor: WhatsappMessageProcessor,
                    private val client: WhatsappClient, private val config: WhatsAppConfigService,
                    private val clock: Clock) {
    private val json = jacksonObjectMapper()
    private val running = java.util.concurrent.atomic.AtomicBoolean()
    private data class Work(val id: String, val payload: String?, val reply: String?, val attempts: Int, val lease: String)

    @Transactional
    fun enqueue(value: JsonNode, message: JsonNode) {
        val remoteId = message.path("id").asText()
        val phoneId = value.path("metadata").path("phone_number_id").asText()
        require(remoteId.isNotBlank() && remoteId.length <= 512 && phoneId.isNotBlank()) { "Missing inbound message identity" }
        val id = java.security.MessageDigest.getInstance("SHA-256").digest("$phoneId:$remoteId".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val payload = json.writeValueAsString(mapOf("metadata" to value.path("metadata"), "message" to message))
        require(payload.length <= 256 * 1024) { "Inbound message exceeds size limit" }
        val now = Timestamp.from(clock.instant())
        jdbc.update("""insert into whatsapp_inbound(id,payload,next_attempt_at,created_at,updated_at)
            values (?,?,?,?,?) on conflict (id) do nothing""", id, payload, now, now, now)
    }

    @Scheduled(fixedDelayString = "\${schoolagent.whatsapp.inbound-retry-ms:30000}", initialDelay = 60000)
    fun drain() {
        if (!running.compareAndSet(false, true)) return
        try {
            repeat(20) {
                val now = Timestamp.from(clock.instant())
                val lease = UUID.randomUUID().toString()
                val row = jdbc.query("""update whatsapp_inbound set status='PROCESSING', lease_token=?, attempts=attempts+1,
                    next_attempt_at=?, updated_at=? where id=(select id from whatsapp_inbound
                    where next_attempt_at<=? and attempts<5 order by created_at for update skip locked limit 1)
                    returning id,payload,reply,attempts""", { rs, _ -> Work(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), lease) },
                    lease,
                    Timestamp.from(clock.instant().plusSeconds(600)), now, now).firstOrNull() ?: return@repeat
                process(row)
            }
            // Preserve deduplication tombstones for 60 days; content is unnecessary after completion.
            jdbc.update("update whatsapp_inbound set status='FAILED', next_attempt_at=null where attempts>=5 and next_attempt_at<=?", Timestamp.from(clock.instant()))
            jdbc.update("update whatsapp_inbound set payload=null, reply=null where updated_at<?", Timestamp.from(clock.instant().minusSeconds(7 * 86400)))
            jdbc.update("delete from whatsapp_inbound where created_at<?", Timestamp.from(clock.instant().minusSeconds(60 * 86400)))
        } finally { running.set(false) }
    }

    fun statusCounts(): Map<String, Int> = jdbc.query("select status,count(*) from whatsapp_inbound group by status",
        { rs, _ -> rs.getString(1) to rs.getInt(2) }).toMap()

    private fun process(row: Work) {
        val reply = try {
            if (row.reply != null) json.readValue(row.reply, WhatsappMessageProcessor.PreparedReply::class.java)
            else {
                val payload = json.readTree(row.payload ?: error("Expired inbound content"))
                processor.prepare(payload, payload.path("message"))?.also {
                    // Commit preparation before sending so retries reuse the same response.
                    jdbc.update("update whatsapp_inbound set reply=?, updated_at=? where id=? and lease_token=?", json.writeValueAsString(it), Timestamp.from(clock.instant()), row.id, row.lease)
                }
            }
        } catch (e: Exception) { retry(row, e.javaClass.simpleName); return }
        if (reply == null || config.userForNumber(reply.recipient)?.let { it.id.toString() == reply.userId && WhatsappMessageProcessor.accessStamp(it) == reply.accessStamp } != true) {
            finish(row, "SKIPPED"); return
        }
        // This state survives a crash or timeout after the provider may have accepted the reply.
        if (jdbc.update("update whatsapp_inbound set status='UNKNOWN', next_attempt_at=null, updated_at=? where id=? and lease_token=?", Timestamp.from(clock.instant()), row.id, row.lease) != 1) return
        try {
            config.withChild(reply.childId) { client.sendText(reply.recipient, reply.text) }
            finish(row, "DONE")
        } catch (e: WhatsappSendException) {
            if (!e.ambiguous && e.retryable) retry(row, "Provider rejected send; retry scheduled")
            else if (!e.ambiguous) finish(row, "FAILED")
        } catch (_: Exception) { /* UNKNOWN deliberately needs operator review. */ }
    }
    private fun finish(row: Work, status: String) {
        jdbc.update("update whatsapp_inbound set status=?,payload=null,reply=null,next_attempt_at=null,last_error=null,updated_at=? where id=? and lease_token=?", status, Timestamp.from(clock.instant()), row.id, row.lease)
    }
    private fun retry(row: Work, error: String) {
        jdbc.update("update whatsapp_inbound set status=?,next_attempt_at=?,last_error=?,updated_at=? where id=? and lease_token=?",
            if (row.attempts < 5) "RETRY" else "FAILED",
            if (row.attempts < 5) Timestamp.from(clock.instant().plusSeconds(60L * row.attempts)) else null,
            error.take(120), Timestamp.from(clock.instant()), row.id, row.lease)
    }
}

@org.springframework.web.bind.annotation.RestController
class WhatsappInboundStatusController(private val inbox: WhatsappInbox) {
    @org.springframework.web.bind.annotation.GetMapping("/api/whatsapp/inbound-status")
    fun status() = inbox.statusCounts()
}
