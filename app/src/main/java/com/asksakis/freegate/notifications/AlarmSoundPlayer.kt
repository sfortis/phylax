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
            val choice = readUserChoice(context)
            if (choice == Choice.Silent) {
                Log.d(TAG, "Alert sound muted by user (Settings → Notifications → Sounds)")
                return
            }
            try {
                val audioManager =
                    context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                ensureAlarmStreamAudible(audioManager)
                acquireAudioFocus(audioManager)
                startPlayback(context.applicationContext, choice)
            } catch (e: Exception) {
                Log.w(TAG, "Alarm playback failed: ${e.message}")
                stopLocked()
            }
        }
    }

    /**
     * Three legal states for the alert sound:
     *   - [Choice.Bundled]   default; play `res/raw/alert_tone.ogg`
     *   - [Choice.External]  user picked a ringtone — play that URI
     *   - [Choice.Silent]    user explicitly picked "None" — skip everything
     */
    private sealed interface Choice {
        data object Bundled : Choice
        data class External(val uri: Uri) : Choice
        data object Silent : Choice
    }

    private fun readUserChoice(context: Context): Choice {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_ALERT_SOUND_URI, null) ?: return Choice.Bundled
        if (raw == SILENT_SENTINEL) return Choice.Silent
        return runCatching { Choice.External(Uri.parse(raw)) }.getOrDefault(Choice.Bundled)
    }

    /**
     * Make sure the alarm stream can actually be heard, **without overriding
     * the user's existing volume choice**. The earlier "force-max" variant
     * caused a serious side-effect on Samsung One UI: with the "Use one volume
     * for ringtone, notifications and system" / linked-volume sliders enabled,
     * setStreamVolume(STREAM_ALARM, max) yanked STREAM_MUSIC up to max too,
     * so an alert arriving while the user was listening to music blasted
     * Spotify at full volume.
     *
     * Behaviour now: if the alarm stream has been actively muted (volume 0),
     * bump it up to a quiet-but-audible half-max so the user still gets the
     * alert; otherwise leave the user's chosen alarm volume alone.
     */
    private fun ensureAlarmStreamAudible(audioManager: AudioManager) {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        if (current > 0) return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max / 2, 0)
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

    private fun startPlayback(appContext: Context, choice: Choice) {
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        when (choice) {
            is Choice.External -> {
                // System ringtone URIs are content:// references whose resolution
                // can throw if the picked sound was deleted/uninstalled in the
                // meantime — fall back to the bundled tone if so.
                runCatching { mp.setDataSource(appContext, choice.uri) }
                    .onFailure {
                        Log.w(TAG, "Custom alert URI ${choice.uri} failed (${it.message}); using bundled tone")
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
        // openRawResourceFd returns a length-bounded fd; setDataSource expects exactly
        // (fd, startOffset, length) so MediaPlayer doesn't read past the asset boundary.
        appContext.resources.openRawResourceFd(R.raw.alert_tone).use { afd ->
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        }
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

    /**
     * SharedPreferences key for the user's alert-sound choice. Stored values:
     *   - missing/null      => default bundled tone
     *   - "silent"          => no sound, AlarmSoundPlayer skips entirely
     *   - any URI string    => MediaPlayer plays that URI through STREAM_ALARM
     */
    const val PREF_ALERT_SOUND_URI = "notify_alert_sound_uri"
    const val SILENT_SENTINEL = "silent"
}
