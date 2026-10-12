package de.schoolagent.crawler

import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class FetchedPage(
    val requestedUrl: String,
    val finalUrl: String,
    val contentType: String?,
    val bytes: ByteArray,
) {
    val isHtml: Boolean get() = (contentType ?: "").contains("html", ignoreCase = true)
}

/** Port so tests can fake the web. */
interface HttpCrawler {
    fun fetch(url: String): FetchedPage
}

class CrawlException(message: String) : RuntimeException(message)

@Service
class JdkHttpCrawler : HttpCrawler {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override fun fetch(url: String): FetchedPage {
        val uri = runCatching { URI.create(url) }.getOrElse { throw CrawlException("invalid url: $url") }
        if (uri.scheme !in listOf("http", "https")) throw CrawlException("scheme not allowed: ${uri.scheme}")

        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "SchoolAgent/0.1 (family private agent)")
            .GET()
            .build()
        val response: HttpResponse<ByteArray> = try {
            client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: Exception) {
            throw CrawlException("fetch failed: ${e.message}")
        }
        if (response.statusCode() >= 400) throw CrawlException("HTTP ${response.statusCode()} for $url")
        if (response.body().size > MAX_BYTES) throw CrawlException("too large: ${response.body().size} bytes")
        return FetchedPage(
            requestedUrl = url,
            finalUrl = response.uri().toString(),
            contentType = response.headers().firstValue("content-type").orElse(null),
            bytes = response.body(),
        )
    }

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}

/** Deterministic URL + link handling (spec §10/§17). */
object Links {

    private val trackingParams = setOf("utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "fbclid", "gclid")

    private val relevantKeywords = listOf(
        "anmeldung", "teilnahme", "termine", "termin", "elterninformation", "kosten",
        "material", "faq", "einverständnis", "einverstaendnis", "downloads", "formular",
        "form", "registration", "info", "brief",
    )

    private val ignoredExtensions = listOf(".pdf.js", "#", "mailto:", "tel:", "javascript:")

    fun canonicalize(url: String): String? {
        val uri = runCatching { URI.create(url.trim()) }.getOrNull() ?: return null
        if (uri.scheme !in listOf("http", "https")) return null
        val query = uri.rawQuery
            ?.split('&')
            ?.filter { it.isNotBlank() }
            ?.filter { param ->
                val key = param.substringBefore('=').lowercase()
                key !in trackingParams
            }
            ?.joinToString("&")
            ?.takeIf { it.isNotBlank() }
        return runCatching {
            URI(uri.scheme, uri.userInfo, uri.host, uri.port, uri.path ?: "/", query, null).toString()
        }.getOrNull()
    }

    fun isRelevant(linkText: String, url: String): Boolean {
        val lowered = (linkText + " " + url).lowercase()
        if (ignoredExtensions.any { lowered.contains(it) }) return false
        return relevantKeywords.any { lowered.contains(it) }
    }
}
