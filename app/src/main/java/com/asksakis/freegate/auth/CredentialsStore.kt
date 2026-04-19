package com.asksakis.freegate.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Storage for the Frigate account credentials. Username lives in default prefs (so the
 * Settings UI can read/write it directly), password lives in EncryptedSharedPreferences
 * backed by the AndroidKeyStore.
 *
 * TODO: androidx.security-crypto has been marked deprecated by Google. Migrate the
 * password to a direct Android Keystore + AES/GCM wrap stored alongside the default
 * SharedPreferences when we next touch this file. No new releases are expected upstream.
 */
class CredentialsStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val defaultPrefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(appContext)

    private val secretPrefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            SECRET_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "EncryptedSharedPreferences init failed, falling back to plain prefs: ${e.message}")
        appContext.getSharedPreferences(SECRET_PREFS_FALLBACK_FILE, Context.MODE_PRIVATE)
    }

    fun getUsername(): String? = defaultPrefs.getString(PREF_USERNAME, null)?.takeIf { it.isNotBlank() }

    fun setUsername(value: String?) {
        defaultPrefs.edit()
            .apply { if (value.isNullOrBlank()) remove(PREF_USERNAME) else putString(PREF_USERNAME, value.trim()) }
            .apply()
    }

    fun getPassword(): String? = secretPrefs.getString(PREF_PASSWORD, null)?.takeIf { it.isNotEmpty() }

    fun setPassword(value: String?) {
        secretPrefs.edit()
            .apply { if (value.isNullOrEmpty()) remove(PREF_PASSWORD) else putString(PREF_PASSWORD, value) }
            .apply()
    }

    fun hasCredentials(): Boolean = !getUsername().isNullOrBlank() && !getPassword().isNullOrEmpty()

    companion object {
        private const val TAG = "CredentialsStore"
        const val PREF_USERNAME = "frigate_username"
        const val PREF_PASSWORD = "frigate_password"
        private const val SECRET_PREFS_FILE = "frigate_secrets"
        private const val SECRET_PREFS_FALLBACK_FILE = "frigate_secrets_fallback"

        @Volatile
        private var INSTANCE: CredentialsStore? = null

        fun getInstance(context: Context): CredentialsStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: CredentialsStore(context).also { INSTANCE = it }
            }
    }
}
