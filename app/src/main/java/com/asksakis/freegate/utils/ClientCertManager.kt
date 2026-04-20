package com.asksakis.freegate.utils

import android.app.Activity
import android.content.Context
import android.security.KeyChain
import android.util.Log
import android.webkit.ClientCertRequest
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
    }

    fun clearAlias() {
        PreferenceManager.getDefaultSharedPreferences(appContext)
            .edit()
            .remove(PREF_CLIENT_CERT_ALIAS)
            .apply()
        OkHttpClientFactory.invalidate()
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
            val privateKey: PrivateKey = KeyChain.getPrivateKey(appContext, alias) ?: return null
            val chain: Array<X509Certificate> = KeyChain.getCertificateChain(appContext, alias) ?: return null

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
            Log.w(TAG, "Failed to load client certificate: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "ClientCertManager"
        const val PREF_CLIENT_CERT_ALIAS = "client_cert_alias"

        @Volatile
        private var INSTANCE: ClientCertManager? = null

        fun getInstance(context: Context): ClientCertManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ClientCertManager(context).also { INSTANCE = it }
            }
    }
}
