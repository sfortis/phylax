package com.asksakis.freegate.notifications

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmSoundPlayerTest {

    @Test
    fun `preference off keeps the always-ring behaviour`() {
        assertTrue(AlarmSoundPlayer.ringerAllowsSound(AudioManager.RINGER_MODE_NORMAL, false))
        assertTrue(AlarmSoundPlayer.ringerAllowsSound(AudioManager.RINGER_MODE_VIBRATE, false))
        assertTrue(AlarmSoundPlayer.ringerAllowsSound(AudioManager.RINGER_MODE_SILENT, false))
    }

    @Test
    fun `preference on follows the ringer mode`() {
        assertTrue(AlarmSoundPlayer.ringerAllowsSound(AudioManager.RINGER_MODE_NORMAL, true))
        assertFalse(AlarmSoundPlayer.ringerAllowsSound(AudioManager.RINGER_MODE_VIBRATE, true))
        assertFalse(AlarmSoundPlayer.ringerAllowsSound(AudioManager.RINGER_MODE_SILENT, true))
    }
}
