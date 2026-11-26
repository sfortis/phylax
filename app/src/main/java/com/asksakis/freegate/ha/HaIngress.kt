package com.asksakis.freegate.ha

import android.content.Context
import androidx.preference.PreferenceManager
import com.asksakis.freegate.utils.ClientCertManager
import com.asksakis.freegate.utils.OkHttpClientFactory

/**
 * Entry point of the Home Assistant ingress support: the configured panel URLs, the shared
 * [HaIngressSessions] and the OkHttp interceptors. The WebView side is [HaWebViewSupport].
 */
object HaIngress {

    @Volatile
    private var sessions: HaIngressSessions? = null

    fun sessions(context: Context): HaIngressSessions =
        sessions ?: synchronized(this) {
            sessions ?: create(context.applicationContext).also { sessions = it }
        }

    /**
     * The shared sessions, or null when no Home Assistant sign-in was ever stored. Users
     * without Home Assistant therefore never open the encrypted token store.
     */
    fun sessionsIfSignedIn(context: Context): HaIngressSessions? =
        sessions ?: if (HaTokenStore.exists(context)) sessions(context) else null

    /** True when a Home Assistant sign-in is stored for [origin]. */
    fun hasSignIn(context: Context, origin: String): Boolean =
        sessionsIfSignedIn(context)?.hasCredentials(origin) == true

    /**
     * Status text for a Home Assistant panel URL whose WebSocket got a web page instead of
     * Frigate: sign in to HA in the app, or, when a sign-in exists, check the add-on,
     * because ingress then failed for another reason. Null for any other URL.
     */
    fun unreachableApiHint(context: Context, baseUrl: String?): String? {
        val panel = HaPanelUrl.parse(baseUrl) ?: return null
        return if (hasSignIn(context, panel.origin)) {
            "Home Assistant ingress is unavailable. Check the Frigate add-on"
        } else {
            "Open the app and sign in to Home Assistant with \"Keep me logged in\""
        }
    }

    /** The configured server URLs that are shaped like HA panel URLs. Cheap: two pref reads. */
    fun configuredPanels(context: Context): List<HaPanelUrl> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return listOf(PREF_INTERNAL_URL, PREF_EXTERNAL_URL)
            .mapNotNull { HaPanelUrl.parse(prefs.getString(it, null)) }
            .distinct()
    }

    fun interceptor(context: Context): HaIngressInterceptor {
        val appContext = context.applicationContext
        return HaIngressInterceptor(
            panels = { configuredPanels(appContext) },
            sessions = { sessionsIfSignedIn(appContext) },
        )
    }

    fun cookieGuard(context: Context): HaIngressCookieGuard {
        val appContext = context.applicationContext
        return HaIngressCookieGuard { configuredPanels(appContext) }
    }

    private fun create(appContext: Context): HaIngressSessions {
        val certs = ClientCertManager.getInstance(appContext)
        return HaIngressSessions(
            tokens = HaTokenStore.getInstance(appContext).asTokenSource(),
            authClientFor = { origin -> HaAuthClient(OkHttpClientFactory.build(origin, certs)) },
            webSocketClientFor = { origin -> HaWebSocketClient(OkHttpClientFactory.build(origin, certs)) },
        )
    }

    private const val PREF_INTERNAL_URL = "internal_url"
    private const val PREF_EXTERNAL_URL = "external_url"
}
