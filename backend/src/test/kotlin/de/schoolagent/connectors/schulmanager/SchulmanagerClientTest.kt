package de.schoolagent.connectors.schulmanager

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import de.schoolagent.connectors.school.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class SchulmanagerClientTest {
    private val json = ObjectMapper()

    @Test
    fun `reads paginated child-scoped letters and attachments without acknowledging or submitting anything`() {
        val calls = CopyOnWriteArrayList<String>()
        val offsets = mutableListOf<Int>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        fun letter(id: Int, student: Int = 7) = mapOf("id" to id, "title" to "Letter $id", "createdAt" to "2026-09-25T08:00:00Z", "studentStatuses" to listOf(mapOf("studentId" to student, "readTimestamp" to null)))
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = if (exchange.requestMethod == "POST") json.readTree(exchange.requestBody) else null
            val reply: Any = when (path) {
                "/api/get-salt" -> "test-salt"
                "/api/login" -> mapOf("jwt" to "test-token", "user" to mapOf("institutionId" to 1, "associatedParents" to listOf(mapOf("student" to mapOf("id" to 7, "firstname" to "Taylor", "lastname" to "Example")), mapOf("student" to mapOf("id" to 8, "firstname" to "Other")))))
                "/api/calls" -> {
                    assertThat(exchange.requestHeaders.getFirst("Authorization")).isEqualTo("Bearer test-token")
                    val req = body!!.path("requests")[0]
                    val endpoint = req.path("endpointName").asText()
                    calls.add(endpoint)
                    val data: Any = when (endpoint) {
                        "get-letters" -> {
                            val offset = req.path("parameters").path("offset").asInt()
                            offsets.add(offset)
                            if (offset == 0) (1..16).map { letter(it, if (it == 2) 8 else 7) } else listOf(letter(16), letter(17))
                        }
                        "poqa" -> {
                            assertThat(req.path("parameters").path("action").path("action").asText()).isEqualTo("findByPk")
                            assertThat(req.toString()).contains("studentId", "7", "modules/letters/letter")
                            mapOf("id" to 1, "title" to "Trip", "text" to "<p>Bring a lunch.</p><p>Meet at school.</p>", "sentDate" to "2026-09-25", "answerDeadline" to "2026-10-01", "studentStatuses" to listOf(mapOf("studentId" to 7)),
                                "questions" to listOf(mapOf("id" to 1, "text" to "May your child attend?")),
                                "attachments" to listOf(mapOf("id" to 12, "filename" to "letter.pdf", "contentType" to "application/pdf", "file" to json.writeValueAsString(listOf(1, "letters", "file", "secret-key", "application/pdf", 15, "letter.pdf")))))
                        }
                        "get-remote-storage-url" -> base
                        else -> error("Unexpected request: $endpoint")
                    }
                    mapOf("results" to listOf(mapOf("status" to 200, "data" to data)))
                }
                else -> "%PDF-1.7 test"
            }
            val bytes = if (path.startsWith("/download-file/")) reply.toString().toByteArray() else json.writeValueAsBytes(reply)
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            SchulmanagerSession(SchoolCredentials(SchoolProvider.SCHULMANAGER, base, "parent@example.test", "password", "7", "1"), base).use { session ->
                assertThat(session.identity.students.map { it.id }).containsExactly("7", "8")
                assertThatThrownBy { session.notices("999") }.isInstanceOf(SchoolConnectionException::class.java)
                val letters = session.notices("7")
                assertThat(letters).hasSize(16)
                assertThat(letters.map { it.id }).doesNotContain("2").doesNotHaveDuplicates()
                assertThat(offsets).containsExactly(0, 15)
                val parts = session.documents(letters.first(), "7")
                assertThat(parts).hasSize(2)
                assertThat(String(parts[0].bytes)).contains("Bring a lunch.\n", "School response requested: May your child attend?", "2026-10-01").doesNotContain("secret-key")
                assertThat(String(parts[1].bytes)).startsWith("%PDF-")
                assertThat(calls).containsOnly("get-letters", "poqa", "get-remote-storage-url")
            }
        } finally { server.stop(0) }
    }

    @Test
    fun `password derivation matches the web client`() {
        // Independent Python hashlib.pbkdf2_hmac vector, 512-byte key (4096 bits).
        val hash = SchulmanagerSession.passwordHash("password", "salt")
        assertThat(hash).hasSize(1024)
        assertThat(hash.take(32)).isEqualTo("5bb36a2e9b7a00aa87c470a53e4b68ba")
    }
}
