package com.asksakis.freegate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.NavController
import androidx.navigation.NavDestination
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
import com.google.android.material.snackbar.Snackbar
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var networkUtils: NetworkUtils
    // NetworkFixer functionality has been consolidated into NetworkUtils
    private var networkIndicator: TextView? = null
    
    private val requestRuntimePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val wifiKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        val wifiGranted = results[wifiKey] == true

        if (wifiGranted) {
            networkUtils.checkAndUpdateUrl()
            if (::navController.isInitialized &&
                navController.currentDestination?.id == R.id.nav_home) {
                navController.navigate(R.id.nav_home)
            }
        } else {
            Snackbar.make(
                binding.root,
                "Frigate Viewer needs permission to detect WiFi networks for automatic URL switching.",
                Snackbar.LENGTH_LONG
            ).setAction("Grant") {
                requestRequiredPermissions()
            }.show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw app content behind transparent system bars. The AppBarLayout carries
        // fitsSystemWindows=true so it grows under the status bar; light status icons
        // are forced regardless of light/dark theme because the toolbar is always dark.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
        
        // Handle intent if launched from custom scheme
        handleIntent(intent)
        
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // Check if this is the first run
        val isFirstRun = prefs.getBoolean("is_first_run", true)
        if (isFirstRun) {
            // Show a toast prompting the user to configure URLs
            Toast.makeText(
                this,
                "Welcome! Configure Frigate URLs in Settings.",
                Toast.LENGTH_LONG
            ).show()
            
            // Save that we've shown the first-run message
            prefs.edit().putBoolean("is_first_run", false).apply()
        }
        
        // Initialize network utilities (singleton)
        networkUtils = NetworkUtils.getInstance(this)
        
        // Find network indicator
        networkIndicator = findViewById(R.id.network_indicator)
        
        // Log current permission status
        logPermissionStatus()
        
        // Always force-check permissions on each startup
        // This is critical for WiFi detection to work
        requestRequiredPermissions()
        
        
        // Show a message about why we need permissions only if we don't have them
        if (!hasRequiredPermissions()) {
            Snackbar.make(
                binding.root,
                "WiFi detection requires permissions for automatic URL switching.",
                Snackbar.LENGTH_LONG
            ).setAction("Grant") {
                requestRequiredPermissions()
            }.show()
        }

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as? NavHostFragment
        if (navHostFragment != null) {
            navController = navHostFragment.navController
            appBarConfiguration = AppBarConfiguration(setOf(R.id.nav_home, R.id.nav_settings))
            setupActionBarWithNavController(navController, appBarConfiguration)

            navController.addOnDestinationChangedListener { _, destination, _ ->
                updateNetworkIndicator(destination)
            }
            networkUtils.currentUrl.observe(this) { _ ->
                updateNetworkIndicator(navController.currentDestination)
            }
        } else {
            Log.d("MainActivity", "NavHostFragment not ready yet")
        }
        
        // Check for updates on app start
        checkForUpdates()

        // Start the Frigate alert listener if the user has notifications enabled.
        com.asksakis.freegate.notifications.FrigateAlertService.updateForContext(this)

        // Edge-to-edge bottom inset for the WebView area.
        applyBottomSystemBarPadding()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                if (::navController.isInitialized &&
                    navController.currentDestination?.id != R.id.nav_settings
                ) {
                    navController.navigate(R.id.nav_settings)
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    /**
     * Check required permissions and request them if needed
     */
    private fun checkRequiredPermissions() {
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
        }
    }
    
    /**
     * Request the appropriate permissions based on Android version
     * Using the exact approach from the example code
     */
    /**
     * Request the appropriate permissions based on Android version
     * For Android 13+, we use NEARBY_WIFI_DEVICES with neverForLocation flag
     */
    private fun requestRequiredPermissions() {
        // Only request dangerous permissions at runtime. Normal permissions
        // (ACCESS_WIFI_STATE, ACCESS_NETWORK_STATE, MODIFY_AUDIO_SETTINGS) are
        // granted automatically at install time from the manifest.
        val runtimePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,  // Wi-Fi SSID on Android 13+
                Manifest.permission.RECORD_AUDIO,         // WebRTC mic for Frigate two-way audio
                Manifest.permission.POST_NOTIFICATIONS    // Required to show Frigate alerts
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.RECORD_AUDIO
            )
        }

        val missing = runtimePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return

        Log.d("MainActivity", "Requesting runtime permissions: $missing")
        requestRuntimePermissions.launch(missing.toTypedArray())
    }
    
    
    /**
     * Check if we have the required permissions based on Android version
     */
    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
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
            Manifest.permission.NEARBY_WIFI_DEVICES,
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
            Log.d("MainActivity", "Debug - Has WiFi transport: ${networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true}")
            
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
        val mainPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        val hasMainPermission = ContextCompat.checkSelfPermission(this, mainPermission) == 
            PackageManager.PERMISSION_GRANTED
            
        Log.d("MainActivity", "Main required permission ($mainPermission): ${if (hasMainPermission) "GRANTED" else "DENIED"}")
        Log.d("MainActivity", "===== END PERMISSION STATUS =====")
    }

    /**
     * Updates the network indicator based on the current network type
     */
    private fun updateNetworkIndicator(destination: NavDestination?) {
        try {
            // Get the indicator
            val indicator = networkIndicator ?: return
            
            // Get settings to determine connection mode
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"
            
            // Check if we're on a home network
            val isHomeNetwork = networkUtils.isHome()
            
            // In manual mode, use the fixed setting; in auto mode, use actual connection
            val isInternal = when (connectionMode) {
                "internal" -> true
                "external" -> false
                else -> isHomeNetwork
            }
            
            // Update indicator text
            indicator.text = if (isInternal) "INT" else "EXT"
            
            // Update indicator color
            val backgroundColor = if (isInternal) "#4CAF50" else "#F44336" // Green for INT, Red for EXT
            val drawable = indicator.background.mutate()
            drawable.setTint(Color.parseColor(backgroundColor))
            indicator.background = drawable
            
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
     * gesture/nav bar — otherwise page content renders underneath it. AppBarLayout
     * already handles the top inset via fitsSystemWindows; this does the bottom one.
     */
    private fun applyBottomSystemBarPadding() {
        val container = findViewById<android.view.View>(R.id.nav_host_fragment_content_main)
            ?: return
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bars.bottom)
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
    }
    
    override fun onDestroy() {
        // Unregister network callbacks to prevent memory leaks
        if (::networkUtils.isInitialized) {
            networkUtils.unregisterCallback()
        }
        
        super.onDestroy()
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
        if (!::navController.isInitialized) {
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as? NavHostFragment
            if (navHostFragment != null) {
                navController = navHostFragment.navController
                appBarConfiguration = AppBarConfiguration(setOf(R.id.nav_home, R.id.nav_settings))
                setupActionBarWithNavController(navController, appBarConfiguration)
                navController.addOnDestinationChangedListener { _, destination, _ ->
                    updateNetworkIndicator(destination)
                }
                networkUtils.currentUrl.observe(this) { _ ->
                    updateNetworkIndicator(navController.currentDestination)
                }
            }
        }
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
                // TODO: pass cameraId through to HomeFragment once we need it.
                navigateHome()
            }
            "review" -> {
                val reviewId = firstSegment ?: uri.path?.trimStart('/')
                if (!reviewId.isNullOrEmpty()) {
                    com.asksakis.freegate.notifications.DeepLinkRouter.pendingReviewId = reviewId
                }
                navigateHome()
            }
            else -> navigateHome()
        }
    }

    private fun navigateHome() {
        if (::navController.isInitialized) navController.navigate(R.id.nav_home)
    }

    private fun navigateSettings() {
        if (::navController.isInitialized) navController.navigate(R.id.nav_settings)
    }
    
    /**
     * Check for app updates from GitHub
     */
    private fun checkForUpdates() {
        val updateChecker = UpdateChecker(this)
        lifecycleScope.launch {
            // Force check on app launch
            val updateInfo = updateChecker.checkForUpdates(force = true)
            updateInfo?.let {
                updateChecker.showUpdateDialog(this@MainActivity, it)
            }
        }
    }
}