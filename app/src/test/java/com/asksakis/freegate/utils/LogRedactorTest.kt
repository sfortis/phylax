package com.asksakis.freegate.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class LogRedactorTest {

    private val redactor = LogRedactor(listOf("abc123.ui.nabu.casa"))

    @Test
    fun `OAuth redirect parameters and the public host are redacted`() {
        val line = "shouldOverrideUrlLoading: https://abc123.ui.nabu.casa/ccab4aaf_frigate-proxy/" +
            "?auth_callback=1&code=d893aa9f47ee&state=eyJoYXNz&storeToken=true"
        assertEquals(
            "shouldOverrideUrlLoading: https://<host>/ccab4aaf_frigate-proxy/" +
                "?auth_callback=1&code=<redacted>&state=<redacted>&storeToken=true",
            redactor.redact(line),
        )
    }

    @Test
    fun `ingress session and token are redacted`() {
        val line = "Opening http://192.168.1.41:8123/api/hassio_ingress/T785Ohoy0VK4j/ " +
            "Cookie: ingress_session=abcdef; x=1"
        assertEquals(
            "Opening http://192.168.1.41:8123/api/hassio_ingress/<redacted>/ " +
                "Cookie: ingress_session=<redacted>; x=1",
            redactor.redact(line),
        )
    }

    @Test
    fun `existing session rules still apply`() {
        assertEquals("frigate_token=<redacted>; Path=/", redactor.redact("frigate_token=eyJhbGci; Path=/"))
        assertEquals("Authorization: <redacted>", redactor.redact("Authorization: Bearer abc.def"))
    }

    @Test
    fun `lines without secrets are unchanged`() {
        val line = "URL validation succeeded: http://192.168.1.220:5000 - Connection successful (200) in 5ms"
        assertEquals(line, redactor.redact(line))
    }

    @Test
    fun `hosts are masked only where they stand on their own`() {
        val line = "Loading https://abc123.ui.nabu.casa/x and https://xabc123.ui.nabu.casa.evil/y"
        assertEquals("Loading https://<host>/x and https://xabc123.ui.nabu.casa.evil/y", redactor.redact(line))
    }

    @Test
    fun `single-label LAN names are not treated as public hosts`() {
        assertEquals(
            listOf("abc123.ui.nabu.casa"),
            LogRedactor.publicHostsOf(
                listOf("http://frigate:5000", "https://abc123.ui.nabu.casa/ccab4aaf_frigate", null),
            ),
        )
    }

    @Test
    fun `lowercase authorization header is redacted`() {
        assertEquals("authorization: <redacted>", redactor.redact("authorization: Bearer abc").lowercase())
    }
}
