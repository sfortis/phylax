package com.asksakis.freegate.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * Reusable notification chime player, shared by [DetectionSoundPlayer] and
 * [MotionSoundPlayer]. Reads the user's chosen sound URI from [prefKey] (or falls back to
 * the bundled [fallbackRawRes]); a stored [SILENT_SENTINEL] means "no sound".
 *
 * Routine notifications, so playback uses `USAGE_NOTIFICATION` +
 * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` (no force-max volume, no STREAM_ALARM). DND +
 * notification-volume settings naturally suppress it through the notification stream.
 *
 * Each instance is its own singleton-of-one: back-to-back plays stop and replace the
 * previous MediaPlayer rather than stacking. Two instances (detection / motion) keep
 * independent player + audio-focus state.
 */
class NotificationChimePlayer(
    private val tag: String,
    private val prefKey: String,
    private val fallbackRawRes: Int,
) {

    private val lock = Any()
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    /** Fire-and-forget. Stops any currently-playing chime and starts the new one. */
    fun play(context: Context) {
        synchronized(lock) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            stopLocked()
            audioManager?.let(::releaseAudioFocus)
            val choice = readUserChoice(context) ?: run {
                Log.d(tag, "Sound muted by user (Settings -> Notifications -> Sounds)")
                return
            }
            if (audioManager == null) return
            try {
                acquireAudioFocus(audioManager)
                startPlayback(context.applicationContext, choice)
            } catch (e: Exception) {
                Log.w(tag, "Playback failed: ${e.message}")
                stopLocked()
                releaseAudioFocus(audioManager)
            }
        }
    }

    private sealed interface Choice {
        data object Bundled : Choice
        data class External(val uri: Uri) : Choice
    }

    /** Returns null when the user has explicitly chosen "Silent". */
    private fun readUserChoice(context: Context): Choice? {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(prefKey, null) ?: return Choice.Bundled
        if (raw == SILENT_SENTINEL) return null
        return runCatching { Choice.External(Uri.parse(raw)) }.getOrDefault(Choice.Bundled)
    }

    private fun acquireAudioFocus(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }

    private fun releaseAudioFocus(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun startPlayback(appContext: Context, choice: Choice) {
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        when (choice) {
            is Choice.External -> {
                // A picked ringtone can be uninstalled/deleted later; fall back to bundled.
                runCatching { mp.setDataSource(appContext, choice.uri) }
                    .onFailure {
                        Log.w(tag, "Custom URI ${choice.uri} failed (${it.message}); using bundled tone")
                        setBundledDataSource(mp, appContext)
                    }
            }
            Choice.Bundled -> setBundledDataSource(mp, appContext)
        }
        mp.setOnCompletionListener { stop(appContext) }
        mp.setOnErrorListener { _, what, extra ->
            Log.w(tag, "MediaPlayer error what=$what extra=$extra")
            stop(appContext)
            true
        }
        mp.prepare()
        mp.start()
        player = mp
    }

    private fun setBundledDataSource(mp: MediaPlayer, appContext: Context) {
        appContext.resources.openRawResourceFd(fallbackRawRes).use { afd ->
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        }
    }

    private fun stop(context: Context) {
        synchronized(lock) {
            stopLocked()
            (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?.let(::releaseAudioFocus)
        }
    }

    /** Caller MUST hold [lock]. */
    private fun stopLocked() {
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
        player = null
    }

    companion object {
        const val SILENT_SENTINEL = "silent"
    }
}
