package com.asksakis.freegate.ui.home

import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.preference.PreferenceManager
import com.asksakis.freegate.utils.NetworkUtils

/**
 * ViewModel for the HomeFragment
 * Simply passes through network information from NetworkUtils
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "HomeViewModel"
    private val networkUtils = NetworkUtils.getInstance(application)

    // Pass through the URL directly from NetworkUtils
    val currentUrl: LiveData<String?> = networkUtils.currentUrl

    /**
     * WebView state saved across fragment view destruction so navigating to Settings
     * and back does not lose the current page, scroll position, or playing media.
     * Fragment recreate destroys the View (and thus the WebView), but this ViewModel
     * survives. onDestroyView stashes the WebView bundle here; onCreateView restores it.
     */
    var savedWebViewState: Bundle? = null

    init {
        Log.d(TAG, "HomeViewModel initialized")
    }
    
    /**
     * Gets the list of home networks from preferences
     */
    fun getHomeNetworks(): Set<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        return prefs.getStringSet("home_wifi_networks", emptySet()) ?: emptySet()
    }
    
    /**
     * Manually triggers a network status refresh
     * This bypasses debounce timers for immediate response
     */
    fun refreshStatus() {
        Log.d(TAG, "Manual refresh triggered - bypassing debounce")
        networkUtils.forceRefresh()
    }
}