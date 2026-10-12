package de.schoolagent.connectors.elternportal

import org.jsoup.Jsoup
import org.springframework.stereotype.Service
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * HTTP implementation against the real portal (discovered on
 * demo.eltern-portal.org): login form posts csrf/username/password/go_to
 * to /includes/project/auth/login.php and establishes a PHPSESSID; brief rows
 * expose stable repo= ids for dedup and per-file csrf download links.
 */
class HttpElternportalClient : ElternportalClient {

    private val cookieManager = CookieManager()
    private val client: HttpClient = HttpClient.newBuilder()
        .cookieHandler(cookieManager)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private var baseUrl: String = ""

    override fun login(url: String, username: String, password: String) {
        baseUrl = url.trim().trimEnd('/')
        cookieManager.cookieStore.removeAll()

        val loginPage = get("$baseUrl/")
        val csrf = Regex("name='csrf' value='([a-f0-9]+)'").find(loginPage)?.groupValues?.get(1)
            ?: throw ElternportalException("login form not found on $baseUrl", authRelated = true)

        val form = mapOf(
            "csrf" to csrf,
            "username" to username,
            "password" to password,
            "go_to" to "",
        )
        val body = form.entries.joinToString("&") {
            java.net.URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + java.net.URLEncoder.encode(it.value, Charsets.UTF_8)
        }
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/includes/project/auth/login.php"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (!response.uri().path.contains("start") && !response.body().contains("logout")) {
            throw ElternportalException("login failed (check URL/credentials)", authRelated = true)
        }
    }

    fun dashboard(): String = authenticatedPage("start")

    /** Selects the viewing context only. Callers serialize shared accounts and verify the returned pupil. */
    fun selectChild(id: String) {
        require(id.matches(Regex("\\d+"))) { "Invalid portal student ID" }
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/set_child.php?id=$id"))
            .timeout(Duration.ofSeconds(15)).header("Content-Type", "text/plain;charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.noBody()).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200 || response.body().trim() != "1")
            throw ElternportalException("Could not select the matching portal child")
    }

    fun authenticatedPage(path: String): String {
        ensureSession()
        val page = get("$baseUrl/${path.trimStart('/')}")
        if (Jsoup.parse(page).select("a[href=logout],a[href$=/logout]").isEmpty())
            throw ElternportalException("Portal session expired; please test the connection again", authRelated = true)
        return page
    }

    override fun fetchElternbriefe(): List<PortalBrief> {
        return ElternportalParser().parseBriefs(authenticatedPage("aktuelles/elternbriefe"))
    }

    fun fetchElternbriefe(student: de.schoolagent.connectors.school.SchoolStudent): List<PortalBrief> {
        val page = authenticatedPage("aktuelles/elternbriefe")
        ElternportalRecordsParser().requireSelected(page, student)
        return ElternportalParser().parseBriefs(page)
    }

    override fun downloadFile(brief: PortalBrief): ByteArray {
        ensureSession()
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/aktuelles/get_file/?repo=${brief.repoId}&csrf=${brief.csrf}"))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() >= 400) {
            throw ElternportalException("download failed: HTTP ${response.statusCode()}")
        }
        return response.body()
    }

    private fun ensureSession() {
        if (baseUrl.isBlank()) throw ElternportalException("not logged in", authRelated = true)
    }

    private fun get(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "SchoolAgent/0.1 (family private agent)")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() >= 400) throw ElternportalException("HTTP ${response.statusCode()} for $url")
        return response.body()
    }
}
