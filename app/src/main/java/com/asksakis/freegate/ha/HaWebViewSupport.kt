package com.asksakis.freegate.ha

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Makes the WebView show only Frigate when the server URL is a Home Assistant panel URL.
 *
 * The app loads URLs from many places, so instead of changing each of them this class
 * answers the main-frame request for a panel URL from `shouldInterceptRequest`. It sets
 * the `ingress_session` cookie and returns a small page that replaces itself with the
 * same location under the ingress path. Without Home Assistant tokens it returns null,
 * and the HA frontend loads as before, which is how the user signs in the first time.
 */
object HaWebViewSupport {

    private const val TAG = "HaWebViewSupport"

    /** Call from `shouldInterceptRequest`, which runs on a background thread. */
    fun interceptMainFrame(context: Context, request: WebResourceRequest): WebResourceResponse? {
        if (!request.isForMainFrame || request.method != "GET") return null
        val target = webViewTarget(context, request.url.toString()) ?: return null
        installCookie(target)
        Log.d(TAG, "Opening Frigate through ingress on ${target.cookieUrl}")
        val quoted = JSONObject.quote(target.url)
        // The fragment (a camera's live view, `#<camera>`) never reaches this request, but
        // the redirect page is served at the full address, so it carries it over.
        val html = "<!doctype html><meta charset=\"utf-8\"><script>location.replace($quoted + location.hash)</script>"
        return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
    }

    /**
     * Give the WebView a valid session cookie again, for example when the app returns from
     * the background after the Supervisor's 15-minute window. Blocking.
     */
    fun refreshCookie(context: Context, configuredUrl: String?) {
        val url = configuredUrl ?: return
        if (HaPanelUrl.parse(url) == null) return
        webViewTarget(context, url)?.let(::installCookie)
    }

    /** True when [url] is a page under a configured HA panel path (the HA frontend, not ingress). */
    fun isPanelPage(context: Context, url: String): Boolean {
        val httpUrl = url.toHttpUrlOrNull() ?: return false
        return HaIngressInterceptor.panelFor(httpUrl, HaIngress.configuredPanels(context))
            ?.let { HaIngressInterceptor.isPanelPath(httpUrl, it) } == true
    }

    /** What the WebView loads instead of an HA panel URL, and the cookie it must set first. */
    data class WebViewTarget(val url: String, val cookieUrl: String, val cookie: String) {
        override fun toString() = "WebViewTarget(origin=$cookieUrl)"
    }

    /**
     * The ingress URL for [url] when it lies under a configured HA panel and a session can
     * be created, so the WebView shows only Frigate instead of the whole HA frontend.
     * Returns null otherwise, and the WebView loads [url] unchanged. Blocking: call it off
     * the main thread.
     */
    fun webViewTarget(context: Context, url: String): WebViewTarget? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val (panel, session) = panelSession(context, url) ?: return null
        // Without a fragment: the redirect page appends location.hash, so a fragment here
        // (should a WebView ever pass one) would appear twice.
        val target = HaIngressInterceptor.toIngressUrl(httpUrl, panel, session.ingressPath)
            ?.newBuilder()?.fragment(null)?.build() ?: return null
        // The same attributes the HA frontend uses for this cookie.
        val secure = if (httpUrl.isHttps) "; Secure" else ""
        val cookie = "${HaIngressInterceptor.INGRESS_COOKIE}=${session.session}; " +
            "Path=${HaIngressInterceptor.INGRESS_PATH_PREFIX}; SameSite=Strict$secure"
        return WebViewTarget(target.toString(), panel.origin, cookie)
    }

    private fun panelSession(context: Context, url: String): Pair<HaPanelUrl, HaIngressSessions.IngressSession>? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val origin = HaPanelUrl.originOf(url) ?: return null
        // Only URLs under the panel path, so HA's own pages (the login page) never cost a session.
        val panel = HaIngress.configuredPanels(context)
            .firstOrNull { it.origin == origin && HaIngressInterceptor.isPanelPath(httpUrl, it) }
            ?: return null
        val manager = HaIngress.sessionsIfSignedIn(context) ?: return null
        val session = if (manager.hasCredentials(origin)) manager.sessionFor(panel) else null
        return session?.let { panel to it }
    }

    private fun installCookie(target: WebViewTarget) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie(target.cookieUrl, target.cookie)
            flush()
        }
    }
}
