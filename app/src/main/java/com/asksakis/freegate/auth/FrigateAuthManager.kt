package com.asksakis.freegate.auth

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.asksakis.freegate.utils.ClientCertManager
import com.asksakis.freegate.utils.OkHttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Authenticates against Frigate's `/api/login` and distributes the resulting
 * `frigate_token` cookie to both the Android WebView cookie store (for the WebView UI)
 * and any out-of-band consumer via [getCookieHeader] (for the notification WebSocket).
 *
 * Single point of truth for the session — re-login happens on demand or after a 401.
 */
class FrigateAuthManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val credentials = CredentialsStore.getInstance(appContext)
    private val clientCertManager = ClientCertManager.getInstance(appContext)
    private val mutex = Mutex()

    @Volatile private var cachedToken: String? = null
    @Volatile private var rawSetCookie: String? = null
    @Volatile private var tokenIssuedAtMs: Long = 0L
    @Volatile private var lastLoginFailureMs: Long = 0L

    /**
     * Ensure a fresh token exists for [baseUrl]. Returns true on success. Forces a
     * fresh login if [force] is set (e.g. after a 401). Safe to call from any thread.
     */
    suspend fun ensureLoggedIn(baseUrl: String, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            if (!credentials.hasCredentials()) {
                // No credentials configured: this is either a no-auth Frigate or
                // the user hasn't filled them in yet. Either way, returning false
                // here makes the caller's retry loop block forever — instead
                // return true so the WS / config calls fire WITHOUT a cookie.
                // Frigate without auth answers them; Frigate with auth will 401
                // and surface that to the user via the normal failure path.
                Log.d(TAG, "No credentials configured; proceeding without login")
                return@withContext true
            }
            mutex.withLock {
                if (!force && isCurrentlyFresh()) return@withLock true
                if (!force && isInFailureCooldown()) {
                    // A deployment whose auth is done by a reverse proxy has no working
                    // /api/login, so every caller would otherwise POST it again on its own
                    // cadence: the stats poller alone retried twelve times a minute while
                    // the app was open. The cooldown keeps one attempt per minute; anything
                    // that genuinely warrants an immediate retry (a 401 from a consumer, a
                    // profile swap, edited credentials) clears it via [invalidate].
                    Log.d(TAG, "Login retry suppressed; previous attempt failed recently")
                    return@withLock false
                }
                runCatching { performLogin(baseUrl) }
                    .onSuccess { token ->
                        cachedToken = token
                        tokenIssuedAtMs = System.currentTimeMillis()
                        lastLoginFailureMs = 0L
                        installCookie(baseUrl, token)
                    }
                    .onFailure { e ->
                        lastLoginFailureMs = System.currentTimeMillis()
                        Log.e(TAG, "Frigate login failed: ${e.message}")
                    }
                    .isSuccess
            }
        }

    /**
     * Cookie header value for out-of-band HTTP/WS calls, or null when no session is
     * available at all.
     *
     * The session this class established is preferred. When there is none, and [baseUrl] is
     * given, the `frigate_token` the WebView holds is used instead. A user who signed in on
     * Frigate's own login page inside the app never hands us credentials to log in with, so
     * without this fallback the WebSocket presents nothing, collects 401s and reports
     * "Sign-in needed" while the camera view works perfectly (issue #25).
     *
     * The borrowed cookie expires on Frigate's own schedule and is only refreshed when the
     * user opens the app again, so credentials in Settings remain the only way to keep
     * notifications working through long stretches in the background.
     */
    fun getCookieHeader(baseUrl: String? = null): String? {
        cachedToken?.let { return "frigate_token=$it" }
        val url = baseUrl?.takeIf { it.isNotBlank() } ?: return null
        return webViewToken(url)?.let { "frigate_token=$it" }
    }

    /**
     * Read `frigate_token` out of the WebView cookie store. [CookieManager] hands back the
     * whole `name=value; name=value` line for the host, so the token is picked out of it.
     * Wrapped because the call can throw while the system WebView package is being updated,
     * and a missing cookie is a normal outcome rather than an error.
     */
    private fun webViewToken(baseUrl: String): String? {
        val raw = runCatching { CookieManager.getInstance().getCookie(baseUrl) }
            .onFailure { Log.w(TAG, "WebView cookie store unavailable: ${it.message}") }
            .getOrNull() ?: return null
        return raw.split(';')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(TOKEN_PREFIX) }
            ?.removePrefix(TOKEN_PREFIX)
            ?.takeIf { it.isNotEmpty() }
            ?.also { Log.d(TAG, "Using the WebView session for out-of-band calls") }
    }

    /**
     * Clear any cached token. The next consumer that needs auth will re-login, without
     * waiting out the failure cooldown: every caller of this reacts to something that
     * makes an immediate retry meaningful (a 401 response, a profile swap, credentials
     * the user just edited).
     */
    fun invalidate() {
        cachedToken = null
        tokenIssuedAtMs = 0L
        lastLoginFailureMs = 0L
    }

    private fun isCurrentlyFresh(): Boolean {
        if (cachedToken == null) return false
        val age = System.currentTimeMillis() - tokenIssuedAtMs
        return age < TOKEN_REFRESH_AFTER_MS
    }

    private fun isInFailureCooldown(): Boolean {
        val since = lastLoginFailureMs
        if (since == 0L) return false
        return System.currentTimeMillis() - since < LOGIN_RETRY_COOLDOWN_MS
    }

    private fun performLogin(baseUrl: String): String {
        val loginUrl = loginUrl(baseUrl)
        val body = JSONObject()
            .put("user", credentials.getUsername())
            .put("password", credentials.getPassword())
            .toString()
            .toRequestBody(JSON_MEDIA)

        val client = OkHttpClientFactory.build(baseUrl, clientCertManager)
        val req = Request.Builder()
            .url(loginUrl)
            .post(body)
            .header("User-Agent", "FrigateViewer/1.0 AuthManager")
            .build()

        Log.d(TAG, "POST $loginUrl")
        client.newCall(req).execute().use { response ->
            check(response.isSuccessful) { "Login HTTP ${response.code}" }
            val setCookie = response.headers("Set-Cookie")
            val raw = setCookie.firstOrNull { it.startsWith("frigate_token=") }
                ?: error("No frigate_token in Set-Cookie")
            rawSetCookie = raw
            return parseCookieValue(raw, "frigate_token")
                ?: error("frigate_token Set-Cookie line couldn't be parsed: $raw")
        }
    }

    private fun loginUrl(baseUrl: String): String {
        val normalized = baseUrl.trimEnd('/')
        return "$normalized/api/login"
    }

    private fun parseCookieValue(setCookieLine: String, name: String): String? {
        val prefix = "$name="
        val token = setCookieLine.split(';').firstOrNull()?.trim() ?: return null
        if (!token.startsWith(prefix)) return null
        return token.removePrefix(prefix)
    }

    private fun installCookie(baseUrl: String, token: String) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        // Pass the full Set-Cookie line through so Frigate's own attributes
        // (Max-Age, HttpOnly, SameSite, Secure) survive — re-emitting only
        // `Path=/` strips flags that some Frigate auth-proxy versions check
        // before honouring the cookie on the very next request, which we'd
        // otherwise observe as an immediate redirect back to /login.
        val cookieLine = rawSetCookie ?: "frigate_token=$token; Path=/"
        cm.setCookie(baseUrl, cookieLine)
        cm.flush()
        Log.d(TAG, "Installed frigate_token into WebView CookieManager for $baseUrl (raw=${cookieLine.take(80)}…)")
    }

    companion object {
        private const val TAG = "FrigateAuthManager"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val TOKEN_PREFIX = "frigate_token="

        /** Refresh the token proactively once a day. Frigate defaults to 24h. */
        private val TOKEN_REFRESH_AFTER_MS = TimeUnit.HOURS.toMillis(20)

        /**
         * How long a failed login suppresses the next attempt. One minute matches the
         * WebSocket's own reconnect backoff ceiling, so the listener still retries on
         * roughly every reconnect cycle while high-frequency callers stop hammering an
         * endpoint that has already refused them.
         */
        private val LOGIN_RETRY_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(1)

        @Volatile
        private var INSTANCE: FrigateAuthManager? = null

        fun getInstance(context: Context): FrigateAuthManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: FrigateAuthManager(context).also { INSTANCE = it }
            }
    }
}
