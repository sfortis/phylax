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
import com.asksakis.freegate.R

/**
 * Plays the detection chime via [MediaPlayer], reading the user's chosen sound
 * from [PREF_DETECTION_SOUND_URI] (or falling back to the bundled
 * `res/raw/detection_tone.ogg`).
 *
 * Detection notifications are routine, so this player intentionally uses
 * `USAGE_NOTIFICATION` and `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` (no force-max
 * volume, no STREAM_ALARM routing). DND + notification-volume settings on the
 * device naturally suppress the playback through the notification stream
 * itself, so we don't need to special-case them here.
 *
 * Lifecycle: process-singleton; back-to-back detections stop and replace the
 * previous MediaPlayer instance instead of stacking.
 */
object DetectionSoundPlayer {

    private const val TAG = "DetectionSoundPlayer"

    private val lock = Any()
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    fun play(context: Context) {
        synchronized(lock) {
            stopLocked()
            val choice = readUserChoice(context)
            if (choice == Choice.Silent) {
                Log.d(TAG, "Detection sound muted by user (Settings → Notifications → Sounds)")
                return
            }
            try {
                val audioManager =
                    context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                acquireAudioFocus(audioManager)
                startPlayback(context.applicationContext, choice)
            } catch (e: Exception) {
                Log.w(TAG, "Detection playback failed: ${e.message}")
                stopLocked()
            }
        }
    }

    private sealed interface Choice {
        data object Bundled : Choice
        data class External(val uri: Uri) : Choice
        data object Silent : Choice
    }

    private fun readUserChoice(context: Context): Choice {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_DETECTION_SOUND_URI, null) ?: return Choice.Bundled
        if (raw == SILENT_SENTINEL) return Choice.Silent
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
                .build()
        )
        when (choice) {
            is Choice.External -> {
                runCatching { mp.setDataSource(appContext, choice.uri) }
                    .onFailure {
                        Log.w(TAG, "Custom detection URI ${choice.uri} failed (${it.message}); using bundled chime")
                        setBundledDataSource(mp, appContext)
                    }
            }
            Choice.Bundled -> setBundledDataSource(mp, appContext)
            Choice.Silent -> return // unreachable; play() already early-returns
        }
        mp.setOnCompletionListener { stop(appContext) }
        mp.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
            stop(appContext)
            true
        }
        mp.prepare()
        mp.start()
        player = mp
    }

    private fun setBundledDataSource(mp: MediaPlayer, appContext: Context) {
        appContext.resources.openRawResourceFd(R.raw.detection_tone).use { afd ->
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

    private fun stopLocked() {
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
        player = null
    }

    /** Same semantics as [AlarmSoundPlayer.PREF_ALERT_SOUND_URI] but for detections. */
    const val PREF_DETECTION_SOUND_URI = "notify_detection_sound_uri"
    const val SILENT_SENTINEL = "silent"
}
