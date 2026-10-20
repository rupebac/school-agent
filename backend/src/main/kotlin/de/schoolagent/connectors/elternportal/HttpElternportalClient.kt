package de.schoolagent.connectors.elternportal

import org.jsoup.Jsoup

/**
 * HTTP implementation against the real portal (discovered on
 * demo.eltern-portal.org): login form posts csrf/username/password/go_to
 * to /includes/project/auth/login.php and establishes a PHPSESSID; brief rows
 * expose stable repo= ids for dedup and per-file csrf download links.
 */
class HttpElternportalClient(private val http: de.schoolagent.crawler.PublicHttpSession = de.schoolagent.crawler.PublicHttpSession()) : ElternportalClient {

    private var baseUrl: String = ""
    private var sessionStarted = System.nanoTime()

    override fun login(url: String, username: String, password: String) {
        sessionStarted = System.nanoTime()
        baseUrl = url.trim().trimEnd('/')
        http.clearCookies()

        val loginPage = get("$baseUrl/")
        val csrf = Regex("name='csrf' value='([a-f0-9]+)'").find(loginPage)?.groupValues?.get(1)
            ?: throw ElternportalException("login form not found", authRelated = true)

        val form = mapOf(
            "csrf" to csrf,
            "username" to username,
            "password" to password,
            "go_to" to "",
        )
        val body = form.entries.joinToString("&") {
            java.net.URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + java.net.URLEncoder.encode(it.value, Charsets.UTF_8)
        }
        val response = http.request("$baseUrl/includes/project/auth/login.php", "POST", body)
        if (response.status !in 200..299 || !response.uri.path.contains("start") && !response.text().contains("logout")) {
            throw ElternportalException("login failed (check URL/credentials)", authRelated = true)
        }
    }

    fun dashboard(): String = authenticatedPage("start")

    /** Selects the viewing context only. Callers serialize shared accounts and verify the returned pupil. */
    fun selectChild(id: String) {
        require(id.matches(Regex("\\d+"))) { "Invalid portal student ID" }
        val response = http.request("$baseUrl/api/set_child.php?id=$id", "POST", "", "text/plain;charset=UTF-8")
        if (response.status != 200 || response.text().trim() != "1")
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
        val response = http.request("$baseUrl/aktuelles/get_file/?repo=${brief.repoId}&csrf=${brief.csrf}")
        if (response.status !in 200..299) throw ElternportalException("download failed: HTTP ${response.status}")
        return response.bytes
    }

    private fun ensureSession() {
        if (baseUrl.isBlank()) throw ElternportalException("not logged in", authRelated = true)
        require(java.time.Duration.ofNanos(System.nanoTime() - sessionStarted) < java.time.Duration.ofMinutes(4)) {
            "School session request budget reached"
        }
    }

    private fun get(url: String): String {
        val response = http.request(url)
        if (response.status !in 200..299) throw ElternportalException("HTTP ${response.status}")
        return response.text()
    }
}
