package de.schoolagent.crawler

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

/** Read-only access to the playwright-worker (spec §11) for JS-only pages. */
@Service
class BrowserWorkerClient(
    private val rest: RestTemplate,
    @Value("\${schoolagent.browser.worker-url:http://127.0.0.1:8100}") private val workerUrl: String,
) {

    data class RenderedPage(val finalUrl: String, val title: String?, val text: String, val links: List<String>)

    fun available(): Boolean = workerUrl.isNotBlank()

    fun render(url: String): RenderedPage {
        if (!available()) throw CrawlException("Browser worker is disabled")
        PublicNetworkPolicy.addresses(PublicNetworkPolicy.uri(url).host)
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val raw = rest.postForObject(
            "$workerUrl/render",
            HttpEntity(mapOf("url" to url), headers),
            String::class.java,
        ) ?: throw CrawlException("empty worker response")
        val node: JsonNode = com.fasterxml.jackson.databind.ObjectMapper().readTree(raw)
        if (node.has("error")) throw CrawlException("worker: ${node.path("error").asText()}")
        return RenderedPage(
            finalUrl = node.path("url").asText(url),
            title = node.path("title").asText(null),
            text = node.path("text").asText(""),
            links = node.path("links").map { it.asText() },
        )
    }
}
