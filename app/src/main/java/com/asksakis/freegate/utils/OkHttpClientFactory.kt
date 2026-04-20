package com.asksakis.freegate.utils

import android.content.Context
import androidx.preference.PreferenceManager
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Builds OkHttp clients for Frigate traffic.
 *
 * TLS posture depends on the URL: private/LAN hosts (RFC1918, loopback, link-local,
 * .local/.lan) keep the permissive "trust-all + any-hostname" behavior that lets users
 * connect to self-signed Frigate instances. Public hosts fall back to the platform trust
 * store and standard hostname verification when [strictTls] is set — otherwise we stay
 * permissive because Frigate is commonly exposed with self-signed certs even externally.
 *
 * Client certificates (mTLS) are always applied if configured.
 *
 * **Caching:** one base client is built per unique TLS key and reused across callers.
 * Callers apply their own timeouts/ping intervals via `newBuilder()` on the base,
 * keeping a single shared dispatcher, connection pool, and TLS context.
 */
object OkHttpClientFactory {

    data class Timeouts(
        val connectSeconds: Long = 15,
        val readSeconds: Long = 15,
        val pingSeconds: Long = 0, // 0 = disabled
    )

    private data class CacheKey(val permissiveTls: Boolean, val certAlias: String?)

    private val cache = ConcurrentHashMap<CacheKey, OkHttpClient>()

    /**
     * Build a client for [baseUrl]. [strictTls] explicit override wins; otherwise the user
     * `strict_tls_external` preference decides for public hosts. Private/LAN URLs are
     * always trust-all (self-signed Frigate is the common case there).
     */
    fun build(
        baseUrl: String,
        clientCertManager: ClientCertManager,
        timeouts: Timeouts = Timeouts(),
        strictTls: Boolean? = null,
    ): OkHttpClient {
        val context = clientCertManager.appContext
        val effectiveStrict = strictTls
            ?: PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("strict_tls_external", false)
        val permissiveTls = !(effectiveStrict && !UrlUtils.isPrivateIpUrl(baseUrl))
        val certAlias = clientCertManager.getSavedAlias()
        val base = cache.computeIfAbsent(CacheKey(permissiveTls, certAlias)) {
            buildBase(it, clientCertManager)
        }

        val builder = base.newBuilder()
            .connectTimeout(timeouts.connectSeconds, TimeUnit.SECONDS)
            .readTimeout(timeouts.readSeconds, TimeUnit.SECONDS)
        if (timeouts.pingSeconds > 0) {
            builder.pingInterval(timeouts.pingSeconds, TimeUnit.SECONDS)
        }
        return builder.build()
    }

    /** Drop cached clients. Call when mTLS state (cert alias) changes. */
    fun invalidate() {
        cache.clear()
    }

    private fun buildBase(key: CacheKey, clientCertManager: ClientCertManager): OkHttpClient {
        val builder = OkHttpClient.Builder()
        val keyManagers = clientCertManager.buildKeyManagers()

        if (key.permissiveTls) {
            val trustAll = arrayOf<X509TrustManager>(TrustAllManager)
            val ssl = SSLContext.getInstance("TLS").apply {
                init(keyManagers, trustAll, SecureRandom())
            }
            builder.sslSocketFactory(ssl.socketFactory, trustAll[0])
            builder.hostnameVerifier(HostnameVerifier { _, _ -> true })
        } else if (keyManagers != null) {
            val defaultTm = SystemDefaultTrustManager
            val ssl = SSLContext.getInstance("TLS").apply {
                init(keyManagers, arrayOf(defaultTm), SecureRandom())
            }
            builder.sslSocketFactory(ssl.socketFactory, defaultTm)
        }
        // else: strict + no mTLS → use OkHttp platform defaults (no override).

        return builder.build()
    }

    private object TrustAllManager : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
    }

    private val SystemDefaultTrustManager: X509TrustManager by lazy {
        val factory = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        factory.init(null as java.security.KeyStore?)
        factory.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }
}
