package com.asksakis.freegate.notifications

import android.content.Context
import android.util.Log
import com.asksakis.freegate.auth.FrigateAuthManager
import com.asksakis.freegate.utils.ClientCertManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * Lightweight client for `GET /api/config`, used by the Settings UI to populate the
 * camera multi-select filter. Runs on [Dispatchers.IO].
 */
class FrigateConfigFetcher(context: Context) {

    private val appContext = context.applicationContext
    private val authManager = FrigateAuthManager.getInstance(appContext)
    private val clientCertManager = ClientCertManager.getInstance(appContext)

    suspend fun fetchCameraNames(baseUrl: String): List<String> =
        withContext(Dispatchers.IO) {
            if (!authManager.ensureLoggedIn(baseUrl)) {
                Log.w(TAG, "Not logged in; can't fetch config")
                return@withContext emptyList()
            }
            val cookie = authManager.getCookieHeader() ?: return@withContext emptyList()
            val client = buildClient()
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/config")
                .header("Cookie", cookie)
                .header("User-Agent", "FrigateViewer/1.0 ConfigFetcher")
                .build()
            try {
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Config fetch HTTP ${response.code}")
                        return@withContext emptyList()
                    }
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val cameras = JSONObject(body).optJSONObject("cameras") ?: return@withContext emptyList()
                    val names = mutableListOf<String>()
                    val keys = cameras.keys()
                    while (keys.hasNext()) names += keys.next()
                    names.sort()
                    names
                }
            } catch (e: Exception) {
                Log.e(TAG, "Config fetch failed: ${e.message}")
                emptyList()
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
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .sslSocketFactory(ssl.socketFactory, trustAll[0])
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .build()
    }

    companion object {
        private const val TAG = "FrigateConfigFetcher"
    }
}
