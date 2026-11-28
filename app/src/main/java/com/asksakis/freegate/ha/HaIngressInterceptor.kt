package com.asksakis.freegate.ha

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Routes Frigate requests through Home Assistant ingress when the configured server URL
 * is the HA panel of an add-on (issue #25).
 *
 * Every out-of-band call (notification WebSocket, review catch-up, snapshots, config,
 * stats, login, URL validation) builds its URL from the configured base URL, for example
 * `https://ha.example.com/ccab4aaf_frigate-proxy/ws`. Home Assistant answers that path with
 * its own frontend. This interceptor rewrites such a request to
 * `https://ha.example.com/api/hassio_ingress/<token>/ws` and adds the `ingress_session`
 * cookie, so the callers need no knowledge of Home Assistant. A request that already
 * targets an ingress path, such as a download link taken from the WebView, only gets the
 * cookie.
 *
 * When no Home Assistant sign-in exists for the origin, the request passes unchanged, and
 * the caller sees the same answer as before.
 */
class HaIngressInterceptor(
    private val panels: () -> List<HaPanelUrl>,
    /** Null when no Home Assistant sign-in was ever stored on this device. */
    private val sessions: () -> HaIngressSessions?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = targetOf(request) ?: return chain.proceed(request)
        val response = chain.proceed(withSession(request, target.panel, target.session))
        if (response.code != HTTP_UNAUTHORIZED && response.code != HTTP_UNAVAILABLE) return response

        // 401: the Supervisor no longer knows the session. 503: the ingress token is stale,
        // for example after the add-on was reinstalled. The manager decides whether a new
        // session can help, because Frigate itself can also answer 401.
        val fresh = target.manager.replaceAfterRejection(target.panel, target.session) ?: return response
        response.close()
        return chain.proceed(withSession(request, target.panel, fresh))
    }

    private class Target(
        val panel: HaPanelUrl,
        val manager: HaIngressSessions,
        val session: HaIngressSessions.IngressSession,
    )

    /** The panel and session for [request], or null when the request is not for ingress. */
    private fun targetOf(request: Request): Target? {
        if (request.tag(HaInternalRequest::class.java) != null) return null
        val panel = panelFor(request.url, panels()) ?: return null
        val manager = sessions() ?: return null
        val session = if (manager.hasCredentials(panel.origin)) manager.sessionFor(panel) else null
        return session?.let { Target(panel, manager, it) }
    }

    private fun withSession(request: Request, panel: HaPanelUrl, session: HaIngressSessions.IngressSession): Request {
        val url = toIngressUrl(request.url, panel, session.ingressPath) ?: request.url
        return request.newBuilder()
            .url(url)
            .header("Cookie", mergeCookie(request.header("Cookie"), session.session))
            .build()
    }

    companion object {
        const val INGRESS_COOKIE = "ingress_session"
        const val INGRESS_PATH_PREFIX = "/api/hassio_ingress/"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_UNAVAILABLE = 503

        /**
         * The configured panel [url] belongs to: the one whose slug is the first path
         * segment, or for a URL that already targets an ingress path, the first panel on
         * the same origin. Null for any other URL.
         */
        internal fun panelFor(url: HttpUrl, panels: List<HaPanelUrl>): HaPanelUrl? {
            val origin = HaPanelUrl.originOf(url.toString()) ?: return null
            val sameOrigin = panels.filter { it.origin == origin }
            return sameOrigin.firstOrNull { isPanelPath(url, it) }
                ?: sameOrigin.firstOrNull().takeIf { isIngressPath(url) }
        }

        internal fun isPanelPath(url: HttpUrl, panel: HaPanelUrl): Boolean =
            url.encodedPathSegments.firstOrNull() == panel.slug

        internal fun isIngressPath(url: HttpUrl): Boolean = url.encodedPath.startsWith(INGRESS_PATH_PREFIX)

        /** True when [url] already targets a Home Assistant ingress path. */
        fun isIngressUrl(url: String): Boolean = url.toHttpUrlOrNull()?.let(::isIngressPath) == true

        /**
         * The caller's cookies with [session] as the only `ingress_session`. A download
         * copies the WebView's cookies, which may already hold an older session.
         */
        internal fun mergeCookie(existing: String?, session: String): String =
            (cookiesWithoutSession(existing) + "$INGRESS_COOKIE=$session").joinToString("; ")

        internal fun cookiesWithoutSession(cookie: String?): List<String> = cookie.orEmpty().split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("$INGRESS_COOKIE=") }

        /**
         * Map a URL under the panel path to the same location under [ingressPath]. The query
         * and fragment are kept. Returns null for a URL outside the panel path.
         */
        fun toIngressUrl(url: HttpUrl, panel: HaPanelUrl, ingressPath: String): HttpUrl? {
            if (!isPanelPath(url, panel)) return null
            val rest = url.encodedPathSegments.drop(1).joinToString("/")
            return url.newBuilder().encodedPath(ingressPath + rest).build()
        }
    }
}

/**
 * Network interceptor that keeps the ingress session on the Home Assistant origin.
 *
 * OkHttp follows redirects after the application interceptors ran, and on a redirect to
 * another host it drops only the Authorization header. A Cookie header set by
 * [HaIngressInterceptor] would travel along. This interceptor sees every hop and removes
 * `ingress_session` from any request that does not go to a configured panel origin.
 */
class HaIngressCookieGuard(private val panels: () -> List<HaPanelUrl>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val cookie = request.header("Cookie")
        if (cookie == null || !cookie.contains("${HaIngressInterceptor.INGRESS_COOKIE}=")) {
            return chain.proceed(request)
        }
        val origin = HaPanelUrl.originOf(request.url.toString())
        if (panels().any { it.origin == origin }) return chain.proceed(request)
        val others = HaIngressInterceptor.cookiesWithoutSession(cookie)
        val stripped = request.newBuilder().apply {
            if (others.isEmpty()) removeHeader("Cookie") else header("Cookie", others.joinToString("; "))
        }.build()
        return chain.proceed(stripped)
    }
}
