package com.asksakis.freegate.ha

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for Home Assistant tokens, one entry per HA origin.
 *
 * The tokens are keyed by origin rather than by server profile, because they belong to
 * the Home Assistant instance: two profiles that point at the same HA share one sign-in.
 *
 * Unlike [com.asksakis.freegate.auth.CredentialsStore], there is no plain-text fallback.
 * An HA refresh token gives access to the whole Home Assistant instance for months, so
 * when the encrypted prefs cannot be opened nothing is stored and HA ingress stays
 * unavailable on that device. The file is excluded from backup and device transfer.
 */
class HaTokenStore private constructor(context: Context) {

    private val prefs: SharedPreferences? = openPrefs(context.applicationContext)

    fun get(origin: String): HaTokens? = HaTokens.parse(prefs?.getString(key(origin), null))

    /**
     * Store [tokens] under the origin of their `hassUrl`. Returns true when the stored
     * value changed, so callers can log a new sign-in once instead of on every page load.
     */
    fun put(tokens: HaTokens): Boolean {
        val store = prefs ?: return false
        val origin = HaPanelUrl.originOf(tokens.hassUrl) ?: return false
        if (get(origin) == tokens) return false
        store.edit().putString(key(origin), tokens.toJson()).apply()
        return true
    }

    fun remove(origin: String) {
        prefs?.edit()?.remove(key(origin))?.apply()
    }

    private fun key(origin: String) = KEY_PREFIX + origin

    companion object {
        private const val TAG = "HaTokenStore"
        private const val PREFS_FILE = "ha_tokens"
        private const val KEY_PREFIX = "tokens:"

        @Volatile
        private var INSTANCE: HaTokenStore? = null

        fun getInstance(context: Context): HaTokenStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: HaTokenStore(context).also { INSTANCE = it }
            }

        private fun openPrefs(context: Context): SharedPreferences? = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Encrypted prefs unavailable, Home Assistant tokens will not be stored: ${e.message}")
            null
        }
    }
}
