package com.asksakis.freegate.utils

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class TopSwipeRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {
    
    private var initialTouchY = 0f
    private var touchZoneRatio = 0.1f // Top 10% of the WebView
    private var isValidSwipeStart = false
    private var activePointerCount = 0
    
    @Suppress("ReturnCount")
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerCount = 1
                initialTouchY = event.y
                
                // Find the WebView child
                val webView = findWebView(this)
                if (webView != null) {
                    // Calculate touch zone based on WebView dimensions
                    val webViewLocation = IntArray(2)
                    webView.getLocationInWindow(webViewLocation)
                    val webViewTop = webViewLocation[1]
                    val webViewHeight = webView.height
                    val touchZoneHeight = webViewHeight * touchZoneRatio
                    
                    // Check if touch is within the WebView's top 10%
                    val touchRelativeToWebView = event.rawY - webViewTop
                    val isInTouchZone = touchRelativeToWebView in 0f..touchZoneHeight
                    val isWebViewAtTop = webView.scrollY == 0
                    
                    // Mark if this is a valid swipe start
                    isValidSwipeStart = isInTouchZone && isWebViewAtTop && activePointerCount == 1
                    
                    // Only allow refresh if touch starts in the valid zone
                    if (!isValidSwipeStart) {
                        return false
                    }
                } else {
                    // No WebView found, don't allow refresh
                    isValidSwipeStart = false
                    return false
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multiple fingers detected, disable swipe refresh
                activePointerCount++
                isValidSwipeStart = false
                isEnabled = false
                return false
            }
            MotionEvent.ACTION_POINTER_UP -> {
                activePointerCount--
                if (activePointerCount == 1) {
                    isEnabled = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // If multiple fingers or swipe didn't start in the valid zone, don't intercept
                if (!isValidSwipeStart || activePointerCount > 1) {
                    return false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Reset flag when touch ends
                isValidSwipeStart = false
                activePointerCount = 0
                isEnabled = true
            }
        }
        
        // Don't call super if we have multiple pointers
        if (activePointerCount > 1) {
            return false
        }
        
        return super.onInterceptTouchEvent(event)
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // If the swipe didn't start in the valid zone or we have multiple pointers, don't process
        if (!isValidSwipeStart || activePointerCount > 1) {
            return false
        }
        
        // Reset flag when touch ends
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isValidSwipeStart = false
                activePointerCount = 0
                isEnabled = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointerCount++
                isValidSwipeStart = false
                isEnabled = false
                return false
            }
            MotionEvent.ACTION_POINTER_UP -> {
                activePointerCount--
                if (activePointerCount == 1) {
                    isEnabled = true
                }
            }
        }
        
        return super.onTouchEvent(event)
    }
    
    private fun findWebView(parent: android.view.ViewGroup): WebView? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is WebView) {
                return child
            } else if (child is android.view.ViewGroup) {
                val webView = findWebView(child)
                if (webView != null) {
                    return webView
                }
            }
        }
        return null
    }
}
