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

    private fun resolveUserAgent(prefs: SharedPreferences): String {
        val default = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Mobile Safari/537.36"
        return if (prefs.getBoolean("use_custom_user_agent", false)) {
            prefs.getString("custom_user_agent", default) ?: default
        } else {
            default
        }
    }
}
