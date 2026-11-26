package com.asksakis.freegate.utils

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Removes secrets and identifying host names from log lines before the user shares a log
 * archive. The archive is usually attached to a public GitHub issue.
 *
 * Redacted: the Frigate session cookie, Authorization headers, OAuth query parameters
 * (the Home Assistant login redirect logs `code` and `state`), the Home Assistant ingress
 * session and ingress token, and the public host names the user configured (for example
 * a Nabu Casa address). LAN addresses stay, because they help diagnosis and identify no one.
 */
class LogRedactor(hostsToMask: Collection<String>) {

    /**
     * One pattern per host, matched only where the host stands on its own: not as part of
     * a longer host name or word. A longer host goes first, in case one contains another.
     */
    private val hostPatterns = hostsToMask
        .map { it.lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }
        .map { Regex("(?<![A-Za-z0-9.-])" + Regex.escape(it) + "(?![A-Za-z0-9-])", RegexOption.IGNORE_CASE) }

    fun redact(line: String): String {
        var result = line
            .replace(FRIGATE_TOKEN, "frigate_token=<redacted>")
            .replace(BEARER, "Authorization: <redacted>")
            .replace(QUERY_SECRET, "$1<redacted>")
            .replace(INGRESS_SESSION, "$1<redacted>")
            .replace(INGRESS_TOKEN, "$1<redacted>")
        for (pattern in hostPatterns) {
            result = result.replace(pattern, "<host>")
        }
        return result
    }

    companion object {
        private val FRIGATE_TOKEN = Regex("frigate_token=[^;\\s\"]+")
        private val BEARER = Regex("Authorization: [^\\s]+ [^\\s\"]+", RegexOption.IGNORE_CASE)
        private val QUERY_SECRET =
            Regex("([?&](?:code|state|access_token|refresh_token|token)=)[^&\\s#\"]+", RegexOption.IGNORE_CASE)
        private val INGRESS_SESSION = Regex("(ingress_session=)[^;\\s\"&]+")
        private val INGRESS_TOKEN = Regex("(/api/hassio_ingress/)[A-Za-z0-9_-]+")

        /**
         * The hosts of [urls] that are public, which are the ones that identify a user. A
         * host without a dot (`frigate`, `homeassistant`) is a LAN name and stays readable.
         */
        fun publicHostsOf(urls: Collection<String?>): List<String> = urls
            .filterNotNull()
            .filter { it.isNotBlank() && !UrlUtils.isPrivateIpUrl(it) }
            .mapNotNull { it.trim().toHttpUrlOrNull()?.host }
            .filter { it.contains('.') }
    }
}
