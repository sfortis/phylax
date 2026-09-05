package com.asksakis.freegate.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.asksakis.freegate.auth.FrigateAuthManager
import com.asksakis.freegate.utils.ClientCertManager
import com.asksakis.freegate.utils.OkHttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Fetches an event/thumbnail image from Frigate using the authenticated session cookie
 * (and, if needed, the saved mTLS client cert).
 */
class SnapshotDownloader(context: Context) {

    private val appContext = context.applicationContext
    private val authManager = FrigateAuthManager.getInstance(appContext)
    private val clientCertManager = ClientCertManager.getInstance(appContext)

    suspend fun download(baseUrl: String, path: String): Bitmap? =
        withContext(Dispatchers.IO) {
            // The request goes out whether or not a session could be established.
            // ensureLoggedIn returns true when no credentials are configured (no-auth
            // Frigate) and false when a login was attempted but failed, which is the
            // normal case for a deployment whose auth is done by a reverse proxy and
            // that has no working /api/login. Both serve
            // /api/events/<id>/thumbnail.jpg without a cookie, so a failed login must
            // not cost the notification its image. A server that really needs auth
            // answers 401 and the non-2xx branch below handles it.
            if (!authManager.ensureLoggedIn(baseUrl)) {
                Log.d(TAG, "No session; fetching snapshot without a cookie")
            }
            val cookie = authManager.getCookieHeader(baseUrl)
            val url = "${baseUrl.trimEnd('/')}${if (path.startsWith('/')) path else "/$path"}"
            val req = Request.Builder()
                .url(url)
                .apply { if (!cookie.isNullOrEmpty()) header("Cookie", cookie) }
                .header("User-Agent", "FrigateViewer/1.0 Snapshot")
                .build()
            try {
                OkHttpClientFactory.build(
                    baseUrl,
                    clientCertManager,
                    OkHttpClientFactory.Timeouts(connectSeconds = 10, readSeconds = 10),
                ).newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Snapshot HTTP ${response.code} for $url")
                        return@withContext null
                    }
                    response.body?.byteStream()?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Snapshot download failed: ${e.message}")
                null
            }
        }


    companion object {
        private const val TAG = "SnapshotDownloader"
    }
}
