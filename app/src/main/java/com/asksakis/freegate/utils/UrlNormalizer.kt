package com.asksakis.freegate.utils

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Normalises and validates a user-entered URL for Frigate's Internal / External
 * preferences. Parse-level validation via OkHttp's [HttpUrl] so we reject genuine
 * garbage (no host, bad port, unsupported scheme) but accept real-world inputs like
 * `frigate.local:5000` or `frigate.example.com/frigate` (reverse-proxy subpath).
 */
object UrlNormalizer {

    data class Result(
        /** Final value to persist. Null when [error] is set. */
        val normalized: String?,
        /** Human-readable reason when the URL can't be accepted. */
        val error: String? = null,
        /** Non-fatal note (e.g. "http on an external URL"). */
        val warning: String? = null,
    )

    /**
     * @param raw whatever the user typed
     * @param external when true, missing scheme defaults to `https://`
     *                 (internal defaults to `http://`)
     */
    fun normalize(raw: String, external: Boolean): Result {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) {
            return Result(normalized = null, error = "URL can't be empty")
        }

        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            external -> "https://$trimmed"
            else -> "http://$trimmed"
        }

        val parsed: HttpUrl = withScheme.toHttpUrlOrNull()
            ?: return Result(normalized = null, error = "Not a valid URL")

        if (parsed.host.isBlank()) {
            return Result(normalized = null, error = "URL is missing a host")
        }

        // HttpUrl.toString() is already a canonical form — lower-cased scheme, no
        // default-port explicit, percent-encoded path. Trim any trailing slash again
        // because HttpUrl may re-introduce one for a bare-host URL.
        val canonical = parsed.toString().trimEnd('/')

        val warning = when {
            external && parsed.scheme == "http" && !UrlUtils.isPrivateIpUrl(canonical) ->
                "External URL uses http:// — traffic will be unencrypted"
            else -> null
        }

        return Result(normalized = canonical, warning = warning)
    }
}
