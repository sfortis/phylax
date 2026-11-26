package com.asksakis.freegate.ha

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Decides whether the app's calls to Home Assistant itself (the token refresh and the HA
 * WebSocket) must verify the server certificate.
 *
 * Those calls carry the HA refresh token, which gives full access to Home Assistant, and
 * the notification service sends them in the background on any network. On a public host
 * a client that accepts any certificate would hand the token to whoever answers on a
 * hostile Wi-Fi. Public HA addresses (Nabu Casa, DuckDNS with Let's Encrypt, Cloudflare
 * Tunnel, Tailscale `*.ts.net` certificates) have valid certificates, so verification costs
 * them nothing.
 *
 * Hosts that only exist inside a home network or a VPN keep the app's normal, permissive
 * policy, because they commonly use self-signed certificates: private and link-local
 * addresses, the Tailscale ranges (100.64.0.0/10 and IPv6 ULA), single-label names such
 * as MagicDNS names, and local suffixes such as `.local`. Plain http has no certificate.
 */
object HaTlsPolicy {

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal", ".localdomain")

    fun requiresStrictTls(origin: String): Boolean {
        val url = origin.toHttpUrlOrNull() ?: return true
        if (!url.isHttps) return false
        val host = url.host.lowercase()
        return when {
            host.contains(':') -> !isPrivateIpv6(host)
            isIpv4(host) -> !isPrivateIpv4(host)
            !host.contains('.') -> false
            else -> LOCAL_SUFFIXES.none { host.endsWith(it) }
        }
    }

    private fun isIpv4(host: String): Boolean = host.split('.').let { parts ->
        parts.size == IPV4_PARTS && parts.all { it.toIntOrNull() in 0..MAX_OCTET }
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val (a, b) = host.split('.').map { it.toInt() }
        return a == 10 || a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            (a == 100 && b in 64..127) // carrier-grade NAT, used by Tailscale
    }

    /** Loopback, unique local (fc00::/7, which includes Tailscale) and link-local (fe80::/10). */
    private fun isPrivateIpv6(host: String): Boolean =
        host == "::1" || host.startsWith("fc") || host.startsWith("fd") ||
            host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")

    private const val IPV4_PARTS = 4
    private const val MAX_OCTET = 255
}
