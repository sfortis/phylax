package com.asksakis.freegate.notifications

import android.content.Context
import com.asksakis.freegate.R

/**
 * Plays the detection chime. Thin wrapper over a shared [NotificationChimePlayer] reading
 * [PREF_DETECTION_SOUND_URI] and falling back to the bundled `res/raw/detection_tone.ogg`.
 */
object DetectionSoundPlayer {

    private val impl = NotificationChimePlayer(
        tag = "DetectionSoundPlayer",
        prefKey = PREF_DETECTION_SOUND_URI,
        fallbackRawRes = R.raw.detection_tone,
    )

    /** Fire-and-forget: stops any current chime and plays the detection sound. */
    fun play(context: Context) = impl.play(context)

    /**
     * SharedPreferences key for the user's detection-sound choice. Stored values:
     *   - missing/null    => default bundled chime
     *   - [SILENT_SENTINEL] => no sound
     *   - any URI string  => that URI is played through STREAM_NOTIFICATION
     */
    const val PREF_DETECTION_SOUND_URI = "notify_detection_sound_uri"
    const val SILENT_SENTINEL = NotificationChimePlayer.SILENT_SENTINEL
}
