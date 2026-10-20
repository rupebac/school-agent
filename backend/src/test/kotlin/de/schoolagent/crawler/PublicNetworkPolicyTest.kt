package de.schoolagent.crawler

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.InetAddress

class PublicNetworkPolicyTest {
    @Test fun `private local metadata shared and transition destinations are blocked`() {
        for (ip in listOf("127.0.0.1", "10.0.0.1", "169.254.169.254", "172.16.0.1", "192.168.1.1",
            "0.0.0.0", "100.64.0.1", "224.0.0.1", "198.18.0.1", "::1", "::", "fc00::1", "fe80::1", "2002:7f00:1::", "::ffff:127.0.0.1")) {
            assertThat(PublicNetworkPolicy.isPublic(InetAddress.getByName(ip))).describedAs(ip).isFalse()
        }
        assertThat(PublicNetworkPolicy.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue()
        assertThat(PublicNetworkPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111"))).isTrue()
    }

    @Test fun `crawler never connects to loopback or non HTTP destinations`() {
        for (url in listOf("http://127.0.0.1/form", "http://[::1]/form", "file:///etc/hosts", "http://localhost/form",
            "http://user:pass@example.org/form", "http://example.org:5432/form")) {
            assertThatThrownBy { JdkHttpCrawler().fetch(url) }.isInstanceOf(BlockedCrawlException::class.java)
        }
    }
}
