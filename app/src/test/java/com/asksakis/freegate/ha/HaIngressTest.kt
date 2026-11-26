package com.asksakis.freegate.ha

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HaIngressTest {

    private lateinit var ha: FakeHomeAssistant
    private lateinit var tokens: FakeTokenSource
    private var now = 1_000_000L
    private val plainClient = OkHttpClient()

    private val panel get() = HaPanelUrl(ha.origin, FakeHomeAssistant.SLUG)

    private fun sessions() = HaIngressSessions(
        tokens = tokens,
        authClientFor = { HaAuthClient(plainClient) },
        webSocketClientFor = { HaWebSocketClient(plainClient, timeoutMs = 5_000) },
        clock = { now },
    )

    @Before
    fun setUp() {
        ha = FakeHomeAssistant()
        tokens = FakeTokenSource()
    }

    @After
    fun tearDown() {
        ha.close()
    }

    // HaAuthClient

    @Test
    fun `refresh returns a new access token`() {
        val result = HaAuthClient(plainClient).refresh(ha.tokens())
        assertEquals(HaAuthClient.Result.Success(FakeHomeAssistant.REFRESHED_ACCESS_TOKEN, 1800), result)
    }

    @Test
    fun `revoked refresh token is reported as invalid grant`() {
        ha.refreshTokenRevoked = true
        assertEquals(HaAuthClient.Result.InvalidGrant, HaAuthClient(plainClient).refresh(ha.tokens()))
    }

    // HaWebSocketClient

    @Test
    fun `dedicated commands give the ingress path and a session`() {
        val result = HaWebSocketClient(plainClient).createSession(
            ha.origin, FakeHomeAssistant.VALID_ACCESS_TOKEN, FakeHomeAssistant.SLUG, needIngressPath = true,
        )
        assertEquals(HaWebSocketClient.Result.Success(FakeHomeAssistant.INGRESS_PATH, "session-1"), result)
        assertEquals(listOf("supervisor/ingress/info", "supervisor/ingress/session"), ha.commandTypes)
    }

    @Test
    fun `older Home Assistant falls back to supervisor api`() {
        ha.legacyOnly = true
        val result = HaWebSocketClient(plainClient).createSession(
            ha.origin, FakeHomeAssistant.VALID_ACCESS_TOKEN, FakeHomeAssistant.SLUG, needIngressPath = true,
        )
        assertEquals(HaWebSocketClient.Result.Success(FakeHomeAssistant.INGRESS_PATH, "session-1"), result)
        assertEquals(
            listOf(
                "supervisor/ingress/info",
                "supervisor/api /addons/${FakeHomeAssistant.SLUG}/info",
                "supervisor/ingress/session",
                "supervisor/api /ingress/session",
            ),
            ha.commandTypes,
        )
    }

    @Test
    fun `rejected access token is reported`() {
        val result = HaWebSocketClient(plainClient).createSession(ha.origin, "wrong", FakeHomeAssistant.SLUG, true)
        assertEquals(HaWebSocketClient.Result.AuthInvalid, result)
    }

    @Test
    fun `ingress path is normalized and websocket URL derived from the origin`() {
        assertEquals("/api/hassio_ingress/abc/", HaWebSocketClient.normalizeIngressPath("api/hassio_ingress/abc"))
        assertEquals("wss://ha.example.com/api/websocket", HaWebSocketClient.webSocketUrl("https://ha.example.com"))
        assertEquals("ws://10.0.0.2:8123/api/websocket", HaWebSocketClient.webSocketUrl("http://10.0.0.2:8123"))
    }

    // HaIngressSessions

    @Test
    fun `expired access token is refreshed and saved before the session is created`() {
        tokens.save(ha.tokens(accessToken = "old", expiresAtMs = now - 1))
        val session = sessions().sessionFor(panel)
        assertEquals(HaIngressSessions.IngressSession(FakeHomeAssistant.INGRESS_PATH, "session-1"), session)
        assertEquals(1, ha.refreshCount.get())
        assertEquals(FakeHomeAssistant.REFRESHED_ACCESS_TOKEN, tokens.stored[ha.origin]?.accessToken)
    }

    @Test
    fun `session is reused inside the window and renewed after it`() {
        tokens.save(ha.tokens())
        val manager = sessions()
        val first = manager.sessionFor(panel)
        now += HaIngressSessions.SESSION_REUSE_MS - 1
        assertEquals(first, manager.sessionFor(panel))
        now += HaIngressSessions.SESSION_REUSE_MS
        val renewed = manager.sessionFor(panel)
        assertNotNull(renewed)
        assertNotEquals(first, renewed)
        // Each new session asks for the ingress path again, to notice a reinstalled add-on.
        assertEquals(2, ha.commandTypes.count { it == "supervisor/ingress/info" })
    }

    @Test
    fun `revoked refresh token is deleted`() {
        ha.refreshTokenRevoked = true
        tokens.save(ha.tokens(accessToken = null))
        assertNull(sessions().sessionFor(panel))
        assertNull(tokens.stored[ha.origin])
    }

    @Test
    fun `cached access token revoked early is refreshed once`() {
        tokens.save(ha.tokens(accessToken = "revoked-early"))
        assertNotNull(sessions().sessionFor(panel))
        assertEquals(1, ha.refreshCount.get())
    }

    @Test
    fun `no credentials means no session and a cooldown before the next try`() {
        val manager = sessions()
        assertNull(manager.sessionFor(panel))
        tokens.save(ha.tokens())
        assertNull(manager.sessionFor(panel))
        now += HaIngressSessions.FAILURE_COOLDOWN_MS
        assertNotNull(manager.sessionFor(panel))
    }

    // HaIngressInterceptor

    private fun interceptedClient(manager: HaIngressSessions) = OkHttpClient.Builder()
        .addInterceptor(HaIngressInterceptor(panels = { listOf(panel) }, sessions = { manager }))
        .addNetworkInterceptor(HaIngressCookieGuard { listOf(panel) })
        .build()

    private fun get(client: OkHttpClient, path: String) =
        client.newCall(Request.Builder().url("${ha.origin}/${FakeHomeAssistant.SLUG}/$path").build()).execute()

    @Test
    fun `panel URL is rewritten to ingress with the session cookie`() {
        tokens.save(ha.tokens())
        val client = interceptedClient(sessions())
        val body = client.newCall(
            Request.Builder().url("${ha.origin}/${FakeHomeAssistant.SLUG}/api/version?x=1")
                .header("Cookie", "frigate_token=abc").build(),
        ).execute().use { it.body!!.string() }
        assertEquals("frigate:${FakeHomeAssistant.INGRESS_PATH}api/version?x=1", body)
        val cookie = ha.ingressRequests.single().getHeader("Cookie")
        assertEquals("frigate_token=abc; ingress_session=session-1", cookie)
    }

    @Test
    fun `expired session is replaced after a 401`() {
        tokens.save(ha.tokens())
        val manager = sessions()
        val client = interceptedClient(manager)
        val url = "${ha.origin}/${FakeHomeAssistant.SLUG}/api/stats"
        client.newCall(Request.Builder().url(url).build()).execute().close()
        now += HaIngressSessions.FRESH_SESSION_MS
        ha.validSessions.clear() // the Supervisor forgot the session
        val code = client.newCall(Request.Builder().url(url).build()).execute().use { it.code }
        assertEquals(200, code)
        assertEquals(2, ha.sessionCount.get())
    }

    @Test
    fun `401 from Frigate on a fresh session is not retried`() {
        tokens.save(ha.tokens())
        ha.frigateRejects = true
        val client = interceptedClient(sessions())
        assertEquals(401, get(client, "api/login").use { it.code })
        assertEquals(1, ha.sessionCount.get())
        assertEquals(1, ha.ingressRequests.size)
    }

    @Test
    fun `renewals after rejections are rate limited`() {
        tokens.save(ha.tokens())
        ha.frigateRejects = true
        val client = interceptedClient(sessions())
        get(client, "api/stats").close()
        now += HaIngressSessions.FRESH_SESSION_MS
        get(client, "api/stats").close() // old enough: one renewal
        now += HaIngressSessions.FRESH_SESSION_MS
        get(client, "api/stats").close() // inside the renewal interval: no renewal
        assertEquals(2, ha.sessionCount.get())
    }

    @Test
    fun `reinstalled add-on with a new ingress path is picked up after a 503`() {
        tokens.save(ha.tokens())
        val client = interceptedClient(sessions())
        get(client, "api/version").close()
        ha.currentIngressPath = "/api/hassio_ingress/NEWTOKEN/"
        now += HaIngressSessions.FRESH_SESSION_MS
        val body = get(client, "api/version").use { it.body!!.string() }
        assertEquals("frigate:/api/hassio_ingress/NEWTOKEN/api/version", body)
    }

    @Test
    fun `ingress session cookie does not follow a redirect to another host`() {
        val other = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            other.enqueue(okhttp3.mockwebserver.MockResponse().setBody("elsewhere"))
            tokens.save(ha.tokens())
            ha.redirectTo = other.url("/landing").toString()
            val client = interceptedClient(sessions())
            client.newCall(
                Request.Builder().url("${ha.origin}/${FakeHomeAssistant.SLUG}/x")
                    .header("Cookie", "frigate_token=t").build(),
            ).execute().close()
            assertEquals("frigate_token=t", other.takeRequest().getHeader("Cookie"))
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `panel is chosen by slug when two panels share an origin`() {
        val a = HaPanelUrl("https://ha.example.com", "ccab4aaf_frigate")
        val b = HaPanelUrl("https://ha.example.com", "ccab4aaf_frigate-proxy")
        val panels = listOf(a, b)
        fun panelOf(url: String) = HaIngressInterceptor.panelFor(url.toHttpUrl(), panels)
        assertEquals(b, panelOf("https://ha.example.com/ccab4aaf_frigate-proxy/ws"))
        assertEquals(a, panelOf("https://ha.example.com/api/hassio_ingress/T/x"))
        assertNull(panelOf("https://ha.example.com/lovelace"))
    }

    @Test
    fun `requests outside the panel or without credentials pass unchanged`() {
        val client = interceptedClient(sessions())
        val body = client.newCall(Request.Builder().url("${ha.origin}/${FakeHomeAssistant.SLUG}/ws").build())
            .execute().use { it.body!!.string() }
        assertEquals("<html>Home Assistant</html>", body)
        assertEquals("/${FakeHomeAssistant.SLUG}/ws", ha.server.takeRequest().path)
        tokens.save(ha.tokens())
        val other = client.newCall(Request.Builder().url("${ha.origin}/lovelace").build())
            .execute().use { it.body!!.string() }
        assertEquals("<html>Home Assistant</html>", other)
        assertTrue(ha.ingressRequests.isEmpty())
    }

    @Test
    fun `ingress URL keeps the rest of the path, the query and the fragment`() {
        val p = HaPanelUrl("https://ha.example.com", "ccab4aaf_frigate")
        val ingress = "/api/hassio_ingress/T/"
        fun map(url: String) = HaIngressInterceptor.toIngressUrl(url.toHttpUrl(), p, ingress)?.toString()
        assertEquals("https://ha.example.com/api/hassio_ingress/T/", map("https://ha.example.com/ccab4aaf_frigate"))
        assertEquals("https://ha.example.com/api/hassio_ingress/T/", map("https://ha.example.com/ccab4aaf_frigate/"))
        assertEquals(
            "https://ha.example.com/api/hassio_ingress/T/review?id=1#cam",
            map("https://ha.example.com/ccab4aaf_frigate/review?id=1#cam"),
        )
        assertNull(map("https://ha.example.com/lovelace"))
    }

    @Test
    fun `existing ingress session cookie is replaced, other cookies are kept`() {
        assertEquals("ingress_session=new", HaIngressInterceptor.mergeCookie(null, "new"))
        assertEquals(
            "frigate_token=t; other=1; ingress_session=new",
            HaIngressInterceptor.mergeCookie("frigate_token=t; ingress_session=old; other=1", "new"),
        )
        assertTrue(HaIngressInterceptor.isIngressUrl("https://ha.example.com/api/hassio_ingress/T/exports/a.mp4"))
    }
}
