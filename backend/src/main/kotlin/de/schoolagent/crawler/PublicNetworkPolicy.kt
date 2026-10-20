package de.schoolagent.crawler

import java.net.InetAddress
import java.net.URI

/** External documents may link only to public HTTP(S) destinations. */
object PublicNetworkPolicy {
    fun uri(raw: String): URI {
        val uri = runCatching { URI.create(raw) }.getOrNull()
            ?: throw BlockedCrawlException()
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host == null ||
            uri.userInfo != null || uri.port !in setOf(-1, 80, 443)) throw BlockedCrawlException()
        return uri
    }

    fun addresses(host: String): Array<InetAddress> {
        val result = InetAddress.getAllByName(host)
        if (result.isEmpty() || result.any { !isPublic(it) }) throw BlockedCrawlException()
        return result
    }

    internal fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 255 }
        if (bytes.size == 4) {
            val a = bytes[0]; val b = bytes[1]; val c = bytes[2]
            return a !in setOf(0, 10, 127) && a < 224 &&
                !(a == 100 && b in 64..127) && !(a == 169 && b == 254) &&
                !(a == 172 && b in 16..31) && !(a == 192 && (b == 168 || b == 0 || b == 2)) &&
                !(a == 198 && (b in 18..19 || b == 51 && c == 100)) &&
                !(a == 203 && b == 0 && c == 113)
        }
        // Global unicast only, excluding documentation and transition/translation ranges.
        return bytes.size == 16 && bytes[0] in 0x20..0x3f &&
            !(bytes[0] == 0x20 && bytes[1] == 0x01 && (bytes[2] < 0x02 || bytes[2] == 0x0d && bytes[3] == 0xb8)) &&
            !(bytes[0] == 0x20 && bytes[1] == 0x02)
    }
}

class BlockedCrawlException : CrawlException("External link destination is not allowed")
