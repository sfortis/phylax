package com.asksakis.freegate.utils

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.KeyChain
import android.util.Log
import android.webkit.ClientCertRequest
import android.webkit.WebView
import androidx.preference.PreferenceManager
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.X509KeyManager

/**
 * Centralized client certificate handling for mTLS.
 *
 * Used from two places:
 *  - WebViewClient.onReceivedClientCertRequest (needs async KeyChain lookup + UI prompt fallback)
 *  - NetworkUtils URL validation (needs KeyManager array for HttpURLConnection's SSLContext)
 *
 * All KeyChain calls block and must run off the main thread.
 */
class ClientCertManager private constructor(context: Context) {

    internal val appContext: Context = context.applicationContext

    /** The alias saved by the user for automatic reuse on subsequent cert requests. */
    fun getSavedAlias(): String? =
        PreferenceManager.getDefaultSharedPreferences(appContext)
            .getString(PREF_CLIENT_CERT_ALIAS, null)

    fun saveAlias(alias: String) {
        PreferenceManager.getDefaultSharedPreferences(appContext)
            .edit()
            .putString(PREF_CLIENT_CERT_ALIAS, alias)
            .apply()
        // Drop cached OkHttp clients so the next call picks up the new mTLS key manager.
        OkHttpClientFactory.invalidate()
        clearWebViewCertDecisions()
    }

    fun clearAlias() {
        PreferenceManager.getDefaultSharedPreferences(appContext)
            .edit()
            .remove(PREF_CLIENT_CERT_ALIAS)
            .apply()
        OkHttpClientFactory.invalidate()
        clearWebViewCertDecisions()
    }

    /**
     * Forget the per-host "do not send a client certificate" decisions the WebView stores
     * whenever a certificate request is cancelled. Those decisions survive restarts and
     * suppress [android.webkit.WebViewClient.onReceivedClientCertRequest] entirely, so a
     * user who once dismissed the picker is never asked again: the certificate they later
     * choose is never presented and the server reports no client certificate at all.
     * Called whenever the saved alias changes, which is the point where the previous
     * decision stops reflecting what the user wants.
     */
    private fun clearWebViewCertDecisions() {
        // Must run on the UI thread; callers reach this from KeyChain callbacks and
        // background threads.
        Handler(Looper.getMainLooper()).post {
            runCatching { WebView.clearClientCertPreferences(null) }
                .onFailure { Log.w(TAG, "Could not clear WebView cert decisions: ${it.message}") }
        }
    }

    /**
     * One-shot repair for installs that were already stuck before the WebView decisions
     * started being cleared on every alias change. Those users have a saved certificate
     * that the WebView refuses to ask for, and no way to recover short of clearing app
     * data, so drop the stored decisions once and record that it happened.
     */
    fun clearStaleCertDecisionsOnce() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        if (prefs.getBoolean(PREF_CERT_DECISIONS_RESET, false)) return
        prefs.edit().putBoolean(PREF_CERT_DECISIONS_RESET, true).apply()
        Log.i(TAG, "Clearing stored WebView client-cert decisions once after upgrade")
        clearWebViewCertDecisions()
    }

    /**
     * Fulfill a WebView client-cert request using the given alias. If the key material
     * can't be loaded (revoked, cleared from KeyChain, etc.) the saved alias is cleared
     * and [onFailure] is called on the main thread so the caller can prompt for a new one.
     */
    fun provideCertificate(
        request: ClientCertRequest?,
        alias: String,
        onFailure: (ClientCertRequest?) -> Unit
    ) {
        Thread {
            try {
                val privateKey: PrivateKey? = KeyChain.getPrivateKey(appContext, alias)
                val chain: Array<X509Certificate>? = KeyChain.getCertificateChain(appContext, alias)

                if (privateKey != null && chain != null) {
                    Log.i(TAG, "Providing client certificate: $alias")
                    request?.proceed(privateKey, chain)
                } else {
                    Log.e(TAG, "Key or chain missing for alias $alias - clearing")
                    clearAlias()
                    onFailure(request)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error providing client certificate - clearing alias", e)
                clearAlias()
                onFailure(request)
            }
        }.start()
    }

    /**
     * Show the system KeyChain picker. If the user selects an alias it is persisted
     * and returned via [onSelected] on the picker's callback thread. If the user
     * cancels, [onSelected] is called with null.
     */
    fun promptForCertificate(
        activity: Activity,
        request: ClientCertRequest?,
        onSelected: (alias: String?) -> Unit
    ) {
        KeyChain.choosePrivateKeyAlias(
            activity,
            { alias ->
                if (alias != null) {
                    Log.i(TAG, "User selected certificate: $alias")
                    saveAlias(alias)
                }
                onSelected(alias)
            },
            request?.keyTypes,
            request?.principals,
            request?.host,
            request?.port ?: -1,
            null
        )
    }

    /**
     * Build [KeyManager]s that serve the user's saved client certificate for mTLS in
     * plain [java.net.HttpURLConnection]-based code paths. Must be called from a
     * background thread — KeyChain lookups block.
     *
     * Returns null if no alias is saved or the key material can't be loaded.
     */
    fun buildKeyManagers(): Array<KeyManager>? {
        val alias = getSavedAlias() ?: return null
        return try {
            // Each miss below is reported separately. Silence here reads in a log exactly
            // like "no certificate configured", which is the one case that is not a fault.
            val privateKey: PrivateKey = KeyChain.getPrivateKey(appContext, alias) ?: run {
                Log.w(TAG, "KeyChain holds no private key for '$alias' right now")
                return null
            }
            val chain: Array<X509Certificate> = KeyChain.getCertificateChain(appContext, alias) ?: run {
                Log.w(TAG, "KeyChain holds no certificate chain for '$alias' right now")
                return null
            }

            val km = object : X509KeyManager {
                override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
                override fun chooseClientAlias(
                    keyType: Array<out String>?,
                    issuers: Array<out Principal>?,
                    socket: Socket?
                ) = alias
                override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
                override fun chooseServerAlias(
                    keyType: String?,
                    issuers: Array<out Principal>?,
                    socket: Socket?
                ) = null
                override fun getCertificateChain(alias: String?) = chain
                override fun getPrivateKey(alias: String?) = privateKey
            }
            arrayOf(km)
        } catch (e: Exception) {
            Log.w(TAG, "Could not load client certificate '$alias': ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "ClientCertManager"
        const val PREF_CLIENT_CERT_ALIAS = "client_cert_alias"
        /** Set once [clearStaleCertDecisionsOnce] has run, so it never repeats. */
        private const val PREF_CERT_DECISIONS_RESET = "client_cert_decisions_reset"

        @Volatile
        private var INSTANCE: ClientCertManager? = null

        fun getInstance(context: Context): ClientCertManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ClientCertManager(context).also { INSTANCE = it }
            }
    }
}
