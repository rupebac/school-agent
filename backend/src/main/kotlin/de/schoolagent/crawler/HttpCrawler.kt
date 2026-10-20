package de.schoolagent.crawler

import org.springframework.stereotype.Service
import java.net.URI

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

open class CrawlException(message: String) : RuntimeException(message)

@Service
class JdkHttpCrawler : HttpCrawler {

    override fun fetch(url: String): FetchedPage {
        val response = PublicHttpSession().request(url, maxBytes = MAX_BYTES)
        if (response.status !in 200..299) throw CrawlException("HTTP ${response.status}")
        return FetchedPage(url, response.uri.toString(), response.contentType, response.bytes)
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
            // Keep raw path/query escapes intact: re-encoding % breaks signed/form links.
            if (uri.host == null || uri.userInfo != null) return null
            "${uri.scheme}://${uri.rawAuthority}${uri.rawPath.ifEmpty { "/" }}" +
                (query?.let { "?$it" } ?: "")
        }.getOrNull()
    }

    fun isRelevant(linkText: String, url: String): Boolean {
        val lowered = (linkText + " " + url).lowercase()
        if (ignoredExtensions.any { lowered.contains(it) }) return false
        return relevantKeywords.any { lowered.contains(it) }
    }
}
