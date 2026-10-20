package de.schoolagent.crawler

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI

class PublicHttpSessionTest {
    @Test fun `default transport rejects private portal destinations before sending credentials`() {
        assertThatThrownBy { PublicHttpSession().request("http://127.0.0.1/", "POST", "password=fictional") }
            .isInstanceOf(BlockedCrawlException::class.java)
    }
    @Test fun `slow response cannot extend the whole request deadline and body limits apply`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/large") { x -> x.sendResponseHeaders(200, 1024); runCatching { x.responseBody.use { it.write(ByteArray(1024)) } }; x.close() }
        server.createContext("/slow") { x ->
            x.sendResponseHeaders(200, 0)
            runCatching { x.responseBody.use { out -> repeat(40) { out.write(65); out.flush(); Thread.sleep(30) } } }; x.close()
        }
        server.start()
        try {
            val transport = PublicHttpSession(URI::create, InetAddress::getAllByName, 200)
            val base = "http://127.0.0.1:${server.address.port}"
            assertThatThrownBy { transport.request("$base/large", maxBytes = 50) }.isInstanceOf(CrawlException::class.java)
            val start = System.nanoTime()
            assertThatThrownBy { transport.request("$base/slow") }.isInstanceOf(CrawlException::class.java)
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1500)
        } finally { server.stop(0) }
    }
}
