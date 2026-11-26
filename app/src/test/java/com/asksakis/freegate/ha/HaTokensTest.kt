package com.asksakis.freegate.ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HaTokensTest {

    /** The shape the HA frontend writes to localStorage (home-assistant-js-websocket AuthData). */
    private val frontendJson = """
        {"hassUrl":"https://abc.ui.nabu.casa","clientId":"https://abc.ui.nabu.casa/",
         "expires":1764150000000,"refresh_token":"refresh-1","access_token":"access-1",
         "expires_in":1800,"token_type":"Bearer"}
    """.trimIndent()

    @Test
    fun `parses the frontend hassTokens entry`() {
        val tokens = HaTokens.parse(frontendJson)
        assertEquals(
            HaTokens(
                hassUrl = "https://abc.ui.nabu.casa",
                clientId = "https://abc.ui.nabu.casa/",
                refreshToken = "refresh-1",
                accessToken = "access-1",
                accessTokenExpiresAtMs = 1764150000000L,
            ),
            tokens,
        )
    }

    @Test
    fun `stored copy round-trips`() {
        val tokens = HaTokens.parse(frontendJson)!!
        assertEquals(tokens, HaTokens.parse(tokens.toJson()))
    }

    @Test
    fun `null client id survives the round trip`() {
        val tokens = HaTokens("http://ha.local:8123", null, "r", null, 0L)
        assertEquals(tokens, HaTokens.parse(tokens.toJson()))
    }

    @Test
    fun `entries without a refresh token or hassUrl are rejected`() {
        assertNull(HaTokens.parse("""{"hassUrl":"https://a","access_token":"x"}"""))
        assertNull(HaTokens.parse("""{"refresh_token":"r"}"""))
        assertNull(HaTokens.parse("""{"hassUrl":"https://a","refresh_token":null}"""))
        assertNull(HaTokens.parse("""{"hassUrl":null,"refresh_token":"r"}"""))
        assertNull(HaTokens.parse("not json"))
        assertNull(HaTokens.parse(null))
    }

    @Test
    fun `toString never contains the tokens`() {
        val text = HaTokens.parse(frontendJson).toString()
        assertFalse(text.contains("refresh-1"))
        assertFalse(text.contains("access-1"))
    }

    @Test
    fun `evaluateJavascript results are decoded`() {
        assertNull(HaTokenCapture.decodeJsString(null))
        assertNull(HaTokenCapture.decodeJsString("null"))
        assertEquals("""{"a":"b"}""", HaTokenCapture.decodeJsString("\"{\\\"a\\\":\\\"b\\\"}\""))
    }
}
