package com.asksakis.freegate.auth

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.asksakis.freegate.notifications.FrigateAlertService
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Support for Frigate published through a Cloudflare Tunnel behind Cloudflare Access,
 * including the "One-time PIN" login method where Cloudflare emails a code to enter.
 *
 * The sign-in itself happens in the WebView: Access redirects the page to
 * `<team>.cloudflareaccess.com`, the user enters their email and the PIN, and Access
 * redirects back through `/cdn-cgi/access/authorized`, which sets the `CF_Authorization`
 * cookie on the Frigate host. The WebView needs nothing more.
 *
 * Everything outside the WebView (the notification WebSocket, stats, snapshots, config,
 * URL validation) goes through OkHttp, which has no cookie store. This object closes that
 * gap in two ways:
 *  - [CookieInterceptor] adds the WebView's `CF_Authorization` to every request hop, read
 *    from [CookieManager] for that hop's URL, so the cookie only ever goes to the host
 *    Cloudflare set it on.
 *  - [LoginRedirectInterceptor] turns Access's redirect to its login page into a 401
 *    marked with [MARKER_HEADER]. Without it a background call would follow the redirect
 *    and receive the login page as a "200 OK" HTML body, and the WebSocket would fail as
 *    a plain network error instead of asking the user to sign in.
 *
 * The Access session lasts as long as the application's session duration in the Cloudflare
 * dashboard (24 hours by default). When it expires the WebView shows the PIN page again
 * the next time the app is opened, and the alert listener reports that a sign-in is needed.
 */
object CloudflareAccess {

    private const val TAG = "CloudflareAccess"

    const val COOKIE_NAME = "CF_Authorization"
    private const val COOKIE_PREFIX = "$COOKIE_NAME="

    /** Set on the synthetic 401 that stands in for a redirect to the Access login page. */
    const val MARKER_HEADER = "X-Phylax-Cloudflare-Access"
    private const val MARKER_VALUE = "login-required"

    private const val ACCESS_LOGIN_DOMAIN = "cloudflareaccess.com"
    private const val ACCESS_LOGIN_PATH = "/cdn-cgi/access/login"

    /**
     * True when the most recent request was sent to the Access login page instead of
     * Frigate. Cleared when a request that carried the Access cookie succeeds.
     */
    @Volatile
    var signInRequired: Boolean = false
        private set

    @Volatile
    private var lastSeenToken: String? = null

    /** The `CF_Authorization` value the WebView holds for [url], or null. */
    fun tokenFor(url: String): String? {
        val raw = runCatching { CookieManager.getInstance().getCookie(url) }
            .onFailure { Log.w(TAG, "WebView cookie store unavailable: ${it.message}") }
            .getOrNull() ?: return null
        return raw.split(';')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(COOKIE_PREFIX) }
            ?.removePrefix(COOKIE_PREFIX)
            ?.takeIf { it.isNotEmpty() }
    }

    /** Whether [url] is Cloudflare Access's own login page (the email / PIN form). */
    fun isLoginPage(url: HttpUrl): Boolean {
        val host = url.host.lowercase()
        return host == ACCESS_LOGIN_DOMAIN ||
            host.endsWith(".$ACCESS_LOGIN_DOMAIN") ||
            url.encodedPath.startsWith(ACCESS_LOGIN_PATH)
    }

    fun isLoginPage(url: String?): Boolean =
        url?.toHttpUrlOrNull()?.let { isLoginPage(it) } ?: false

    /** Whether [response] is the stand-in 401 for an Access login redirect. */
    fun isSignInResponse(response: Response): Boolean =
        response.header(MARKER_HEADER) == MARKER_VALUE

    /**
     * Call when the WebView finishes loading [url]. When a new Access cookie has appeared
     * (the user just entered the emailed PIN), restart the alert listener so it reconnects
     * with the cookie now, rather than staying paused on "sign-in needed" until the next
     * five-minute health check.
     */
    fun onPageLoaded(context: Context, url: String?) {
        if (url.isNullOrBlank() || isLoginPage(url)) return
        val token = tokenFor(url) ?: return
        if (token == lastSeenToken) return
        val hadToken = lastSeenToken != null
        lastSeenToken = token
        signInRequired = false
        Log.i(TAG, if (hadToken) "Cloudflare Access session renewed" else "Cloudflare Access session found")
        FrigateAlertService.updateForContext(context.applicationContext)
    }

    /**
     * Network interceptor: adds `CF_Authorization` to each hop. Running per hop (rather
     * than once per call) means a redirect to another host never carries the cookie with
     * it, because [CookieManager] only returns it for the host it was issued for.
     */
    object CookieInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val token = tokenFor(request.url.toString()) ?: return chain.proceed(request)
            val existing = request.header("Cookie")
            if (existing != null && existing.contains(COOKIE_PREFIX)) return chain.proceed(request)
            val cookie = if (existing.isNullOrBlank()) {
                "$COOKIE_PREFIX$token"
            } else {
                "$existing; $COOKIE_PREFIX$token"
            }
            val response = chain.proceed(request.newBuilder().header("Cookie", cookie).build())
            if (response.isSuccessful) signInRequired = false
            return response
        }
    }

    /**
     * Application interceptor: sees the final response after redirects. If the call ended
     * on the Access login page, or stopped on a redirect pointing at it, answer 401 so the
     * existing auth-failure paths (WebSocket AUTH_REQUIRED, stats backoff) take over.
     */
    object LoginRedirectInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            if (!endedAtLogin(response)) return response

            signInRequired = true
            Log.w(TAG, "Cloudflare Access sign-in required for ${request.url.host}")
            val protocol = response.protocol
            response.close()
            return Response.Builder()
                .request(request)
                .protocol(protocol)
                .code(401)
                .message("Cloudflare Access sign-in required")
                .header(MARKER_HEADER, MARKER_VALUE)
                .body("".toResponseBody(null))
                .build()
        }

        private fun endedAtLogin(response: Response): Boolean {
            if (isLoginPage(response.request.url)) return true
            if (!response.isRedirect) return false
            val location = response.header("Location") ?: return false
            val target = response.request.url.resolve(location) ?: return false
            return isLoginPage(target)
        }
    }
}
