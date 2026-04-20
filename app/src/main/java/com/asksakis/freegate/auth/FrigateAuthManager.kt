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
    @Volatile private var tokenIssuedAtMs: Long = 0L

    /**
     * Ensure a fresh token exists for [baseUrl]. Returns true on success. Forces a
     * fresh login if [force] is set (e.g. after a 401). Safe to call from any thread.
     */
    suspend fun ensureLoggedIn(baseUrl: String, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            if (!credentials.hasCredentials()) {
                Log.w(TAG, "No credentials configured; skipping login")
                return@withContext false
            }
            mutex.withLock {
                if (!force && isCurrentlyFresh()) return@withLock true
                runCatching { performLogin(baseUrl) }
                    .onSuccess { token ->
                        cachedToken = token
                        tokenIssuedAtMs = System.currentTimeMillis()
                        installCookie(baseUrl, token)
                    }
                    .onFailure { e ->
                        Log.e(TAG, "Frigate login failed: ${e.message}")
                    }
                    .isSuccess
            }
        }

    /** Cookie header value for out-of-band HTTP/WS calls. Null if not logged in. */
    fun getCookieHeader(): String? = cachedToken?.let { "frigate_token=$it" }

    /** Clear any cached token. The next consumer that needs auth will re-login. */
    fun invalidate() {
        cachedToken = null
        tokenIssuedAtMs = 0L
    }

    private fun isCurrentlyFresh(): Boolean {
        if (cachedToken == null) return false
        val age = System.currentTimeMillis() - tokenIssuedAtMs
        return age < TOKEN_REFRESH_AFTER_MS
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
            if (!response.isSuccessful) {
                throw IllegalStateException("Login HTTP ${response.code}")
            }
            val setCookie = response.headers("Set-Cookie")
            val token = setCookie
                .firstNotNullOfOrNull { parseCookieValue(it, "frigate_token") }
                ?: throw IllegalStateException("No frigate_token in Set-Cookie")
            return token
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
        cm.setCookie(baseUrl, "frigate_token=$token; Path=/")
        cm.flush()
        Log.d(TAG, "Installed frigate_token into WebView CookieManager for $baseUrl")
    }

    companion object {
        private const val TAG = "FrigateAuthManager"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Refresh the token proactively once a day. Frigate defaults to 24h. */
        private val TOKEN_REFRESH_AFTER_MS = TimeUnit.HOURS.toMillis(20)

        @Volatile
        private var INSTANCE: FrigateAuthManager? = null

        fun getInstance(context: Context): FrigateAuthManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: FrigateAuthManager(context).also { INSTANCE = it }
            }
    }
}
