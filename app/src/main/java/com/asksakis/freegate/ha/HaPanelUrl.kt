package com.asksakis.freegate.ha

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A server URL that points at the Home Assistant panel of an add-on, for example
 * `https://ha.example.com/ccab4aaf_frigate-proxy`. Home Assistant serves its own frontend
 * on that path, and the add-on itself is only reachable through ingress (issue #25).
 *
 * The shape of the URL alone does not prove that the server is Home Assistant: a reverse
 * proxy can serve Frigate under a path such as `/my_frigate`. The app treats a URL as an
 * HA panel only when it also holds Home Assistant tokens for its origin, which exist only
 * after the user signed in on the HA login page.
 *
 * Parsing uses OkHttp's [HttpUrl], which accepts host names that `java.net.URI` rejects,
 * such as `home_assistant`.
 */
data class HaPanelUrl(
    /** `scheme://host[:port]`, in the same form Home Assistant writes `hassUrl`. */
    val origin: String,
    /** The add-on slug, which is the panel path. */
    val slug: String,
) {
    companion object {
        /**
         * Supervisor add-on slugs are a repository prefix and the add-on name joined by an
         * underscore: `ccab4aaf_frigate`, `core_nginx_proxy`, `local_frigate`.
         */
        private val SLUG = Regex("^[a-z0-9]+_[a-z0-9_-]+$")

        fun parse(url: String?): HaPanelUrl? {
            val httpUrl = parseHttpUrl(url) ?: return null
            val segments = httpUrl.encodedPathSegments.filter { it.isNotEmpty() }
            val slug = segments.singleOrNull()?.takeIf { SLUG.matches(it) } ?: return null
            return HaPanelUrl(originOf(httpUrl), slug)
        }

        /** The origin of any http or https URL, or null when the URL cannot be parsed. */
        fun originOf(url: String?): String? = parseHttpUrl(url)?.let(::originOf)

        private fun parseHttpUrl(url: String?): HttpUrl? =
            url?.trim()?.takeIf { it.isNotEmpty() }?.toHttpUrlOrNull()

        private fun originOf(url: HttpUrl): String {
            // HttpUrl drops the brackets of an IPv6 literal, which an origin needs.
            val host = if (url.host.contains(':')) "[${url.host}]" else url.host
            val defaultPort = HttpUrl.defaultPort(url.scheme)
            return if (url.port == defaultPort) {
                "${url.scheme}://$host"
            } else {
                "${url.scheme}://$host:${url.port}"
            }
        }
    }
}
