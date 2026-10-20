package de.schoolagent.connectors.schulmanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.schoolagent.connectors.school.*
import org.jsoup.Jsoup
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@Service
class SchulmanagerConnector : SchoolConnector {
    override val provider = SchoolProvider.SCHULMANAGER
    override fun connect(credentials: SchoolCredentials): SchoolSession = SchulmanagerSession(credentials)
}

/** Read-only protocol: no confirm, survey submission, model update or arbitrary API calls. */
class SchulmanagerSession(
    private val credentials: SchoolCredentials,
    private val baseUrl: String = BASE_URL,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : SchoolSession {
    private val json = ObjectMapper()
    private val sessionStarted = System.nanoTime()
    private var token: String? = null
    override val identity: SchoolIdentity
    private val institutionId: String
    private val selfId: String?
    private var storageUrl: String? = null
    private val messages = SchulmanagerMessages(::optionalCall)
    // The public web client performs a separate letters/confirm call after fetching details.
    override val canRefreshDocuments = true

    init {
        val salt = post("/api/get-salt", mapOf("emailOrUsername" to credentials.username, "mobileApp" to false))
        if (!salt.isTextual) throw SchoolConnectionException("Schulmanager login requires account selection; check the account in Schulmanager")
        val login = post("/api/login", mapOf(
            "emailOrUsername" to credentials.username, "password" to credentials.password,
            "hash" to passwordHash(credentials.password, salt.asText()), "mobileApp" to false,
        ))
        token = login.path("jwt").asText().ifBlank { login.path("token").asText() }
            .takeIf { it.isNotBlank() }
            ?: throw SchoolConnectionException("Schulmanager login failed or needs another login step; check the account in Schulmanager")
        val user = login.path("user")
        selfId = user.path("id").asText(null)
        institutionId = user.path("institutionId").asText()
        if (credentials.institutionId != null && credentials.institutionId != institutionId) {
            throw SchoolConnectionException("This account belongs to a different school; reconnect it in Settings")
        }
        val students = user.path("associatedParents").map { it.path("student") }.filter { it.isObject }.toMutableList()
        user.path("associatedStudent").takeIf { it.isObject }?.let { students.add(it) }
        identity = SchoolIdentity(institutionId, null, students.distinctBy { it.path("id").asText() }.map {
            SchoolStudent(it.path("id").asText(), listOf(it.path("firstname").asText(), it.path("lastname").asText()).joinToString(" ").trim())
        })
    }

    override fun selectStudent(scope: SchoolStudentScope): SchoolStudent {
        requireStudent(scope.studentId)
        return identity.students.single { it.id == scope.studentId }
    }

    override fun records(scope: SchoolStudentScope, from: java.time.LocalDate, to: java.time.LocalDate): List<SchoolRecordFeed> {
        requireStudent(scope.studentId)
        return SchulmanagerRecords(::optionalCall).read(scope.studentId!!, from, to)
    }

    override fun notices(studentId: String?): List<SchoolNotice> {
        requireStudent(studentId)
        val notices = linkedMapOf<String, SchoolNotice>()
        var offset = 0
        repeat(200) {
            val page = call("letters", "get-letters", mapOf("offset" to offset))
            if (!page.isArray) throw SchoolConnectionException("Unexpected Schulmanager letter list")
            // The website returns 15 rows plus one lookahead row; that row belongs to the next page.
            val more = page.size() == 16
            val batch = if (more) page.take(15) else page.toList()
            for (row in batch) {
                if (row.path("studentStatuses").none { it.path("studentId").asText() == studentId }) continue
                val id = row.path("id").asText()
                if (id.isBlank()) throw SchoolConnectionException("Schulmanager returned a letter without an ID")
                notices[id] = SchoolNotice(
                    id, row.path("title").asText("School letter"),
                    parseDate(row.path("createdAt").asText()), "$BASE_URL/#/modules/letters/view/$id",
                )
            }
            if (!more) return notices.values.toList()
            offset += 15
        }
        throw SchoolConnectionException("Schulmanager letter list exceeded the pagination limit")
    }

    override fun noticeFeeds(studentId: String?): List<SchoolNoticeFeed> {
        requireStudent(studentId)
        return super.noticeFeeds(studentId) + messages.read(identity, studentId!!, credentials.shareConnectedMessages, selfId) +
            SchoolNoticeFeed(SchoolNoticeKind.NOTICEBOARD, SchoolFeedStatus.UNAVAILABLE,
                message = "This provider exposes school notices through letters and messages")
    }

    override fun documents(notice: SchoolNotice, studentId: String?): List<SchoolDocument> {
        return details(notice, studentId).documents
    }

    override fun details(notice: SchoolNotice, studentId: String?): SchoolNoticeDetails {
        requireStudent(studentId)
        if (notice.kind == SchoolNoticeKind.MESSAGE) return messages.details(notice, ::downloadAttachment)
        val detail = call("main", "poqa", mapOf(
            "action" to mapOf("model" to "modules/letters/letter", "action" to "findByPk", "parameters" to listOf(
                notice.id.toLong(), mapOf("include" to listOf(
                    mapOf("association" to "attachments", "required" to false, "attributes" to listOf("id", "filename", "file", "contentType", "inline", "letterId")),
                    mapOf("association" to "studentStatuses", "required" to true, "where" to mapOf("studentId" to mapOf("\$in" to listOf(studentId!!.toLong())))),
                    mapOf("association" to "questions", "required" to false),
                )),
            )), "uiState" to "main.modules.letters.view.details",
        ))
        if (!detail.isObject || detail.path("studentStatuses").none { it.path("studentId").asText() == studentId }) {
            throw SchoolConnectionException("Letter is not available for the selected child")
        }
        val text = buildString {
            appendLine(detail.path("title").asText(notice.title))
            appendLine("Sent: ${detail.path("sentDate").asText()}")
            appendLine(de.schoolagent.connectors.school.SchoolText.fromHtml(detail.path("text").asText()))
            detail.path("answerDeadline").takeIf { !it.isNull && !it.isMissingNode }?.let { appendLine("Response deadline: ${it.asText()}") }
            for (question in detail.path("questions")) {
                appendLine("School response requested: ${Jsoup.parse(question.path("text").asText()).text()}")
                appendLine("Response type: ${question.path("type").asText("Unknown")}")
                val options = question.path("additionalData").path("options")
                if (options.isObject) options.fields().asSequence().sortedBy { it.key }.forEach {
                    if (it.value.isTextual) appendLine("Option ${it.key}: ${Jsoup.parse(it.value.asText()).text()}")
                }
            }
        }
        val documents = mutableListOf(SchoolDocument("letter", notice.title, "text/plain", text.toByteArray(Charsets.UTF_8), sentAt = parseDate(detail.path("sentDate").asText())))
        documents += detail.path("attachments").map(::downloadAttachment)
        val status = detail.path("studentStatuses").single { it.path("studentId").asText() == studentId }
        val questions = detail.path("questions")
        val hasQuestions = questions.isArray && !questions.isEmpty
        val read = status.hasNonNull("readTimestamp")
        val deadlineText = detail.path("answerDeadline").asText(null)
        val deadlineDay = deadlineText?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        val deadline = deadlineText?.let {
            parseDate(it) ?: deadlineDay?.atStartOfDay(java.time.ZoneId.of("Europe/Berlin"))?.toInstant()
                ?: throw SchoolConnectionException("Unrecognized response deadline")
        }
        val questionIds = questions.map { it.path("id").asText() }
        require(questionIds.none { it.isBlank() } && questionIds.distinct().size == questionIds.size)
        val answered = if (status.path("formData").isObject) questions.count {
            status.path("formData").hasNonNull(it.path("id").asText())
        } else 0
        return SchoolNoticeDetails(documents, SchoolResponse(
            receipt = if (!status.has("readTimestamp")) "UNKNOWN" else if (read) "ACKNOWLEDGED" else "PENDING",
            response = when {
                !questions.isArray -> "UNKNOWN"
                !hasQuestions -> "NOT_REQUIRED"
                read -> "SUBMITTED"
                (deadlineDay?.isBefore(java.time.LocalDate.now(java.time.ZoneId.of("Europe/Berlin")))
                    ?: deadline?.isBefore(Instant.now())) == true -> "CLOSED"
                answered > 0 -> "PARTIAL"
                else -> "PENDING"
            },
            deadline = deadline,
            questionIds = questionIds,
            answeredQuestions = if (hasQuestions) answered else null,
            deadlineAllDay = deadlineDay != null,
        ))
    }

    private fun downloadAttachment(attachment: JsonNode): SchoolDocument {
            // Inline images are real evidence too, especially when the letter is only a scanned image.
            val storedFile = attachment.path("file").asText()
            if (storedFile.isBlank()) throw SchoolConnectionException("A Schulmanager attachment is unavailable; retry the sync")
            val descriptor = json.readTree(storedFile)
            if (!descriptor.isArray || descriptor.size() != 7 || descriptor[0].asText() != institutionId) {
                throw SchoolConnectionException("Unexpected Schulmanager attachment descriptor")
            }
            val origin = storageUrl ?: call("main", "get-remote-storage-url").asText().also { storageUrl = it }
            val uri = URI.create(origin)
            // Do not send file keys to an arbitrary host, even if returned in an upstream payload.
            if (baseUrl == BASE_URL && (uri.scheme != "https" || !(uri.host == "schulmanager-online.de" || uri.host?.endsWith(".schulmanager-online.de") == true))) {
                throw SchoolConnectionException("Unrecognized Schulmanager download host")
            }
            val encoded = Base64.getEncoder().encodeToString(storedFile.toByteArray(Charsets.UTF_8))
            val bytes = getBytes("${origin.trimEnd('/')}/download-file/$encoded")
            val mime = attachment.path("contentType").asText().ifBlank { descriptor[4].asText("application/octet-stream") }
            if (mime == "application/pdf" && !bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) {
                throw SchoolConnectionException("Schulmanager returned an invalid PDF")
            }
            return SchoolDocument(attachment.path("id").asText(), attachment.path("filename").asText("Attachment"), mime, bytes)
    }

    private fun requireStudent(studentId: String?) {
        if (studentId == null || identity.students.none { it.id == studentId }) {
            throw SchoolConnectionException("Select the matching Schulmanager child in Settings")
        }
    }

    private fun call(module: String, endpoint: String, parameters: Any = emptyMap<String, Any>()): JsonNode =
        optionalCall(module, endpoint, parameters) ?: throw SchoolConnectionException("Schulmanager could not read $endpoint; check account permissions")

    private fun optionalCall(module: String, endpoint: String, parameters: Any): JsonNode? {
        val body = post("/api/calls", mapOf("bundleVersion" to BUNDLE_VERSION, "requests" to listOf(
            mapOf("moduleName" to module, "endpointName" to endpoint, "parameters" to parameters),
        )))
        val result = body.path("results").firstOrNull() ?: throw SchoolConnectionException("Schulmanager returned no result")
        if (result.path("status").asInt() in setOf(403, 404)) return null
        if (result.path("status").asInt() != 200) {
            throw SchoolConnectionException("Schulmanager could not read $endpoint (status ${result.path("status").asInt()}); check account permissions")
        }
        return result.path("data")
    }

    private fun post(path: String, payload: Any): JsonNode {
        require(Duration.ofNanos(System.nanoTime() - sessionStarted) < Duration.ofMinutes(4)) { "School session request budget reached" }
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json").header("User-Agent", "SchoolAgent/0.1 (private family connector)")
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw SchoolConnectionException("Schulmanager request failed (HTTP ${response.statusCode()}); check the connection in Settings")
        return json.readTree(response.body())
    }

    private fun getBytes(url: String): ByteArray {
        require(Duration.ofNanos(System.nanoTime() - sessionStarted) < Duration.ofMinutes(4)) { "School session request budget reached" }
        val response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { stream ->
            if (response.statusCode() != 200) throw SchoolConnectionException("Schulmanager attachment download failed (HTTP ${response.statusCode()})")
            val bytes = stream.readNBytes(MAX_ATTACHMENT_BYTES + 1)
            if (bytes.size > MAX_ATTACHMENT_BYTES) throw SchoolConnectionException("Schulmanager attachment exceeds 8 MB")
            return bytes
        }
    }

    override fun close() { token = null }

    companion object {
        const val BASE_URL = "https://login.schulmanager-online.de"
        // API compatibility version from the live web client, verified September 2026.
        const val BUNDLE_VERSION = "fd1f308791"
        const val MAX_ATTACHMENT_BYTES = de.schoolagent.documents.TextExtractor.MAX_INPUT_BYTES
        fun passwordHash(password: String, salt: String): String {
            val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(Charsets.UTF_8), 99999, 4096)
            return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded.joinToString("") { "%02x".format(it) } }
            finally { spec.clearPassword() }
        }
        private fun parseDate(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
    }
}
