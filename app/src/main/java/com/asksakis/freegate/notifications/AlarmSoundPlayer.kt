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
 *
 * **Volume policy:** never touch `setStreamVolume(STREAM_ALARM, …)`. Samsung
 * One UI links STREAM_ALARM with STREAM_MUSIC under several "linked volume"
 * settings, so any nudge we make to the alarm volume yanks the user's media
 * playback up at the same time — a previous "force-max-on-each-alert" pass
 * blasted Spotify at full volume in the field. The trade-off: an alert
 * arriving while the alarm volume slider is at 0 is silent. That's an
 * acceptable consequence of respecting the user's explicit volume setting.
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
            val audioManager =
                context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            // Stop the previous player AND release its focus before we kick off
            // a new playback — otherwise a fast second alert would leave the
            // first alert's audio focus request orphaned. stopLocked alone
            // doesn't release focus because it has no AudioManager handle.
            stopLocked()
            audioManager?.let(::releaseAudioFocus)
            val choice = readUserChoice(context) ?: run {
                Log.d(TAG, "Alert sound muted by user (Settings → Notifications → Sounds)")
                return
            }
            if (audioManager == null) return
            try {
                acquireAudioFocus(audioManager)
                startPlayback(context.applicationContext, choice)
            } catch (e: Exception) {
                Log.w(TAG, "Alarm playback failed: ${e.message}")
                stopLocked()
                releaseAudioFocus(audioManager)
            }
        }
    }

    /**
     * Two playable states for the alert sound. "Silent" is modelled as a `null`
     * return from [readUserChoice] so the type system keeps it from leaking
     * into [startPlayback] (which would otherwise allocate an unused MediaPlayer).
     *   - [Choice.Bundled]   default; play `res/raw/alert_tone.ogg`
     *   - [Choice.External]  user picked a ringtone — play that URI
     */
    private sealed interface Choice {
        data object Bundled : Choice
        data class External(val uri: Uri) : Choice
    }

    /** Returns null when the user has explicitly chosen "Silent". */
    private fun readUserChoice(context: Context): Choice? {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_ALERT_SOUND_URI, null) ?: return Choice.Bundled
        if (raw == SILENT_SENTINEL) return null
        return runCatching { Choice.External(Uri.parse(raw)) }.getOrDefault(Choice.Bundled)
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
