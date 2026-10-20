package de.schoolagent.crawler

import org.apache.hc.client5.http.DnsResolver
import org.apache.hc.client5.http.cookie.BasicCookieStore
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.io.entity.StringEntity
import org.apache.hc.core5.util.Timeout
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Cookie-aware transport with socket-time DNS checks, bounded bodies and one redirect deadline. */
class PublicHttpSession(
    private val validate: (String) -> URI = PublicNetworkPolicy::uri,
    private val resolve: (String) -> Array<InetAddress> = PublicNetworkPolicy::addresses,
    private val deadlineMillis: Long = 30_000,
) {
    private val cookies = BasicCookieStore()
    data class Response(val uri: URI, val status: Int, val contentType: String?, val bytes: ByteArray) {
        fun text() = bytes.toString(Charsets.UTF_8)
    }
    fun clearCookies() = cookies.clear()
    fun request(url: String, method: String = "GET", body: String? = null,
                contentType: String = "application/x-www-form-urlencoded", maxBytes: Int = 8 * 1024 * 1024): Response {
        var uri = validate(url)
        var verb = method
        var payload = body
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMillis)
        val lookup = resolve
        val manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(object : DnsResolver {
            override fun resolve(host: String) = lookup(host)
            override fun resolveCanonicalHostname(host: String) = host
        }).setDefaultConnectionConfig(org.apache.hc.client5.http.config.ConnectionConfig.custom()
            .setConnectTimeout(Timeout.ofSeconds(10)).setSocketTimeout(Timeout.ofSeconds(15)).build()).build()
        HttpClients.custom().setConnectionManager(manager).setDefaultCookieStore(cookies)
            .disableRedirectHandling().disableAutomaticRetries().build().use { client ->
            repeat(6) { hop ->
                var remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remaining <= 0) throw CrawlException("External request deadline exceeded")
                // Literals can bypass a transport's DNS resolver.
                resolve(uri.host)
                remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remaining <= 0) throw CrawlException("External request deadline exceeded")
                val request = HttpUriRequestBase(verb, uri)
                request.setHeader("User-Agent", "SchoolAgent/0.1")
                payload?.let { request.entity = StringEntity(it, ContentType.parse(contentType)) }
                val timer = deadlines.schedule({ request.cancel() }, remaining, TimeUnit.MILLISECONDS)
                try {
                    client.executeOpen(null, request, null).use { response ->
                        try {
                        if (response.code in setOf(301, 302, 303, 307, 308)) {
                            val target = response.getFirstHeader("Location")?.value ?: throw CrawlException("Redirect without destination")
                            if (hop == 5) throw CrawlException("Too many redirects")
                            val next = validate(uri.resolve(target).toString())
                            if (uri.scheme == "https" && next.scheme != "https") throw BlockedCrawlException()
                            if (verb != "GET" && verb != "HEAD" && next.authority != uri.authority) throw BlockedCrawlException()
                            if (response.code == 303 || verb == "POST" && response.code in setOf(301, 302)) {
                                verb = "GET"; payload = null
                            }
                            uri = next
                            request.cancel()
                        } else {
                            val bytes = response.entity?.content?.readNBytes(maxBytes + 1) ?: byteArrayOf()
                            if (bytes.size > maxBytes) throw CrawlException("External response exceeds size limit")
                            return Response(uri, response.code, response.getFirstHeader("Content-Type")?.value, bytes)
                        }
                        } finally { request.cancel() }
                    }
                } catch (e: CrawlException) { throw e
                } catch (e: Exception) { throw CrawlException("External request failed (${e.javaClass.simpleName})")
                } finally { request.cancel(); timer.cancel(false) }
            }
        }
        throw CrawlException("Too many redirects")
    }
    companion object {
        private val deadlines = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "external-http-deadlines").apply { isDaemon = true } }
    }
}
