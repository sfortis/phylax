package com.asksakis.freegate.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.asksakis.freegate.auth.FrigateAuthManager
import com.asksakis.freegate.utils.ClientCertManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

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
            if (!authManager.ensureLoggedIn(baseUrl)) return@withContext null
            val cookie = authManager.getCookieHeader() ?: return@withContext null
            val url = "${baseUrl.trimEnd('/')}${if (path.startsWith('/')) path else "/$path"}"
            val req = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("User-Agent", "FrigateViewer/1.0 Snapshot")
                .build()
            try {
                buildClient().newCall(req).execute().use { response ->
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

    private fun buildClient(): OkHttpClient {
        val trustAll = arrayOf<X509TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        })
        val ssl = SSLContext.getInstance("TLS").apply {
            init(clientCertManager.buildKeyManagers(), trustAll, java.security.SecureRandom())
        }
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .sslSocketFactory(ssl.socketFactory, trustAll[0])
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .build()
    }

    companion object {
        private const val TAG = "SnapshotDownloader"
    }
}
