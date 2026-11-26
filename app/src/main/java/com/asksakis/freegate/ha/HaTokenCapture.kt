package com.asksakis.freegate.ha

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import org.json.JSONException
import org.json.JSONTokener
import java.lang.ref.WeakReference

/**
 * Copies the Home Assistant tokens out of the WebView after a page of the configured HA
 * origin has loaded, so that background work can create ingress sessions (issue #25).
 *
 * The HA frontend writes `hassTokens` asynchronously, after it has exchanged the
 * authorization code of the login redirect, and it is a single-page app that fires no
 * further page loads. The read therefore runs at the page load and again after short
 * delays, and stops as soon as tokens are found.
 */
class HaTokenCapture(
    /**
     * Opens the store only once tokens are found. Opening it costs Keystore work on the
     * main thread, which users without a Home Assistant URL should never pay.
     */
    private val storeFor: (Context) -> HaTokenStore = { HaTokenStore.getInstance(it) },
) {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Call from `onPageFinished`. Does nothing unless [configuredUrl] is shaped like an HA
     * panel URL and [pageUrl] belongs to the same origin.
     */
    fun onPageFinished(view: WebView, pageUrl: String?, configuredUrl: String?) {
        val panel = HaPanelUrl.parse(configuredUrl) ?: return
        if (HaPanelUrl.originOf(pageUrl) != panel.origin) return
        handler.removeCallbacksAndMessages(null)
        val viewRef = WeakReference(view)
        RETRY_DELAYS_MS.forEachIndexed { index, delay ->
            val isLast = index == RETRY_DELAYS_MS.lastIndex
            handler.postDelayed({ readTokens(viewRef, panel, isLast) }, delay)
        }
    }

    /** Cancel pending reads, for example when the view is destroyed. */
    fun cancel() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun readTokens(viewRef: WeakReference<WebView>, panel: HaPanelUrl, isLast: Boolean) {
        val view = viewRef.get() ?: return
        if (HaPanelUrl.originOf(view.url) != panel.origin) return
        view.evaluateJavascript(READ_TOKENS_JS) { result ->
            val tokens = HaTokens.parse(decodeJsString(result))
            when {
                tokens == null -> if (isLast) Log.d(TAG, "No hassTokens for ${panel.origin}")
                HaPanelUrl.originOf(tokens.hassUrl) != panel.origin ->
                    Log.w(TAG, "hassTokens belong to another origin; ignored")
                else -> {
                    handler.removeCallbacksAndMessages(null)
                    if (storeFor(view.context).put(tokens)) {
                        Log.d(TAG, "Stored Home Assistant tokens for ${panel.origin}")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "HaTokenCapture"

        /** At the page load, and twice more while the frontend finishes signing in. */
        private val RETRY_DELAYS_MS = longArrayOf(0L, 3_000L, 10_000L)

        private const val READ_TOKENS_JS =
            "(function(){try{return window.localStorage.getItem('hassTokens');}catch(e){return null;}})()"

        /**
         * `evaluateJavascript` hands back the result as a JSON literal: `null`, or a quoted
         * and escaped string. Returns the decoded string, or null.
         */
        internal fun decodeJsString(result: String?): String? {
            if (result.isNullOrEmpty() || result == "null") return null
            return try {
                JSONTokener(result).nextValue() as? String
            } catch (_: JSONException) {
                null
            }
        }
    }
}
