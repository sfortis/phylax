package com.asksakis.freegate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.preference.PreferenceManager
import com.asksakis.freegate.databinding.ActivityMainBinding
// NetworkFixer has been consolidated into NetworkUtils
import com.asksakis.freegate.utils.NetworkUtils
import com.asksakis.freegate.utils.UpdateChecker
import com.google.android.material.navigation.NavigationView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Extraction target: move stats dialog + toolbar-badge glue to dedicated controllers
// once we migrate away from View bindings. Suppressions are explicit so future growth
// still gets flagged.
@Suppress("LargeClass", "TooManyFunctions")
class MainActivity : AppCompatActivity(),
    com.asksakis.freegate.ui.MuteGroupsBottomSheet.OnMutesChanged {

    private companion object {
        /** How often the connection-status dialog fires an RTT probe while visible. */
        const val STATUS_PROBE_INTERVAL_MS = 1_000L
        /** Load percentage at which metric badge text flips to the warning colour. */
        const val LOAD_WARN_THRESHOLD = 50
        const val LOAD_HOT_THRESHOLD = 75
        /** Period at which we re-evaluate stats-chip staleness even without LiveData ticks. */
        const val STATS_STALE_TICK_MS = 5_000L
        /** Breathing gap beneath the WebView, above the system nav inset (dp). */
        const val EXTRA_BOTTOM_GAP_DP = 12f
    }


    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var networkUtils: NetworkUtils
    // NetworkFixer functionality has been consolidated into NetworkUtils
    private var networkIndicator: TextView? = null
    private var connectionStatusIndicator: TextView? = null
    private var statsCpuIndicator: TextView? = null
    private var statsGpuIndicator: TextView? = null
    private val uiStatsWatcher by lazy {
        com.asksakis.freegate.stats.UiStatsWatcher(applicationContext)
    }
    private val statsStaleHandler by lazy { android.os.Handler(mainLooper) }
    private val statsStaleTick = object : Runnable {
        override fun run() {
            // Re-apply alpha/visibility against the current clock so the chip dims once
            // the last good sample ages past the stale threshold, even if no new
            // LiveData event arrives (poll failures would otherwise freeze it bright).
            renderStatsIndicator(com.asksakis.freegate.stats.FrigateStatsRepository.stats.value)
            statsStaleHandler.postDelayed(this, STATS_STALE_TICK_MS)
        }
    }
    private var pendingDeepLink: Intent? = null

    /** Heartbeat tied to the Activity lifecycle — see [onStop]/[onStart]/[onDestroy]. */
    private val statusHeartbeatHandler by lazy { android.os.Handler(mainLooper) }
    private var statusHeartbeatRunnable: Runnable? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw app content behind transparent system bars. The AppBarLayout carries
        // fitsSystemWindows=true so it grows under the status bar; light status icons
        // are forced regardless of light/dark theme because the toolbar is always dark.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        // Root destination shows the Phylax logo + stats badges + network
        // indicator + mute + settings; an extra app-name title text crowds
        // everything off-screen on narrow devices. Settings/child destinations
        // get their NavigationUI label back through addOnDestinationChangedListener.
        supportActionBar?.title = ""
        applyToolbarTitleStyle()

        // Stash any cold-start deep-link intent; handleIntent is re-run once nav is ready.
        pendingDeepLink = intent


        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // First-run guidance is handled by the Home setup empty state (shown when no
        // usable server is configured), not a toast. The old "Welcome! Configure
        // Frigate URLs in Settings" toast fired before the user could act and pointed
        // at an unsignposted Settings screen, so it was removed.

        // Initialize network utilities (singleton)
        networkUtils = NetworkUtils.getInstance(this)
        
        // Find network indicator
        networkIndicator = findViewById(R.id.network_indicator)
        // INT/EXT is now a read-only label. The reachability status (cloud_done /
        // cloud_sync / cloud_off) lives in its own indicator next to it, and
        // *that* is the one that opens the status dialog. Keeping the dialog on
        // a labelled icon makes the tap target self-documenting.
        connectionStatusIndicator = findViewById(R.id.connection_status_indicator)
        connectionStatusIndicator?.setOnClickListener { showNetworkStatusDialog() }

        // Find Frigate-side stats chips (CPU / GPU — separate badges, distinct tints).
        statsCpuIndicator = findViewById(R.id.stats_cpu_indicator)
        statsGpuIndicator = findViewById(R.id.stats_gpu_indicator)
        val statsTap = android.view.View.OnClickListener { showFrigateStatsDialog() }
        statsCpuIndicator?.setOnClickListener(statsTap)
        statsGpuIndicator?.setOnClickListener(statsTap)
        com.asksakis.freegate.stats.FrigateStatsRepository.stats.observe(this) { stats ->
            renderStatsIndicator(stats)
        }
        
        // Log current permission status
        logPermissionStatus()

        // Runtime permissions are no longer requested at launch. Each one is asked
        // for at its feature boundary, when the user first needs it:
        //   - Microphone: on the first two-way-talk tap (HomeFragment).
        //   - Notifications: when the user enables alerts (NotificationsSettingsFragment).
        //   - Location (SSID): when the user turns on Wi-Fi URL auto-switching
        //     (ConnectionSettingsFragment).
        // A fresh install now opens straight into the setup empty state with zero
        // system prompts stacked in front of it.

        bindNavControllerIfReady()
        if (!::navController.isInitialized) {
            Log.d("MainActivity", "NavHostFragment not ready yet")
        }
        
        // Check for updates on app start
        checkForUpdates()

        // Start the Frigate alert listener if the user has notifications enabled.
        com.asksakis.freegate.notifications.FrigateAlertService.updateForContext(this)

        // Edge-to-edge bottom inset for the WebView area.
        applyBottomSystemBarPadding()

        // Landscape is viewing mode: drop our chrome for a bigger picture.
        applyLandscapeChrome()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Activity declares configChanges=orientation, so it is not recreated on
        // rotation; re-apply the landscape/portrait chrome here.
        applyLandscapeChrome()
    }

    /**
     * In landscape we treat the app as a viewer and hide our own chrome (the status
     * bar and the toolbar) so the WebView gets the full height; portrait restores it.
     * Settings/mute stay reachable by rotating back to portrait, and a swipe from the
     * top edge transiently reveals the status bar. Skipped while an in-page video
     * fullscreen is active - that path manages the bars itself.
     */
    private fun applyLandscapeChrome() {
        if (!::binding.isInitialized) return
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val controller = WindowInsetsControllerCompat(window, binding.root)
        val navHost = findViewById<View>(R.id.nav_host_fragment_content_main)
        val lp = navHost?.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
        if (landscape) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
            binding.appBarLayout.visibility = View.GONE
            // Drop the scrolling-view behavior so the WebView lays out from the top of
            // the CoordinatorLayout. Just GONE-ing the AppBarLayout leaves the behavior
            // holding the old toolbar-height offset, which showed as a blank top strip.
            lp?.behavior = null
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
            binding.appBarLayout.visibility = View.VISIBLE
            lp?.behavior = com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior()
        }
        if (lp != null) navHost.layoutParams = lp
        binding.appBarMain.requestLayout()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_main, menu)
        return true
    }

    override fun onMutesChanged() {
        invalidateOptionsMenu()
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Hide the gear icon when we're already inside Settings (root or any child) —
        // the user is already here; showing it would just be noise.
        val destId = if (::navController.isInitialized) navController.currentDestination?.id else null
        val inSettings = destId in setOf(
            R.id.nav_settings,
            R.id.nav_settings_connection,
            R.id.nav_settings_notifications,
            R.id.nav_settings_downloads,
            R.id.nav_settings_advanced,
            // Setup screen: hide the gear/bell too, it owns the whole screen.
            R.id.nav_setup,
        )
        menu.findItem(R.id.action_settings)?.isVisible = !inSettings

        // Bell icon: solid bell when nothing's muted, bell-off when at least
        // one camera group has an active mute. Lets the user see at a glance
        // whether anything is currently being silenced.
        val muteItem = menu.findItem(R.id.action_mute)
        if (muteItem != null) {
            muteItem.isVisible = !inSettings
            val anyMuted = com.asksakis.freegate.notifications.CameraMuteStore
                .getInstance(this).activeMutes().isNotEmpty()
            muteItem.setIcon(
                if (anyMuted) R.drawable.ic_notifications_off
                else R.drawable.ic_notifications_active,
            )
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                if (::navController.isInitialized &&
                    navController.currentDestination?.id != R.id.nav_settings
                ) {
                    navController.navigate(R.id.nav_settings, null, fadeNavOptions)
                }
                true
            }
            R.id.action_mute -> {
                com.asksakis.freegate.ui.MuteGroupsBottomSheet().show(
                    supportFragmentManager,
                    com.asksakis.freegate.ui.MuteGroupsBottomSheet.TAG,
                )
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    /**
     * Check if we have the required permissions based on Android version
     */
    private fun hasRequiredPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Log the status of all permissions needed for WiFi detection
     * This helps with debugging permission issues
     */
    private fun logPermissionStatus() {
        Log.d("MainActivity", "===== PERMISSION STATUS =====")
        
        // Log basic info
        Log.d("MainActivity", "Android SDK Version: ${Build.VERSION.SDK_INT}")
        Log.d("MainActivity", "Android VERSION.RELEASE: ${Build.VERSION.RELEASE}")
        
        // Check all potentially relevant permissions
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        
        permissions.forEach { permission ->
            val hasPermission = ContextCompat.checkSelfPermission(this, permission) == 
                PackageManager.PERMISSION_GRANTED
            Log.d("MainActivity", "Permission $permission: ${if (hasPermission) "GRANTED" else "DENIED"}")
        }
        
        // Check and log WiFi status
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        Log.d("MainActivity", "WiFi Enabled: ${wifiManager.isWifiEnabled}")
        
        // Try to get the SSID in multiple ways for debugging
        try {
            @Suppress("DEPRECATION")
            val wifiInfoSsid = wifiManager.connectionInfo?.ssid
            Log.d("MainActivity", "Debug - WiFi SSID direct from WifiManager: $wifiInfoSsid")
            
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            val hasWifi = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            Log.d("MainActivity", "Debug - Has WiFi transport: $hasWifi")
            
            val transportInfo = networkCapabilities?.transportInfo
            Log.d("MainActivity", "Debug - TransportInfo is not null: ${transportInfo != null}")
            
            if (transportInfo != null && transportInfo is android.net.wifi.WifiInfo) {
                @Suppress("DEPRECATION")
                val transportSsid = transportInfo.ssid
                Log.d("MainActivity", "Debug - WiFi SSID from TransportInfo: $transportSsid")
            }
            
            // Try with direct settings access
            val systemSsid = android.provider.Settings.System.getString(contentResolver, "wifi_ssid")
            Log.d("MainActivity", "Debug - SSID from System settings: $systemSsid")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in SSID debug: ${e.message}")
        }
        
        // Check main permission requirement
        val mainPermission = Manifest.permission.ACCESS_FINE_LOCATION
        val hasMainPermission = ContextCompat.checkSelfPermission(this, mainPermission) ==
            PackageManager.PERMISSION_GRANTED
            
        val mainState = if (hasMainPermission) "GRANTED" else "DENIED"
        Log.d("MainActivity", "Main required permission ($mainPermission): $mainState")
        Log.d("MainActivity", "===== END PERMISSION STATUS =====")
    }

    /**
     * Immutable snapshot of everything the status dialog renders. Computed in
     * [resolveConnectionStatus] from NetworkUtils + system connectivity state so the
     * dialog's render() is a pure `state → views` mapping.
     */
    private data class ConnectionStatusState(
        val stateText: String,
        val stateTintRes: Int,
        val url: String,
        val details: String,
        val rttText: String,
        val history: List<Long>,
    )

    private fun resolveConnectionStatus(): ConnectionStatusState {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val mode = prefs.getString("connection_mode", "auto") ?: "auto"
        val endpoint = networkUtils.endpoint.value
        val url = endpoint?.url ?: networkUtils.currentUrl.value ?: "—"
        val isInternal = endpoint?.isInternal ?: networkUtils.isInternal.value
            ?: networkUtils.isHome()

        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val transport = when {
            caps == null -> "Offline"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
        val ssid = if (transport == "WiFi") networkUtils.getSsid() else null

        // State text reflects the *stable* classification only — IN_PROGRESS probes are
        // transient and would cause the header to flicker between "Connected" and
        // "Checking…" every heartbeat tick.
        val validationStatus = networkUtils.urlValidationStatus.value?.status
        val stateText: String
        val tint: Int
        when {
            caps == null -> { stateText = "Offline"; tint = R.color.cert_missing }
            validationStatus == NetworkUtils.ValidationStatus.FAILED ||
                validationStatus == NetworkUtils.ValidationStatus.TIMEOUT -> {
                stateText = "Server unreachable"; tint = R.color.cert_missing
            }
            else -> {
                stateText = if (isInternal) "Connected (internal)" else "Connected (external)"
                tint = R.color.accent_orange
            }
        }

        // Header already says "Connected (internal/external)" — keep details focused on
        // transport + SSID + (only when forced) the manual-override note.
        val details = buildString {
            append(transport)
            if (ssid != null) append(" · ").append(ssid)
            if (mode != "auto") {
                if (isNotEmpty()) append(" · ")
                append("Forced ").append(mode)
            }
        }

        val history = networkUtils.latencyHistory.value.orEmpty()
        val valid = history.filter { it > 0L }
        val avg = if (valid.isNotEmpty()) valid.average().toLong() else null
        val last = valid.lastOrNull()
        val rttText = when {
            avg != null && last != null -> "Roundtrip: avg ${avg}ms · last ${last}ms"
            last != null -> "Roundtrip: last ${last}ms"
            else -> "Roundtrip: no samples yet"
        }

        return ConnectionStatusState(stateText, tint, url, details, rttText, history)
    }

    /**
     * Connection-status dialog. Views are bound once; the body is re-rendered by
     * observers + a ~3s heartbeat while the dialog is visible. Heartbeat pauses
     * automatically when the Activity goes to [onStop].
     */
    private fun showNetworkStatusDialog() {
        val root = layoutInflater.inflate(R.layout.dialog_connection_status, null)
        val dot = root.findViewById<android.view.View>(R.id.status_dot)
        val stateTv = root.findViewById<android.widget.TextView>(R.id.status_state)
        val urlTv = root.findViewById<android.widget.TextView>(R.id.status_url)
        val detailsTv = root.findViewById<android.widget.TextView>(R.id.status_details)
        val rttTv = root.findViewById<android.widget.TextView>(R.id.status_rtt)
        val graph = root.findViewById<com.asksakis.freegate.ui.views.LatencyGraphView>(R.id.status_graph)

        // Keep a cached snapshot so we can short-circuit unchanged re-binds. TextView
        // already no-ops on same text, but backgroundTintList creates fresh objects
        // and would still invalidate the View every heartbeat tick.
        var lastStaticKey: String? = null
        fun bindStaticState() {
            val state = resolveConnectionStatus()
            val key = "${state.stateTintRes}|${state.stateText}|${state.url}|${state.details}"
            if (key == lastStaticKey) return
            lastStaticKey = key

            dot.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, state.stateTintRes)
            )
            stateTv.text = state.stateText
            urlTv.text = state.url
            detailsTv.text = state.details
        }
        fun bindRtt() {
            val state = resolveConnectionStatus()
            rttTv.text = state.rttText
            graph.setSamples(state.history)
        }
        bindStaticState()
        bindRtt()

        val dialog = com.asksakis.freegate.ui.FreegateDialogs.builder(this)
            .setTitle("Connection status")
            .setView(root)
            .setPositiveButton("Refresh", null) // handled in OnShowListener, no dismiss
            .setNegativeButton("Close", null)
            .create()

        // latency observer only updates rtt/graph; endpoint + validation state touch the
        // header + badge + details (rare changes — no flicker).
        val latencyObs = androidx.lifecycle.Observer<List<Long>> { bindRtt() }
        val statusObs = androidx.lifecycle.Observer<NetworkUtils.ValidationResult> { bindStaticState() }
        val endpointObs = androidx.lifecycle.Observer<NetworkUtils.ResolvedEndpoint> { bindStaticState() }
        networkUtils.latencyHistory.observe(this, latencyObs)
        networkUtils.urlValidationStatus.observe(this, statusObs)
        networkUtils.endpoint.observe(this, endpointObs)

        // Activity-scoped heartbeat so onStop/onDestroy can cancel it even if the user
        // somehow leaves the dialog alive (e.g. process death before dismiss).
        startStatusHeartbeat()

        dialog.setOnDismissListener {
            stopStatusHeartbeat()
            networkUtils.latencyHistory.removeObserver(latencyObs)
            networkUtils.urlValidationStatus.removeObserver(statusObs)
            networkUtils.endpoint.removeObserver(endpointObs)
        }

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener { networkUtils.probeCurrentUrlNow() }
            // Kick one probe immediately so the graph starts populating.
            networkUtils.probeCurrentUrlNow()
        }
        dialog.show()
    }

    private fun startStatusHeartbeat() {
        stopStatusHeartbeat()
        val r = object : Runnable {
            override fun run() {
                networkUtils.probeCurrentUrlNow()
                statusHeartbeatHandler.postDelayed(this, STATUS_PROBE_INTERVAL_MS)
            }
        }
        statusHeartbeatRunnable = r
        statusHeartbeatHandler.postDelayed(r, STATUS_PROBE_INTERVAL_MS)
    }

    private fun stopStatusHeartbeat() {
        statusHeartbeatRunnable?.let { statusHeartbeatHandler.removeCallbacks(it) }
        statusHeartbeatRunnable = null
    }

    /**
     * Updates the network indicator based on the current network type
     */
    /**
     * Rebuild the signal-bar reachability badge from validation + latency.
     *
     * FAILED/TIMEOUT short-circuits to a red "ERROR" label so a stale latency
     * sample can't lie about a server that just dropped. Otherwise the latest
     * sample drives the bar count + tint. Thresholds tuned for a Frigate API
     * probe (LAN setups regularly hit <15 ms, remote-but-fine sits around
     * 50-100 ms, anything over a quarter-second is sluggish enough that the
     * user should notice):
     *   - excellent (≤ 50 ms):  4 bars, green
     *   - good      (≤ 150 ms): 3 bars, green
     *   - fair      (≤ 300 ms): 2 bars, amber
     *   - poor      (> 300 ms): 1 bar,  red
     * No samples yet → 4 faint bars in neutral grey: badge present, but
     * doesn't claim a reachability state we haven't measured.
     *
     * Bars-only display (no millisecond text) — the bar count itself is the
     * signal, the exact ping number belongs in the status dialog.
     */
    private fun refreshConnectionStatus() {
        val badge = connectionStatusIndicator ?: return
        val status = networkUtils.urlValidationStatus.value?.status
        // No server configured: there's nothing to report reachability for, and the
        // Home setup empty state already owns the screen. Hide the badge rather than
        // show misleading "checking" bars.
        if (status == NetworkUtils.ValidationStatus.UNCONFIGURED) {
            badge.visibility = android.view.View.GONE
            return
        }
        badge.visibility = android.view.View.VISIBLE
        val isFailed = status == NetworkUtils.ValidationStatus.FAILED ||
            status == NetworkUtils.ValidationStatus.TIMEOUT
        if (isFailed) {
            badge.text = "ERROR"
            badge.setTextColor(ContextCompat.getColor(this, R.color.status_failed))
            badge.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            return
        }

        // First validation after a cold connect bundles DNS + TCP + TLS, so
        // the first sample can legitimately be 500-1500 ms even on a fast LAN.
        // Median was still getting pinned to a high value when 2-3 of the
        // first 5 samples were warm-up handshakes; take the *minimum* of the
        // most recent samples instead — it tracks the steady-state latency
        // the user actually feels for subsequent requests.
        val latency = networkUtils.latencyHistory.value.orEmpty().takeLast(5).minOrNull()?.toInt()
        val (barsDrawable, tintRes) = when {
            latency == null -> R.drawable.ic_signal_4 to R.color.status_checking
            latency <= 50 -> R.drawable.ic_signal_4 to R.color.status_ok
            latency <= 150 -> R.drawable.ic_signal_3 to R.color.status_ok
            latency <= 300 -> R.drawable.ic_signal_2 to R.color.stats_load_warn
            else -> R.drawable.ic_signal_1 to R.color.status_failed
        }
        badge.text = ""
        badge.setCompoundDrawablesRelativeWithIntrinsicBounds(barsDrawable, 0, 0, 0)
        badge.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, tintRes),
        )
    }

    private fun updateNetworkIndicator(destination: NavDestination?) {
        try {
            val indicator = networkIndicator ?: return
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"

            // Prefer the value emitted by NetworkUtils.isInternal — that's the same
            // boolean that was computed alongside the current URL, so the badge can't
            // disagree with the URL. Fall back to isHome() only before the first emit.
            val isInternal = networkUtils.isInternal.value ?: networkUtils.isHome()
            
            indicator.text = if (isInternal) "INT" else "EXT"

            // Dark capsule (same neutral grey as the signal badge) with only the
            // text recoloured per mode — orange for INT (brand accent), red for
            // EXT. Stops the badge from screaming neon at the user every time
            // they glance at the toolbar; the colour signal still reads at 10sp.
            val textColorRes = if (isInternal) R.color.accent_orange else R.color.cert_missing
            indicator.setTextColor(ContextCompat.getColor(this, textColorRes))
            
            // Only show on HomeFragment or when connection mode is forced
            val isHome = destination?.id == R.id.nav_home
            indicator.visibility = if (isHome || connectionMode != "auto") {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error updating network indicator: ${e.message}")
        }
    }

    /**
     * With edge-to-edge enabled we still want the WebView container to stop above the
     * gesture/nav bar — otherwise page content renders underneath it. Frigate's timeline
     * and scrubber controls sit right at the bottom of the page, so we add a small extra
     * breathing gap on top of the system inset so the user can reach them without hitting
     * the nav gesture area.
     */
    private fun applyBottomSystemBarPadding() {
        val container = findViewById<android.view.View>(R.id.nav_host_fragment_content_main)
            ?: return
        val extraGap = (resources.displayMetrics.density * EXTRA_BOTTOM_GAP_DP).toInt()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            // Inset the sides by the display cutout so a landscape side punch-hole
            // does not sit over Frigate's own left/right controls.
            val cutout = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(
                cutout.left,
                view.paddingTop,
                cutout.right,
                bars.bottom + extraGap,
            )
            insets
        }
    }

    /**
     * Update indicator when activity resumes
     */
    override fun onResume() {
        super.onResume()
        if (::navController.isInitialized) {
            updateNetworkIndicator(navController.currentDestination)
        }
        startStatsWatcherIfNeeded()
        statsStaleHandler.removeCallbacks(statsStaleTick)
        statsStaleHandler.postDelayed(statsStaleTick, STATS_STALE_TICK_MS)
    }

    override fun onPause() {
        super.onPause()
        uiStatsWatcher.stop()
        statsStaleHandler.removeCallbacks(statsStaleTick)
    }

    override fun onDestroy() {
        // NetworkUtils is a process-scoped singleton used by the notification service too —
        // never unregister its callback from an Activity, or the service loses network events
        // permanently and re-init across orientation changes produces nothing.
        uiStatsWatcher.shutdown()
        super.onDestroy()
    }

    /**
     * Start the Frigate HTTP stats poller. Tied to the Activity lifecycle so the poll
     * loop runs only while the UI is visible; stopped in [onPause].
     */
    private fun startStatsWatcherIfNeeded() {
        uiStatsWatcher.start()
    }

    private fun renderStatsIndicator(stats: com.asksakis.freegate.stats.FrigateStats?) {
        val cpuChip = statsCpuIndicator
        val gpuChip = statsGpuIndicator
        if (stats == null) {
            cpuChip?.visibility = android.view.View.GONE
            gpuChip?.visibility = android.view.View.GONE
            return
        }

        val stale = (System.currentTimeMillis() - stats.receivedAtMs) >
            com.asksakis.freegate.stats.FrigateStatsRepository.STALE_AFTER_MS
        val dimAlpha = if (stale) 0.5f else 1.0f

        applyMetricBadge(cpuChip, "CPU", stats.cpuPercent, dimAlpha)
        applyMetricBadge(gpuChip, "GPU", stats.gpuPercent, dimAlpha)
    }

    /**
     * Populate a single metric badge (CPU or GPU). Keeps the distinct dark tint from the
     * drawable; only the text colour changes to red on hot load, otherwise it stays on
     * the badge's muted accent to preserve the visual split between the two badges.
     */
    private fun applyMetricBadge(chip: TextView?, label: String, percent: Int?, alpha: Float) {
        if (chip == null) return
        if (percent == null) {
            chip.visibility = android.view.View.GONE
            return
        }
        chip.visibility = android.view.View.VISIBLE
        chip.text = "$label $percent%"
        chip.alpha = alpha

        // Three-tier load escalation: idle keeps the calm purple/blue per-metric
        // accent; warn flips to amber once the load crosses 50%; hot turns red
        // past 75% so the user can spot trouble without reading the percentage.
        val textColorRes = when {
            percent >= LOAD_HOT_THRESHOLD -> R.color.stats_load_hot
            percent >= LOAD_WARN_THRESHOLD -> R.color.stats_load_warn
            chip.id == R.id.stats_cpu_indicator -> R.color.stats_cpu_text
            chip.id == R.id.stats_gpu_indicator -> R.color.stats_gpu_text
            else -> R.color.text_primary
        }
        chip.setTextColor(ContextCompat.getColor(this, textColorRes))
    }

    private fun showFrigateStatsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_frigate_stats, null)
        val dialog = com.asksakis.freegate.ui.FreegateDialogs.builder(this)
            .setTitle("System statistics")
            .setView(view)
            .setPositiveButton("Close", null)
            .create()

        // Live-update the dialog contents while it's open — avoids having to tap-close-
        // tap to see a fresher sample.
        val observer = androidx.lifecycle.Observer<com.asksakis.freegate.stats.FrigateStats?> { stats ->
            bindStatsDialog(view, stats)
        }
        com.asksakis.freegate.stats.FrigateStatsRepository.stats.observeForever(observer)
        dialog.setOnDismissListener {
            com.asksakis.freegate.stats.FrigateStatsRepository.stats.removeObserver(observer)
        }
        dialog.show()
    }

    private fun bindStatsDialog(view: android.view.View, stats: com.asksakis.freegate.stats.FrigateStats?) {
        val empty = view.findViewById<TextView>(R.id.stats_empty)
        val cpuValue = view.findViewById<TextView>(R.id.stats_cpu_value)
        val gpuValue = view.findViewById<TextView>(R.id.stats_gpu_value)
        val cpuBar = view.findViewById<android.widget.ProgressBar>(R.id.stats_cpu_bar)
        val gpuBar = view.findViewById<android.widget.ProgressBar>(R.id.stats_gpu_bar)
        val memChip = view.findViewById<TextView>(R.id.stats_memory_chip)
        val upChip = view.findViewById<TextView>(R.id.stats_uptime_chip)
        val detectorsList = view.findViewById<android.widget.LinearLayout>(R.id.stats_detectors_list)
        val camerasList = view.findViewById<android.widget.LinearLayout>(R.id.stats_cameras_list)

        if (stats == null) {
            empty.visibility = android.view.View.VISIBLE
            cpuValue.text = "—"
            gpuValue.text = "—"
            cpuBar.progress = 0
            gpuBar.progress = 0
            memChip.text = "RAM —"
            upChip.text = "up —"
            detectorsList.removeAllViews()
            camerasList.removeAllViews()
            return
        }
        empty.visibility = android.view.View.GONE

        val cpu = stats.cpuPercent ?: 0
        val gpu = stats.gpuPercent ?: 0
        cpuValue.text = stats.cpuPercent?.let { "$it%" } ?: "—"
        gpuValue.text = stats.gpuPercent?.let { "$it%" } ?: "—"
        cpuBar.progress = cpu
        gpuBar.progress = gpu
        applyMeterTint(cpuBar, cpuValue, cpu, enabled = stats.cpuPercent != null)
        applyMeterTint(gpuBar, gpuValue, gpu, enabled = stats.gpuPercent != null)

        memChip.text = "RAM " + (stats.memoryPercent?.let { "$it%" } ?: "—")
        upChip.text = "up " + (stats.uptimeSeconds?.let { formatUptime(it) } ?: "—")

        bindDetectorRows(detectorsList, stats.detectors)
        bindCameraRows(camerasList, stats.cameras)
    }

    private fun applyMeterTint(
        bar: android.widget.ProgressBar,
        value: TextView,
        percent: Int,
        enabled: Boolean,
    ) {
        val colorRes = when {
            !enabled -> R.color.text_secondary
            percent >= 80 -> R.color.cert_missing
            percent >= 50 -> R.color.accent_orange
            else -> R.color.accent_orange
        }
        val color = ContextCompat.getColor(this, colorRes)
        bar.progressTintList = android.content.res.ColorStateList.valueOf(color)
        value.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.text_primary else R.color.text_secondary,
            ),
        )
    }

    private fun bindDetectorRows(
        container: android.widget.LinearLayout,
        detectors: List<com.asksakis.freegate.stats.FrigateStats.DetectorStat>,
    ) {
        container.removeAllViews()
        if (detectors.isEmpty()) {
            container.addView(emptyRowTextView("No detectors reported"))
            return
        }
        detectors.forEach { d ->
            val row = layoutInflater.inflate(R.layout.item_frigate_detector_row, container, false)
            row.findViewById<TextView>(R.id.detector_name).text = d.name
            row.findViewById<TextView>(R.id.detector_inference).text =
                d.inferenceMs?.let { "%.1f ms".format(it) } ?: "—"
            container.addView(row)
        }
    }

    private fun bindCameraRows(
        container: android.widget.LinearLayout,
        cameras: List<com.asksakis.freegate.stats.FrigateStats.CameraStat>,
    ) {
        container.removeAllViews()
        if (cameras.isEmpty()) {
            container.addView(emptyRowTextView("No cameras reported"))
            return
        }
        cameras.forEach { c ->
            val row = layoutInflater.inflate(R.layout.item_frigate_camera_row, container, false)
            row.findViewById<TextView>(R.id.camera_name).text =
                com.asksakis.freegate.utils.FrigateNameFormatter.pretty(c.name)
            row.findViewById<TextView>(R.id.camera_fps).text =
                c.cameraFps?.let { "%.1f fps".format(it) } ?: "— fps"
            row.findViewById<TextView>(R.id.camera_detect).text =
                c.detectionFps?.let { "det %.1f".format(it) } ?: "det —"
            container.addView(row)
        }
    }

    private fun emptyRowTextView(message: String): TextView =
        TextView(this).apply {
            text = message
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }

    /**
     * Apply Goldman bold + smaller size to the toolbar title. MaterialToolbar doesn't
     * reliably honour `app:titleTextAppearance` fontFamily via a style — we walk the
     * toolbar after the title is set to catch the underlying TextView. Keeps the style
     * as a fallback for any future theme that does respect it.
     */
    /**
     * Goldman title only on the root destination (app name next to the logo). Other
     * destinations (Settings, child screens) keep the default MaterialToolbar styling
     * so section headings don't suddenly switch font family.
     */
    private fun applyToolbarTitleStyle() {
        val toolbar = binding.toolbar
        val appName = getString(R.string.app_name)
        toolbar.post {
            val titleText = toolbar.title?.toString() ?: return@post
            for (i in 0 until toolbar.childCount) {
                val child = toolbar.getChildAt(i)
                // Only touch the toolbar's own title TextView — it has NO_ID and its
                // text matches the current toolbar title. Skip our badges which have
                // explicit IDs.
                if (child !is TextView ||
                    child.id != android.view.View.NO_ID ||
                    child.text?.toString() != titleText
                ) continue

                if (titleText == appName) {
                    val brandFont = androidx.core.content.res.ResourcesCompat.getFont(
                        this,
                        R.font.ancient_god,
                    )
                    child.typeface = brandFont
                    child.textSize = 14f
                    child.letterSpacing = 0.06f
                    child.includeFontPadding = false
                    child.gravity = android.view.Gravity.CENTER_VERTICAL
                } else {
                    // Reset to MaterialToolbar defaults so Settings / child screens
                    // keep sans-serif-medium titles.
                    child.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    child.textSize = 20f
                    child.letterSpacing = 0f
                    child.includeFontPadding = true
                    child.gravity = android.view.Gravity.CENTER_VERTICAL
                }
            }
        }
    }

    private fun formatUptime(seconds: Long): String {
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        val mins = (seconds % 3_600) / 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${mins}m"
            else -> "${mins}m"
        }
    }
    
    // Override to improve navigation consistency
    override fun onSupportNavigateUp(): Boolean {
        if (!::navController.isInitialized) {
            return super.onSupportNavigateUp()
        }
        
        // Pop back so HomeFragment view is restored (not recreated). Recreating it
        // drops WebView state and bypasses the zoom-disabled settings applied in
        // setupWebView(), re-enabling pinch-to-zoom until the next full reload.
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
    
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        bindNavControllerIfReady()
    }

    /**
     * Wire the NavController and its observers exactly once per Activity instance.
     * onCreate runs first; onPostCreate is a fallback for edge cases where the
     * NavHostFragment isn't attached yet. The `isInitialized` guard keeps both entry
     * points idempotent so we don't double-register observers.
     */
    private fun bindNavControllerIfReady() {
        if (::navController.isInitialized) return
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_content_main) as? NavHostFragment
            ?: return

        navController = navHostFragment.navController
        // Only Home is a top-level destination — Settings (root or child) shows the up
        // arrow so users can back out via the toolbar as well as the gesture.
        appBarConfiguration = AppBarConfiguration(setOf(R.id.nav_home))
        setupActionBarWithNavController(navController, appBarConfiguration)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateNetworkIndicator(destination)
            // Re-run onPrepareOptionsMenu so the gear icon hides inside Settings.
            invalidateOptionsMenu()
            // NavigationUI auto-applies the destination's android:label as the
            // toolbar title. On the root (Home) we want the logo + badges +
            // toolbar actions to own the space, no text — clear it explicitly
            // after NavigationUI runs.
            if (destination.id == R.id.nav_home) {
                supportActionBar?.title = ""
            }
            // NavigationUI reapplies the destination label to the toolbar and resets our
            // custom typeface — re-apply after each destination change.
            applyToolbarTitleStyle()
            // Show the Phylax mark only on the root destination; child screens get their
            // own title back without the brand mark bleeding across.
            binding.toolbar.logo = if (destination.id == R.id.nav_home) {
                androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_toolbar_logo)
            } else {
                null
            }
            // Hide the left-aligned stats/network strip on child destinations
            // so the screen title doesn't draw underneath the CPU/GPU badges
            // (left-aligning the badges put them where NavigationUI renders the
            // toolbar title text).
            findViewById<View>(R.id.toolbar_status_container)?.visibility =
                if (destination.id == R.id.nav_home) View.VISIBLE else View.GONE
        }
        networkUtils.currentUrl.observe(this) { _ ->
            updateNetworkIndicator(navController.currentDestination)
        }
        networkUtils.isInternal.observe(this) { _ ->
            updateNetworkIndicator(navController.currentDestination)
        }
        // Signal-bar reachability badge — independent of INT/EXT. Updates on
        // both the URL validation status (so FAILED becomes ERROR immediately)
        // and on the latency history (so the bar count tracks the live ping).
        networkUtils.urlValidationStatus.observe(this) { _ -> refreshConnectionStatus() }
        networkUtils.latencyHistory.observe(this) { _ -> refreshConnectionStatus() }

        // Navigation graph is wired — safe to act on the cold-start intent.
        pendingDeepLink?.let { handleIntent(it) }
        pendingDeepLink = null
    }


    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }
    
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        Log.d("MainActivity", "Intent action=${intent.action} data=${intent.data}")

        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme !in listOf("freegate", "frigate")) return

        val host = uri.host
        val firstSegment = uri.pathSegments.firstOrNull()
        Log.d("MainActivity", "Deep-link host=$host path=${uri.path}")

        when (host) {
            "home", "cameras" -> navigateHome()
            "settings" -> navigateSettings()
            "camera" -> {
                val cameraId = uri.getQueryParameter("id") ?: uri.path?.trimStart('/')
                Log.d("MainActivity", "Camera deep-link: $cameraId")
                // cameraId is logged for now; wiring it through to HomeFragment is
                // deferred until a UX story actually needs the direct-to-camera path.
                navigateHome()
            }
            "review" -> {
                val reviewId = firstSegment ?: uri.path?.trimStart('/')
                if (!reviewId.isNullOrEmpty()) {
                    com.asksakis.freegate.notifications.DeepLinkRouter.setPending(
                        com.asksakis.freegate.notifications.DeepLinkRouter.Target.Review(reviewId),
                    )
                }
                navigateHome()
            }
            "event" -> {
                val eventId = firstSegment ?: uri.path?.trimStart('/')
                if (!eventId.isNullOrEmpty()) {
                    com.asksakis.freegate.notifications.DeepLinkRouter.setPending(
                        com.asksakis.freegate.notifications.DeepLinkRouter.Target.Event(eventId),
                    )
                }
                navigateHome()
            }
            else -> navigateHome()
        }
    }

    private fun navigateHome() {
        if (::navController.isInitialized) navController.navigate(R.id.nav_home, null, fadeNavOptions)
    }

    private fun navigateSettings() {
        if (::navController.isInitialized) navController.navigate(R.id.nav_settings, null, fadeNavOptions)
    }

    /**
     * Apply the same crossfade as the Settings sub-screens when navigating by
     * destination id (no action defined). `launchSingleTop` avoids pushing a duplicate
     * entry onto the back stack when the user re-enters the same destination (e.g.
     * tapping gear twice in quick succession, permission-grant follow-up that navigates
     * to Home while already on Home).
     */
    private val fadeNavOptions by lazy {
        NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setEnterAnim(R.anim.fade_in)
            .setExitAnim(R.anim.fade_out)
            .setPopEnterAnim(R.anim.fade_in)
            .setPopExitAnim(R.anim.fade_out)
            .build()
    }
    
    /**
     * Check for app updates from GitHub
     */
    private fun checkForUpdates() {
        val updateChecker = UpdateChecker(this)
        lifecycleScope.launch {
            // Respect the checker's throttle on cold start — otherwise every launch
            // hammers GitHub's releases endpoint and risks rate limits. Manual checks
            // from Settings still force-bypass (user-initiated intent).
            val updateInfo = updateChecker.checkForUpdates(force = false)
            updateInfo?.let {
                updateChecker.showUpdateDialog(this@MainActivity, it)
            }
        }
    }
}
