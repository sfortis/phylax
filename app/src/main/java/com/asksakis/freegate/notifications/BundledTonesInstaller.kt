package com.asksakis.freegate.notifications

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.asksakis.freegate.R

/**
 * Copies the bundled CC0 alert/chime tones into MediaStore so they appear by name
 * in the system "Sound" picker that opens from
 * Settings → Apps → Phylax → Notifications → Frigate alerts → Sound.
 *
 * Without this, the picker only lists system ringtones — there's no way for a
 * user to switch *back* to the Phylax tones once they've changed the channel
 * sound, because raw resources inside the APK aren't visible to the picker.
 *
 * MediaStore inserts are scoped to the app on Android 11+, so the files are
 * removed automatically on uninstall. We never delete them at runtime — once
 * registered, they stay registered until the app is uninstalled.
 */
object BundledTonesInstaller {

    private const val TAG = "BundledTonesInstaller"
    private const val RELATIVE_PATH = "Notifications/Phylax/"

    private data class Tone(
        /**
         * Filename written into MediaStore. Samsung One UI and several other OEM
         * pickers display this verbatim (ignoring TITLE), so we keep it
         * human-readable. AOSP and Pixel strip the `.ogg` extension before
         * showing it, so the result is identical there.
         */
        val fileName: String,
        /** Title shown in pickers that respect MediaStore.Audio.TITLE. */
        val title: String,
        /** Bundled `res/raw/<resId>.ogg` source. */
        val resId: Int,
    )

    private val tones = listOf(
        Tone("Phylax Alert.ogg", "Phylax Alert", R.raw.alert_tone),
        Tone("Phylax Chime.ogg", "Phylax Chime", R.raw.detection_tone),
    )

    /**
     * Idempotent: inserts each tone exactly once. Safe to call from every app
     * launch / service start; existing entries are detected via DISPLAY_NAME
     * lookup and skipped.
     */
    fun installIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return // pre-Q needs WRITE_EXTERNAL_STORAGE; not worth it.
        val resolver = context.contentResolver
        for (tone in tones) {
            if (existsInMediaStore(resolver, tone.fileName)) continue
            runCatching { insert(context, resolver, tone) }
                .onFailure { Log.w(TAG, "Install ${tone.fileName} failed: ${it.message}") }
        }
    }

    private fun existsInMediaStore(resolver: ContentResolver, fileName: String): Boolean {
        val uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        // RELATIVE_PATH stored values include a trailing slash; LIKE keeps the lookup
        // tolerant of platform variations (some OEMs add an extra "private/" prefix).
        val selection =
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(fileName, "%$RELATIVE_PATH%")
        return resolver.query(uri, projection, selection, args, null)?.use { c ->
            c.moveToFirst()
        } ?: false
    }

    private fun insert(context: Context, resolver: ContentResolver, tone: Tone) {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val pending = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, tone.fileName)
            put(MediaStore.Audio.Media.TITLE, tone.title)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/ogg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_PATH)
            // IS_NOTIFICATION makes the file appear in any per-channel "Sound"
            // picker. Both Phylax tones are notification-shaped (sub-2s), so we
            // intentionally don't flag IS_ALARM/IS_RINGTONE — keeps them out of
            // the Clock app's alarm picker and the Phone-app ringtone list.
            put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
            put(MediaStore.Audio.Media.IS_RINGTONE, 0)
            put(MediaStore.Audio.Media.IS_ALARM, 0)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val newUri = resolver.insert(collection, pending) ?: run {
            Log.w(TAG, "MediaStore.insert returned null for ${tone.fileName}")
            return
        }

        try {
            resolver.openOutputStream(newUri)?.use { out ->
                context.resources.openRawResource(tone.resId).use { input ->
                    input.copyTo(out)
                }
            } ?: error("openOutputStream returned null")

            val finalize = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            resolver.update(newUri, finalize, null, null)
            Log.d(TAG, "Installed ${tone.title} → $newUri")
        } catch (e: Exception) {
            // Roll back the half-written entry so a future call retries cleanly
            // instead of finding a 0-byte file via existsInMediaStore.
            runCatching { resolver.delete(newUri, null, null) }
            throw e
        }
    }
}
