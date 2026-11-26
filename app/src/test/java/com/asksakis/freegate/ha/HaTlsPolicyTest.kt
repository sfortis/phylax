package com.asksakis.freegate.ha

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaTlsPolicyTest {

    @Test
    fun `public HA addresses with real certificates are verified`() {
        assertTrue(HaTlsPolicy.requiresStrictTls("https://abc123.ui.nabu.casa"))
        assertTrue(HaTlsPolicy.requiresStrictTls("https://myhome.duckdns.org:8123"))
        assertTrue(HaTlsPolicy.requiresStrictTls("https://ha.example.com"))
        assertTrue(HaTlsPolicy.requiresStrictTls("https://homeassistant.tail1234.ts.net"))
        assertTrue(HaTlsPolicy.requiresStrictTls("https://8.8.8.8"))
        assertTrue(HaTlsPolicy.requiresStrictTls("https://100.128.0.1")) // just outside CGNAT
    }

    @Test
    fun `home network and VPN hosts keep the permissive policy`() {
        assertFalse(HaTlsPolicy.requiresStrictTls("https://192.168.1.41:8123"))
        assertFalse(HaTlsPolicy.requiresStrictTls("https://10.200.200.1"))
        assertFalse(HaTlsPolicy.requiresStrictTls("https://172.20.0.5"))
        assertFalse(HaTlsPolicy.requiresStrictTls("https://100.101.102.103")) // Tailscale IPv4
        assertFalse(HaTlsPolicy.requiresStrictTls("https://[fd7a:115c:a1e0::1]")) // Tailscale IPv6
        assertFalse(HaTlsPolicy.requiresStrictTls("https://homeassistant:8123")) // MagicDNS or LAN name
        assertFalse(HaTlsPolicy.requiresStrictTls("https://homeassistant.local:8123"))
        assertFalse(HaTlsPolicy.requiresStrictTls("https://ha.home.arpa"))
    }

    @Test
    fun `plain http has no certificate to verify`() {
        assertFalse(HaTlsPolicy.requiresStrictTls("http://abc123.ui.nabu.casa"))
    }
}
