package de.schoolagent.connectors.school

import org.jsoup.Jsoup
import java.net.URI

/** School HTML becomes readable source text without losing ordinary form/information links. */
object SchoolText {
    fun fromHtml(value: String): String {
        val html = Jsoup.parseBodyFragment(value)
        html.select("script,style").remove()
        html.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            val uri = runCatching { URI(href) }.getOrNull()
            val temporary = uri?.rawQuery?.let {
                Regex("(?i)(?:^|&)(?:[^=]*(?:token|signature|credential)|x-amz-[^=]*|x-goog-[^=]*)=").containsMatchIn(it)
            } == true
            if (uri?.scheme in setOf("http", "https") && uri?.host != null && uri.userInfo == null && !temporary &&
                href != anchor.text()) anchor.appendText(" ($href)")
        }
        html.select("br").append("\\n")
        html.select("p,div,li,h1,h2,h3").prepend("\\n")
        return html.text().replace("\\n", "\n").lines().joinToString("\n") { it.trim() }.trim()
    }
}
