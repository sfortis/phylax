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
            // ensureLoggedIn returns true even when no credentials are configured
            // (no-auth Frigate); getCookieHeader is null in that case. Fire the
            // request without a Cookie header — Frigate without auth still
            // serves /api/events/<id>/thumbnail.jpg. Without this guard removal
            // the notification snapshots would silently fail on unauthenticated
            // setups even though the WS and config calls work.
            if (!authManager.ensureLoggedIn(baseUrl)) return@withContext null
            val cookie = authManager.getCookieHeader()
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
