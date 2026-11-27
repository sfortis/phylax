package com.asksakis.freegate.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.asksakis.freegate.R
import com.asksakis.freegate.notifications.FrigateConfigFetcher
import com.asksakis.freegate.ui.FreegateDialogs
import com.asksakis.freegate.utils.NetworkUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Chooses the cameras of a home screen widget: up to [CameraShortcutsWidget.MAX_CAMERAS]
 * for the shortcuts widget, one for the image widget. The launcher opens it when the widget
 * is added, and the widget opens it again while it has no camera.
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A widget whose configuration is cancelled is removed by the launcher.
        setResult(RESULT_CANCELED)
        widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        // The activity is exported for the launcher, so ignore ids of widgets that are not ours.
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
        if (provider?.packageName != packageName) {
            finish()
            return
        }
        val single = provider.className == CameraSnapshotWidget::class.java.name
        // The fetch can take seconds on a slow server; without this the translucent activity
        // would block the launcher with nothing on screen.
        val loading = FreegateDialogs.builder(this)
            .setMessage(R.string.widget_loading_cameras)
            .setOnCancelListener { finish() }
            .show()
        lifecycleScope.launch {
            val cameras = loadCameras()
            loading.dismiss()
            when {
                isFinishing || isDestroyed -> Unit
                cameras.isEmpty() -> showNoCameras()
                single -> pickOne(cameras)
                else -> pickSeveral(cameras)
            }
        }
    }

    private suspend fun loadCameras(): List<String> {
        // The first URL can be a stale guess in a fresh process, so try the other one too.
        NetworkUtils.getInstance(this).candidateBaseUrls().forEach { baseUrl ->
            try {
                val names = FrigateConfigFetcher(this).fetchCameraNames(baseUrl)
                if (names.isNotEmpty()) return names
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not load the camera list: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun pickSeveral(cameras: List<String>) {
        val current = WidgetStore(this).cameras(widgetId).toSet()
        val checked = BooleanArray(cameras.size) { cameras[it] in current }
        FreegateDialogs.builder(this)
            .setTitle(R.string.widget_pick_cameras)
            .setMultiChoiceItems(labels(cameras), checked) { dialog, which, isChecked ->
                // The dialog has already written the new state into `checked`.
                checked[which] = isChecked
                if (isChecked && checked.count { it } > CameraShortcutsWidget.MAX_CAMERAS) {
                    checked[which] = false
                    (dialog as AlertDialog).listView.setItemChecked(which, false)
                    Toast.makeText(this, R.string.widget_max_cameras, Toast.LENGTH_SHORT).show()
                }
                // With nothing checked, "Add" would cancel and the launcher would drop the widget.
                (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = checked.any { it }
            }
            .setPositiveButton(R.string.widget_add) { _, _ ->
                save(cameras.filterIndexed { i, _ -> checked[i] })
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
            .getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = checked.any { it }
    }

    private fun pickOne(cameras: List<String>) {
        val current = WidgetStore(this).cameras(widgetId).firstOrNull()
        FreegateDialogs.builder(this)
            .setTitle(R.string.widget_pick_camera)
            .setSingleChoiceItems(labels(cameras), cameras.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                save(listOf(cameras[which]))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showNoCameras() {
        FreegateDialogs.builder(this)
            .setTitle(R.string.widget_no_cameras_title)
            .setMessage(R.string.widget_no_cameras)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun save(cameras: List<String>) {
        if (cameras.isEmpty()) {
            finish()
            return
        }
        val store = WidgetStore(this)
        store.setCameras(widgetId, cameras)
        val manager = AppWidgetManager.getInstance(this)
        val provider = manager.getAppWidgetInfo(widgetId)?.provider?.className
        if (provider == CameraSnapshotWidget::class.java.name) {
            // A reconfigured widget no longer shows its old camera, so its picture goes.
            store.pruneSnapshots(CameraSnapshotWidget.cameras(this))
            CameraSnapshotWidget.render(this, manager, widgetId)
            WidgetRefreshWorker.updateSchedule(this)
            WidgetRefreshWorker.refreshNow(this, cameras.first())
        } else {
            CameraShortcutsWidget.render(this, manager, widgetId)
        }
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    private fun labels(cameras: List<String>): Array<CharSequence> =
        cameras.map { HomeWidgets.prettyCamera(it) as CharSequence }.toTypedArray()

    private companion object {
        const val TAG = "WidgetConfigActivity"
    }
}
