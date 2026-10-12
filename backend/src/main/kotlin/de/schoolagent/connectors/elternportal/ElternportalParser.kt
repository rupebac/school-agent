package de.schoolagent.connectors.elternportal

import org.jsoup.Jsoup

/** One Elternbrief row as shown on aktuelles/elternbriefe. */
data class PortalBrief(
    val repoId: String,
    val number: Int?,
    val title: String,
    val dateText: String?,
    val pdfName: String?,
    val classes: String?,
    val csrf: String,
)

class ElternportalException(message: String, val authRelated: Boolean = false) : RuntimeException(message)

/**
 * Elternportal access (spec §6): direct HTTP, read-only. No public API exists,
 * so we login like a browser (CSRF + PHP session) and parse the brief list.
 * This is the only read path — v1 never confirms receipt or changes anything.
 */
interface ElternportalClient {
    /** Establishes a fresh session; returns true on success. */
    fun login(url: String, username: String, password: String)

    fun fetchElternbriefe(): List<PortalBrief>

    fun downloadFile(brief: PortalBrief): ByteArray
}

class ElternportalParser {
    fun parseBriefs(html: String): List<PortalBrief> {
        val doc = Jsoup.parse(html)
        val briefs = mutableListOf<PortalBrief>()
        var lastNumber: Int? = null

        for (row in doc.select("tr")) {
            val text = row.text().trim()
            val numberMatch = Regex("#(\\d+)\\s+(Empfang|nicht)").find(text)
            if (numberMatch != null && row.select("a[href*=repo=]").isEmpty()) {
                lastNumber = numberMatch.groupValues[1].toIntOrNull() ?: lastNumber
                continue
            }
            val link = row.selectFirst("a[href*=repo=]") ?: continue
            val href = link.attr("href")
            val repoId = Regex("repo=(\\d+)").find(href)?.groupValues?.get(1) ?: continue
            val csrf = Regex("csrf=([a-f0-9]+)").find(href)?.groupValues?.get(1) ?: continue
            val rowText = row.text()
            val date = Regex("(\\d{2}\\.\\d{2}\\.\\d{4}, \\d{2}:\\d{2})").find(rowText)?.groupValues?.get(1)
            val pdfName = Regex("([\\w\\-.,() ]+\\.pdf)").find(rowText)?.groupValues?.get(1)?.trim()
            val classes = Regex("Klasse/n:\\s*([A-Za-z0-9, ]+?)(?:\\s+Liebe|\\s+Sehr|\\s+\\Z)")
                .find(rowText)?.groupValues?.get(1)?.trim()
            val title = if (date != null) {
                rowText.substringBefore(date).trim().take(200)
            } else {
                rowText.take(200)
            }
            briefs += PortalBrief(
                repoId = repoId,
                number = lastNumber,
                title = title.ifBlank { pdfName ?: "Elternbrief #$repoId" },
                dateText = date,
                pdfName = pdfName,
                classes = classes,
                csrf = csrf,
            )
            lastNumber = null
        }
        return briefs
    }
}
