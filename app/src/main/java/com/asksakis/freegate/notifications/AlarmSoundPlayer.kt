package com.asksakis.freegate.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import com.asksakis.freegate.R

/**
 * Plays the bundled alert tone directly through `STREAM_ALARM`, bypassing the
 * NotificationChannel audio path entirely.
 *
 * **Why we can't rely on `setSound()` on the channel:** AOSP says a channel
 * with `setBypassDnd(true)` and `USAGE_ALARM` should ring through `STREAM_ALARM`
 * regardless of ringer mode. Samsung One UI 6/7 has an extra audio policy
 * gate that suppresses notification-originated audio in vibrate ringer mode
 * even when the channel is flagged for DND bypass — observed live in dumpsys
 * with `ringer mode muted streams = 0x126` (no STREAM_ALARM) yet vibrate-only
 * delivery, while the user is in the consolidated DND bypass list. The proven
 * workaround used by Pushover, ntfy and hospital alert SDKs is to leave the
 * channel sound `null` and play the alarm tone manually here.
 *
 * Lifecycle: kept process-singleton so two near-simultaneous alerts can't
 * stack their MediaPlayer instances. Self-releases on completion or error.
 */
object AlarmSoundPlayer {

    private const val TAG = "AlarmSoundPlayer"

    private val lock = Any()
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    /**
     * Fire-and-forget. Stops any currently-playing alert sound (e.g. a fast
     * second alert before the first finishes) and starts the new one.
     */
    fun play(context: Context) {
        synchronized(lock) {
            stopLocked()
            try {
                val audioManager =
                    context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                forceAlarmVolumeMax(audioManager)
                acquireAudioFocus(audioManager)
                startPlayback(context.applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "Alarm playback failed: ${e.message}")
                stopLocked()
            }
        }
    }

    /**
     * Samsung resets STREAM_ALARM volume back to a conservative default after
     * idle periods (observed via dumpsys: Pushover sets it to max before each
     * alert post). Match that so a long-quiet alarm stream doesn't deliver
     * a muted alert sound.
     */
    private fun forceAlarmVolumeMax(audioManager: AudioManager) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        }
    }

    private fun acquireAudioFocus(audioManager: AudioManager) {
        // GAIN_TRANSIENT_MAY_DUCK signals other audio apps to *lower* their
        // volume for the duration of the alert instead of pausing entirely.
        // Music players that respect this hint duck during the ~1.5s alert
        // and bounce back automatically when we release focus. With plain
        // GAIN_TRANSIENT (the original setting), Spotify / system media would
        // pause and not resume — a 3am wake-up alarm is appropriate, but a
        // daytime alert that hard-stops the user's music is not.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
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
                AudioManager.STREAM_ALARM,
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

    private fun startPlayback(appContext: Context) {
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        // openRawResourceFd returns a length-bounded fd; setDataSource expects exactly
        // (fd, startOffset, length) so MediaPlayer doesn't read past the asset boundary.
        appContext.resources.openRawResourceFd(R.raw.alert_tone).use { afd ->
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
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

    /** Public stop entry-point used by the completion/error callbacks. */
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
}
