package com.asksakis.freegate.ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaPanelUrlTest {

    @Test
    fun `Nabu Casa panel URL gives origin and slug`() {
        val panel = HaPanelUrl.parse("https://abc123.ui.nabu.casa/ccab4aaf_frigate-proxy")
        assertEquals(HaPanelUrl("https://abc123.ui.nabu.casa", "ccab4aaf_frigate-proxy"), panel)
    }

    @Test
    fun `LAN panel URL keeps a non-default port`() {
        val panel = HaPanelUrl.parse("http://192.168.1.41:8123/ccab4aaf_frigate-proxy/")
        assertEquals(HaPanelUrl("http://192.168.1.41:8123", "ccab4aaf_frigate-proxy"), panel)
    }

    @Test
    fun `default port, query and fragment are not part of the panel`() {
        val panel = HaPanelUrl.parse("HTTPS://HA.Example.com:443/core_frigate?x=1#cams")
        assertEquals(HaPanelUrl("https://ha.example.com", "core_frigate"), panel)
    }

    @Test
    fun `plain Frigate URLs are not panel URLs`() {
        assertNull(HaPanelUrl.parse("http://192.168.1.220:5000"))
        assertNull(HaPanelUrl.parse("http://192.168.1.220:5000/"))
        assertNull(HaPanelUrl.parse("https://frigate.example.com/frigate"))
        assertNull(HaPanelUrl.parse("https://ha.example.com/api/hassio_ingress/abc/"))
    }

    @Test
    fun `invalid input is not a panel URL`() {
        assertNull(HaPanelUrl.parse(null))
        assertNull(HaPanelUrl.parse(""))
        assertNull(HaPanelUrl.parse("ftp://ha.example.com/core_frigate"))
        assertNull(HaPanelUrl.parse("not a url"))
    }

    @Test
    fun `origin matches the form of hassUrl`() {
        assertEquals("https://abc.ui.nabu.casa", HaPanelUrl.originOf("https://abc.ui.nabu.casa/"))
        assertEquals("http://192.168.1.41:8123", HaPanelUrl.originOf("http://192.168.1.41:8123/x?y"))
        assertNull(HaPanelUrl.originOf("about:blank"))
    }

    @Test
    fun `host names with an underscore are accepted`() {
        val panel = HaPanelUrl.parse("http://home_assistant:8123/ccab4aaf_frigate")
        assertEquals(HaPanelUrl("http://home_assistant:8123", "ccab4aaf_frigate"), panel)
    }

    @Test
    fun `IPv6 origin keeps its brackets`() {
        val panel = HaPanelUrl.parse("http://[fd00::41]:8123/ccab4aaf_frigate")
        assertEquals(HaPanelUrl("http://[fd00::41]:8123", "ccab4aaf_frigate"), panel)
    }
}
