package com.asksakis.freegate.notifications

import android.content.Context
import com.asksakis.freegate.R

/**
 * Plays the per-camera motion notification sound. Thin wrapper over a shared
 * [NotificationChimePlayer] reading [PREF_MOTION_SOUND_URI], with its own player + audio-
 * focus state so it is independent of the detection sound. Falls back to the bundled
 * `res/raw/detection_tone.ogg` until the user picks a dedicated motion tone.
 */
object MotionSoundPlayer {

    private val impl = NotificationChimePlayer(
        tag = "MotionSoundPlayer",
        prefKey = PREF_MOTION_SOUND_URI,
        fallbackRawRes = R.raw.detection_tone,
    )

    /** Fire-and-forget: stops any current motion chime and plays the motion sound. */
    fun play(context: Context) = impl.play(context)

    /**
     * SharedPreferences key for the user's motion-sound choice. Stored values:
     *   - missing/null    => default bundled chime
     *   - [SILENT_SENTINEL] => no sound
     *   - any URI string  => that URI is played through STREAM_NOTIFICATION
     */
    const val PREF_MOTION_SOUND_URI = "notify_motion_sound_uri"
    const val SILENT_SENTINEL = NotificationChimePlayer.SILENT_SENTINEL
}
