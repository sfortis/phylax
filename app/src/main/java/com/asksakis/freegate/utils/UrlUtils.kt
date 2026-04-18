package com.asksakis.freegate.utils

import android.net.Uri

/**
 * Utility object for URL type detection and manipulation.
 * Centralizes the logic for determining internal vs external URLs.
 *
 * Classification is done on the URL host (parsed via Uri), never on substrings of the
 * full URL, so paths and query strings cannot cause misclassification.
 */
object UrlUtils {

    private val IPV4_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val PRIVATE_IPV4_REGEX = Regex(
        "^(10(\\.\\d{1,3}){3}|" +
        "192\\.168(\\.\\d{1,3}){2}|" +
        "172\\.(1[6-9]|2\\d|3[0-1])(\\.\\d{1,3}){2}|" +
        "100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])(\\.\\d{1,3}){2}|" +  // CGNAT 100.64.0.0/10 (Tailscale)
        "127(\\.\\d{1,3}){3}|" +                                         // loopback
        "169\\.254(\\.\\d{1,3}){2})$"                                    // link-local
    )

    private fun hostOf(url: String): String? =
        runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()

    /**
     * Checks if a URL is an internal/local URL based solely on its host.
     * Internal hosts: .local/.lan mDNS names, private/loopback IP ranges, or any plain-http URL.
     */
    fun isInternalUrl(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return true
        val host = hostOf(url) ?: return false
        return host.endsWith(".local") ||
               host.endsWith(".lan") ||
               (IPV4_REGEX.matches(host) && PRIVATE_IPV4_REGEX.matches(host))
    }

    /** External URLs are https:// URLs whose host is not internal. */
    fun isExternalUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) && !isInternalUrl(url)

    /**
     * True when the URL points at a private/loopback/link-local IP or mDNS host.
     * Used to decide whether relaxed TLS behavior (self-signed, mixed content) is safe.
     */
    fun isPrivateIpUrl(url: String): Boolean {
        val host = hostOf(url) ?: return false
        if (host.endsWith(".local") || host.endsWith(".lan")) return true
        return IPV4_REGEX.matches(host) && PRIVATE_IPV4_REGEX.matches(host)
    }

    /** True when the host portion is a literal IPv4 address. */
    fun isIpAddressUrl(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return IPV4_REGEX.matches(host)
    }

    fun isInternalToExternalSwitch(currentUrl: String?, newUrl: String): Boolean =
        currentUrl != null && isInternalUrl(currentUrl) && isExternalUrl(newUrl)

    fun isExternalToInternalSwitch(currentUrl: String?, newUrl: String): Boolean =
        currentUrl != null && isExternalUrl(currentUrl) && isInternalUrl(newUrl)

    fun isSignificantModeSwitch(currentUrl: String?, newUrl: String): Boolean =
        isInternalToExternalSwitch(currentUrl, newUrl) || isExternalToInternalSwitch(currentUrl, newUrl)
}
