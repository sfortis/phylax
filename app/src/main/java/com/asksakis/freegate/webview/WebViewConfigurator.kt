package com.asksakis.freegate.webview

import android.content.SharedPreferences
import android.os.Build
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import com.asksakis.freegate.BuildConfig

/**
 * Applies the canonical Phylax [WebSettings] to a WebView.
 *
 * Zoom is disabled at the Android level; a page-finished JS injection additionally
 * forces the viewport meta tag to user-scalable=no because Frigate's own viewport
 * meta otherwise re-enables pinch-zoom despite [WebSettings.setSupportZoom].
 *
 * Mixed-content policy starts in compatibility mode; callers flip it per-URL based
 * on whether the loaded page is internal (via `HomeFragment.applyMixedContentModeFor`).
 */
object WebViewConfigurator {

    fun apply(webView: WebView, prefs: SharedPreferences) {
        val s = webView.settings

        s.javaScriptEnabled = prefs.getBoolean("enable_javascript", true)
        s.domStorageEnabled = prefs.getBoolean("enable_dom_storage", true)
        s.cacheMode = WebSettings.LOAD_DEFAULT

        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true

        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        s.allowFileAccess = false
        s.allowContentAccess = true
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        s.allowFileAccessFromFileURLs = false

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        s.userAgentString = resolveUserAgent(prefs)

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    /**
     * Apply the user agent the current settings ask for. Returns true when it changed,
     * so the caller reloads the page: Frigate picks its layout when the page loads.
     */
    fun refreshUserAgent(webView: WebView, prefs: SharedPreferences): Boolean {
        val wanted = resolveUserAgent(prefs)
        if (webView.settings.userAgentString == wanted) return false
        webView.settings.userAgentString = wanted
        return true
    }

    /**
     * Frigate chooses its live layout from the user agent (react-device-detect), not from
     * the screen size. The phone user agent gets the one-camera-per-row phone layout,
     * which is cut off in landscape and on a TV (issues #37 and #13).
     *
     * The tablet user agent puts the word `Tablet` where the device model goes and leaves
     * out `Mobile`. ua-parser-js then classifies it as a tablet from an unidentified
     * vendor. Removing `Mobile` alone is not enough: a Samsung phone model such as
     * `SM-S938B` is classified as a phone from the model string.
     */
    private fun resolveUserAgent(prefs: SharedPreferences): String =
        if (prefs.getBoolean(PREF_TABLET_LAYOUT, TABLET_LAYOUT_DEFAULT)) {
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; Tablet) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"
        } else {
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Mobile Safari/537.36"
        }

    const val PREF_TABLET_LAYOUT = "tablet_layout"

    /** On by default since issue #37; must match the default in prefs_advanced.xml. */
    private const val TABLET_LAYOUT_DEFAULT = true
}
