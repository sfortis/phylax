package com.asksakis.freegate.ha

import org.junit.Assert.assertEquals
import org.junit.Test

class HaTokenStoreTest {

    @Test
    fun `keys of origins no server uses are stale`() {
        val keys = listOf(
            "tokens:http://192.168.1.41:8123",
            "tokens:https://abc.ui.nabu.casa",
            "unrelated",
        )
        val stale = HaTokenStore.staleKeys(keys, setOf("https://abc.ui.nabu.casa"))
        assertEquals(listOf("tokens:http://192.168.1.41:8123"), stale)
    }

    @Test
    fun `every token key is stale when no server is an HA panel`() {
        val keys = listOf("tokens:http://192.168.1.41:8123")
        assertEquals(keys, HaTokenStore.staleKeys(keys, emptySet()))
    }
}
