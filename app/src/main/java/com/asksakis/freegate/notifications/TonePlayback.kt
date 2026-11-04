package com.asksakis.freegate.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

/**
 * Builds a prepared [MediaPlayer] for a notification sound, shared by [AlarmSoundPlayer]
 * and [NotificationChimePlayer].
 *
 * A sound the user picked can fail in two places. `setDataSource` fails when the file or
 * ringtone no longer exists. `prepare` fails when the file exists but cannot be decoded,
 * for example a corrupt file, a DRM protected file or an unsupported codec. Both cases
 * fall back to the bundled tone, so a broken custom sound never turns an alert silent.
 */
internal object TonePlayback {

    /**
     * Returns a player that is prepared and ready to start. Tries [customUri] first when it
     * is not null, and falls back to [fallbackRawRes]. Throws only when the bundled tone
     * itself cannot be prepared. The caller owns the returned player and must release it.
     */
    fun prepare(
        context: Context,
        attributes: AudioAttributes,
        customUri: Uri?,
        fallbackRawRes: Int,
        tag: String,
    ): MediaPlayer {
        if (customUri != null) {
            val custom = MediaPlayer()
            try {
                custom.setAudioAttributes(attributes)
                custom.setDataSource(context, customUri)
                custom.prepare()
                return custom
            } catch (e: Exception) {
                Log.w(tag, "Custom sound $customUri failed (${e.message}); using bundled tone")
                custom.release()
            }
        }
        return prepareBundled(context, attributes, fallbackRawRes)
    }

    private fun prepareBundled(
        context: Context,
        attributes: AudioAttributes,
        rawRes: Int,
    ): MediaPlayer {
        val bundled = MediaPlayer()
        try {
            bundled.setAudioAttributes(attributes)
            // openRawResourceFd returns a length-bounded fd. setDataSource needs exactly
            // (fd, startOffset, length) so MediaPlayer does not read past the asset boundary.
            context.resources.openRawResourceFd(rawRes).use { afd ->
                bundled.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            bundled.prepare()
            return bundled
        } catch (e: Exception) {
            bundled.release()
            throw e
        }
    }
}
