package com.asksakis.freegate.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetHelpersTest {

    @Test
    fun `cameras are split into at most two balanced rows of three`() {
        assertEquals(emptyList<Int>(), CameraShortcutsWidget.rowSizes(0))
        assertEquals(listOf(1), CameraShortcutsWidget.rowSizes(1))
        assertEquals(listOf(3), CameraShortcutsWidget.rowSizes(3))
        assertEquals(listOf(2, 2), CameraShortcutsWidget.rowSizes(4))
        assertEquals(listOf(3, 2), CameraShortcutsWidget.rowSizes(5))
        assertEquals(listOf(3, 3), CameraShortcutsWidget.rowSizes(6))
        assertEquals(listOf(3, 3), CameraShortcutsWidget.rowSizes(9)) // capped at six
    }

    @Test
    fun `images are scaled to 640 on the long side and never up`() {
        assertEquals(640 to 360, WidgetStore.scaledSize(1920, 1080))
        assertEquals(360 to 640, WidgetStore.scaledSize(1080, 1920))
        assertEquals(320 to 180, WidgetStore.scaledSize(320, 180))
    }

    @Test
    fun `camera names read like the notifications`() {
        assertEquals("Front Door", HomeWidgets.prettyCamera("front_door"))
        assertEquals("Dahua Frontentrance", HomeWidgets.prettyCamera("dahua_frontentrance"))
    }

    @Test
    fun `server status values are formatted compactly`() {
        assertEquals("8m", ServerStatsWidget.formatUptime(8 * 60 + 30))
        assertEquals("5h 12m", ServerStatsWidget.formatUptime(5 * 3600 + 12 * 60))
        assertEquals("3d 4h", ServerStatsWidget.formatUptime(3 * 86400 + 4 * 3600 + 59 * 60))
        assertEquals("23%", ServerStatsWidget.percent(23))
        assertEquals("--", ServerStatsWidget.percent(null))
        assertEquals("9 ms", ServerStatsWidget.inference(8.6))
        assertEquals("--", ServerStatsWidget.inference(null))
    }

    @Test
    fun `refresh slider steps map to 5 minute intervals from 15 minutes to 3 hours`() {
        assertEquals(15, WidgetRefreshWorker.minutesForStep(0))
        assertEquals(20, WidgetRefreshWorker.minutesForStep(1))
        assertEquals(180, WidgetRefreshWorker.minutesForStep(33))
        assertEquals(null, WidgetRefreshWorker.minutesForStep(WidgetRefreshWorker.REFRESH_OFF_STEP))
    }
}
