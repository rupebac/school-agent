package de.schoolagent.connectors.elternportal

import com.sun.net.httpserver.HttpServer
import de.schoolagent.connectors.school.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.LocalDate

/** Real HTTP, redirects and cookies, with arbitrary account pupils and no school network access. */
class HttpElternportalClientTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private var selected = "41"
    private var loginResponse = false
    private var wrongLetterChild = false
    private var downloads = 0
    private val selections = mutableListOf<String>()
    private fun page(content: String = "", selection: String = selected) = """
        <a href='/logout'>Logout</a><h2 id='schule'>Example School</h2>
        <select onchange='set_child(this.value)'>
        <option value='41' ${if (selection == "41") "selected" else ""}>Pat Example (10B)</option>
        <option value='42' ${if (selection == "42") "selected" else ""}>Jamie Example (8C)</option></select>
        <a href='service/termine'>Termine</a>$content
    """

    private fun testClient() = HttpElternportalClient(de.schoolagent.crawler.PublicHttpSession(
        { java.net.URI.create(it) }, { java.net.InetAddress.getAllByName(it) }))
    private fun connector() = ElternportalSchoolConnector().apply { clientFactory = ::testClient }

    @BeforeEach fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val authenticated = exchange.requestHeaders.getFirst("Cookie")?.contains("PHPSESSID=test-session") == true
            val body = when {
                path == "/includes/project/auth/login.php" -> {
                    exchange.responseHeaders.add("Set-Cookie", "PHPSESSID=test-session; Path=/")
                    exchange.responseHeaders.add("Location", "/start")
                    exchange.sendResponseHeaders(302, -1); exchange.close(); return@createContext
                }
                !authenticated || loginResponse -> "<form><input name='csrf' value='abc123'></form>"
                path == "/api/set_child.php" -> {
                    assertThat(exchange.requestMethod).isEqualTo("POST")
                    selected = exchange.requestURI.query.substringAfter("id="); selections += selected
                    "1"
                }
                path == "/aktuelles/elternbriefe" -> page("""<table><tr><td>#1</td></tr><tr><td><a href='aktuelles/get_file/?repo=99&csrf=abc123'>letter.pdf</a></td><td>School letter 11.10.2026, 09:00 letter.pdf</td></tr></table>""", if (wrongLetterChild) "41" else selected)
                path == "/aktuelles/get_file/" -> { downloads++; "%PDF-1.7 test" }
                path == "/service/termine/liste/schulaufgaben" -> page("""<a class='active' href='service/termine/liste/schulaufgaben'>Schulaufgabenplan (8C)</a><table class='termine-table'><tr><th>Datum</th><th>Zeit</th><th>Fach</th></tr><tr><td>20.11.2026</td><td></td><td>Mathematik</td></tr></table>""")
                else -> page()
            }.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }; exchange.close()
        }
        server.start(); base = "http://127.0.0.1:${server.address.port}"
    }
    @AfterEach fun stop() = server.stop(0)

    @Test fun `fresh login selects the requested child and verifies letters through real client code`() {
        connector().connect(SchoolCredentials(SchoolProvider.ELTERNPORTAL, base, "parent", "test", allowReceiptDownloads = true)).use { session ->
            val child = session.selectStudent(SchoolStudentScope("Jamie", "8C", null))!!
            assertThat(child.id).isEqualTo("42"); assertThat(selections).containsExactly("42")
            val letter = session.notices(child.id).single()
            assertThat(session.documents(letter, child.id).single().bytes.decodeToString()).startsWith("%PDF-")
            assertThat(downloads).isEqualTo(1)
            assertThat(session.records(SchoolStudentScope("Jamie", "8C", child.id), LocalDate.of(2026, 10, 11), LocalDate.of(2027, 8, 31)).single { it.kind == SchoolRecordKind.EXAM }.records).hasSize(1)
        }
    }
    @Test fun `PDF retrieval is blocked until automatic receipt permission is explicitly granted`() {
        connector().connect(SchoolCredentials(SchoolProvider.ELTERNPORTAL, base, "parent", "test")).use { session ->
            val child = session.selectStudent(SchoolStudentScope("Jamie", "8C", null))!!
            val notice = session.notices(child.id).single()
            assertThat(session.canAcquire(notice)).isFalse()
            assertThatThrownBy { session.documents(notice, child.id) }.isInstanceOf(SchoolConnectionException::class.java)
            assertThat(downloads).isZero()
        }
    }
    @Test fun `school year rollover blocks only exam section while letters remain accessible`() {
        connector().connect(SchoolCredentials(SchoolProvider.ELTERNPORTAL, base, "parent", "test", "42")).use { session ->
            session.selectStudent(SchoolStudentScope("Jamie", "7C", "42"))
            assertThat(session.notices("42")).hasSize(1)
            val exam = session.records(SchoolStudentScope("Jamie", "7C", "42"), LocalDate.of(2026, 10, 11), LocalDate.of(2027, 8, 31)).single { it.kind == SchoolRecordKind.EXAM }
            assertThat(exam.status).isEqualTo(SchoolFeedStatus.UNAVAILABLE)
            assertThat(exam.message).contains("8C", "Settings")
        }
    }
    @Test fun `expired authentication and switched account views are not accepted as empty letters`() {
        val client = testClient(); client.login(base, "parent", "test")
        loginResponse = true
        assertThatThrownBy { client.dashboard() }.isInstanceOf(ElternportalException::class.java)
        loginResponse = false
        connector().connect(SchoolCredentials(SchoolProvider.ELTERNPORTAL, base, "parent", "test")).use { session ->
            session.selectStudent(SchoolStudentScope("Jamie", "8C", null))
            wrongLetterChild = true
            assertThatThrownBy { session.notices("42") }.isInstanceOf(SchoolConnectionException::class.java)
            assertThat(downloads).isZero()
        }
    }
}
