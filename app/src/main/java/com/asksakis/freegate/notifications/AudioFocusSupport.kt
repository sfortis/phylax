package com.asksakis.freegate.notifications

import android.content.Context
import android.media.AudioManager

/**
 * Resolves the [AudioManager] that audio focus is requested and abandoned through.
 *
 * It always goes through the application context, and every focus call site must use it.
 * AudioManager derives the focus client id from its own instance, and `getSystemService`
 * hands out a different instance per [Context]. Focus requested from a service context
 * therefore cannot be abandoned from the MediaPlayer callbacks, which run with the
 * application context: the ids do not match and the system rejects the release. The
 * orphaned `GAIN_TRANSIENT_MAY_DUCK` request then sits in the focus stack and holds every
 * other app's audio at ducked volume until the process dies.
 */
internal fun Context.focusAudioManager(): AudioManager? =
    applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
