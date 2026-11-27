package com.asksakis.freegate.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentEventsStoreTest {

    private fun event(id: String, timeMs: Long, title: String = "Person") =
        WidgetStore.LastEvent(id, "front_door", title, "Front Door · Driveway", isAlert = true, timeMs = timeMs)

    @Test
    fun `new events go first and the list keeps the ten newest`() {
        var list = emptyList<WidgetStore.LastEvent>()
        (1..12).forEach { list = RecentEventsStore.merge(list, event("r$it", it * 1000L)) }
        assertEquals(RecentEventsStore.MAX_EVENTS, list.size)
        assertEquals("r12", list.first().reviewId)
        assertEquals("r3", list.last().reviewId)
    }

    @Test
    fun `an update of the same review replaces it instead of adding a row`() {
        val list = RecentEventsStore.merge(listOf(event("r1", 1000L)), event("r1", 1000L, title = "Alice"))
        assertEquals(listOf("Alice"), list.map { it.title })
    }

    @Test
    fun `an older event that arrives late is placed by its time`() {
        val list = RecentEventsStore.merge(listOf(event("new", 5000L)), event("old", 1000L))
        assertEquals(listOf("new", "old"), list.map { it.reviewId })
    }

    @Test
    fun `events survive the round trip through storage`() {
        val events = listOf(event("a", 2000L), event("b", 1000L).copy(isAlert = false, subtitle = ""))
        assertEquals(events, RecentEventsStore.decode(RecentEventsStore.encode(events)))
    }

    @Test
    fun `crop keeps the largest centered part with the asked ratio`() {
        assertEquals(640 to 360, WidgetBitmaps.cropSize(640, 480, 16f / 9f))
        assertEquals(480 to 270, WidgetBitmaps.cropSize(480, 640, 16f / 9f))
        assertEquals(360 to 360, WidgetBitmaps.cropSize(640, 360, 1f))
    }
}
