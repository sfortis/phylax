package com.asksakis.freegate.utils

/**
 * Utility object for URL type detection and manipulation.
 * Centralizes the logic for determining internal vs external URLs.
 */
object UrlUtils {

    // Regex pattern for IP addresses (both http and https)
    private val IP_ADDRESS_PATTERN = Regex("https?://\\d+\\.\\d+\\.\\d+\\.\\d+.*")

    // Regex pattern for private IP ranges
    private val PRIVATE_IP_PATTERN = Regex("https?://(192\\.168|10\\.|172\\.(1[6-9]|2[0-9]|3[0-1]))\\..+")

    /**
     * Checks if a URL is an internal/local URL.
     * Internal URLs include:
     * - .local domains (mDNS)
     * - .lan domains
     * - IP addresses (both http and https)
     * - http:// URLs (non-secure, typically internal)
     */
    fun isInternalUrl(url: String): Boolean {
        return url.contains(".local") ||
               url.contains(".lan") ||
               !url.contains("https://") ||
               url.matches(IP_ADDRESS_PATTERN)
    }

    /**
     * Checks if a URL is an external URL.
     * External URLs are https:// URLs that are not internal.
     */
    fun isExternalUrl(url: String): Boolean {
        return url.startsWith("https://") && !isInternalUrl(url)
    }

    /**
     * Checks if a URL points to a private IP range.
     * Useful for determining if SSL validation can be relaxed.
     */
    fun isPrivateIpUrl(url: String): Boolean {
        return url.matches(PRIVATE_IP_PATTERN) ||
               url.contains(".local") ||
               url.contains(".lan")
    }

    /**
     * Checks if a URL is an IP address URL (not a domain).
     */
    fun isIpAddressUrl(url: String): Boolean {
        return url.matches(IP_ADDRESS_PATTERN)
    }

    /**
     * Detects if transitioning from current URL to new URL represents
     * an internal to external switch.
     */
    fun isInternalToExternalSwitch(currentUrl: String?, newUrl: String): Boolean {
        if (currentUrl == null) return false
        return isInternalUrl(currentUrl) && isExternalUrl(newUrl)
    }

    /**
     * Detects if transitioning from current URL to new URL represents
     * an external to internal switch.
     */
    fun isExternalToInternalSwitch(currentUrl: String?, newUrl: String): Boolean {
        if (currentUrl == null) return false
        return isExternalUrl(currentUrl) && isInternalUrl(newUrl)
    }

    /**
     * Detects if transitioning between URLs represents any significant
     * mode switch (internal <-> external).
     */
    fun isSignificantModeSwitch(currentUrl: String?, newUrl: String): Boolean {
        return isInternalToExternalSwitch(currentUrl, newUrl) ||
               isExternalToInternalSwitch(currentUrl, newUrl)
    }
}
