package com.asksakis.freegate.notifications

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Best-effort deep-links into OEM-specific "never sleeping apps" / "auto-start" /
 * "background restrictions" settings screens. These screens exist outside the standard
 * Android API, so each manufacturer has its own activity name and path — we try a list
 * of known component names per vendor and fall back to the app details screen.
 *
 * Why we need this at all: even after the user grants `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
 * OEM skins layer additional sleeping / auto-kill policies on top. On Samsung One UI in
 * particular, an app can be battery-opt-exempted but still get WorkManager ticks delayed
 * for days because it's absent from the Never-sleeping-apps allowlist.
 */
object OemSettingsIntents {

    private const val TAG = "OemSettingsIntents"

    enum class OemType(val displayName: String) {
        SAMSUNG("Samsung"),
        XIAOMI("Xiaomi / Redmi / Poco"),
        OPPO("OPPO / OnePlus / Realme"),
        HUAWEI("Huawei / Honor"),
        VIVO("Vivo / iQOO"),
        GENERIC("your device"),
    }

    fun current(): OemType = when (Build.MANUFACTURER.lowercase()) {
        "samsung" -> OemType.SAMSUNG
        "xiaomi", "redmi", "poco" -> OemType.XIAOMI
        "oppo", "oneplus", "realme" -> OemType.OPPO
        "huawei", "honor" -> OemType.HUAWEI
        "vivo" -> OemType.VIVO
        else -> OemType.GENERIC
    }

    /** Whether the current device has an OEM-specific background restrictions screen worth showing. */
    fun hasCustomBackgroundSettings(): Boolean = current() != OemType.GENERIC

    /** Launches the first resolvable screen for the current OEM, or falls back to app details. */
    fun openBackgroundRestrictions(context: Context) {
        val candidates = candidatesFor(current())
        for (intent in candidates) {
            if (tryStart(context, intent)) return
        }
        // Last-ditch: per-app details — at least lets the user disable "unmonitored" manually.
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        tryStart(context, fallback)
    }

    /** Localised user-facing instructions for the given OEM. */
    fun instructionsFor(oem: OemType): String = when (oem) {
        OemType.SAMSUNG ->
            "On Samsung, 'Unrestricted' battery alone is not enough — Auto-optimize daily " +
                "still kills background services. Do both: set battery to Unrestricted, and " +
                "turn off Auto-optimize in Device Care. The button below opens the right screen."
        OemType.XIAOMI ->
            "MIUI requires explicit Autostart permission for background services. Enable it " +
                "for Phylax in the screen below."
        OemType.OPPO ->
            "OPPO / OnePlus ColorOS keeps a Startup Manager list. Enable Phylax there."
        OemType.HUAWEI ->
            "Huawei / Honor requires Protected Apps and Startup to be enabled so the listener survives."
        OemType.VIVO ->
            "Vivo / iQOO needs High Background Power Consumption enabled for Phylax."
        OemType.GENERIC ->
            "Android's own battery-optimization exemption should be enough. If notifications " +
                "still lag, check the manufacturer's background-activity settings manually."
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start $intent: ${e.message}")
            false
        }
    }

    /**
     * Known OEM screen component names. Listed most-specific-first; callers try each
     * until one resolves. The comments cite which OEM skin version was seen hosting
     * that component — OEMs shuffle these between releases.
     */
    @Suppress("LongMethod")
    private fun candidatesFor(oem: OemType): List<Intent> = when (oem) {
        OemType.SAMSUNG -> listOf(
            // One UI 5/6: Never sleeping apps → Battery activity entry point.
            Intent().setComponent(ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity",
            )),
            Intent().setComponent(ComponentName(
                "com.samsung.android.sm",
                "com.samsung.android.sm.battery.ui.BatteryActivity",
            )),
            Intent().setComponent(ComponentName(
                "com.samsung.android.sm_cn",
                "com.samsung.android.sm.ui.battery.BatteryActivity",
            )),
        )
        OemType.XIAOMI -> listOf(
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            Intent().setComponent(ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )),
            Intent().setComponent(ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            )),
        )
        OemType.OPPO -> listOf(
            Intent().setComponent(ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            )),
            Intent().setComponent(ComponentName(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity",
            )),
            Intent().setComponent(ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            )),
        )
        OemType.HUAWEI -> listOf(
            Intent().setComponent(ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            )),
            Intent().setComponent(ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
            )),
        )
        OemType.VIVO -> listOf(
            Intent().setComponent(ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            )),
            Intent().setComponent(ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            )),
        )
        OemType.GENERIC -> emptyList()
    }
}
