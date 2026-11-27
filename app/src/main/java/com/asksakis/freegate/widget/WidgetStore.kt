package com.asksakis.freegate.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * State of the home screen widgets: the cameras each widget shows, the last alert, and
 * the cached images.
 *
 * Images are stored as files in `noBackupFilesDir`, so a widget can be redrawn after a
 * reboot without network, and pictures of the user's home never go into a backup.
 */
class WidgetStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val imageDir = File(appContext.noBackupFilesDir, IMAGE_DIR)

    fun cameras(widgetId: Int): List<String> =
        prefs.getString(KEY_CAMERAS + widgetId, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    fun setCameras(widgetId: Int, cameras: List<String>) {
        prefs.edit().putString(KEY_CAMERAS + widgetId, cameras.joinToString(SEPARATOR)).apply()
    }

    fun removeWidget(widgetId: Int) {
        prefs.edit().remove(KEY_CAMERAS + widgetId).apply()
    }

    /** The alert the last event widget shows. */
    data class LastEvent(
        val reviewId: String,
        val camera: String,
        /** What was detected, for example "Person" or a recognised name. */
        val title: String,
        /** The camera and the zones, for example "Front Door · Driveway". */
        val subtitle: String,
        val isAlert: Boolean,
        val timeMs: Long,
    )

    fun lastEvent(): LastEvent? {
        val id = prefs.getString(KEY_EVENT_ID, null) ?: return null
        return LastEvent(
            reviewId = id,
            camera = prefs.getString(KEY_EVENT_CAMERA, null).orEmpty(),
            title = prefs.getString(KEY_EVENT_TITLE, null).orEmpty(),
            subtitle = prefs.getString(KEY_EVENT_SUBTITLE, null).orEmpty(),
            isAlert = prefs.getBoolean(KEY_EVENT_ALERT, true),
            timeMs = prefs.getLong(KEY_EVENT_TIME, 0L),
        )
    }

    fun setLastEvent(event: LastEvent, image: Bitmap?) {
        prefs.edit()
            .putString(KEY_EVENT_ID, event.reviewId)
            .putString(KEY_EVENT_CAMERA, event.camera)
            .putString(KEY_EVENT_TITLE, event.title)
            .putString(KEY_EVENT_SUBTITLE, event.subtitle)
            .putBoolean(KEY_EVENT_ALERT, event.isAlert)
            .putLong(KEY_EVENT_TIME, event.timeMs)
            .apply()
        // Without a picture of this event, show none rather than the previous event's.
        if (image == null || !saveImage(LAST_EVENT_IMAGE, image)) deleteImage(LAST_EVENT_IMAGE)
    }

    fun lastEventImage(): Bitmap? = loadImage(LAST_EVENT_IMAGE)

    /** What the server status widget shows: the last stats Frigate returned. */
    data class ServerStatus(
        val cpuPercent: Int?,
        val gpuPercent: Int?,
        val memoryPercent: Int?,
        val inferenceMs: Double?,
        val camerasOnline: Int,
        val camerasTotal: Int,
        val uptimeSeconds: Long?,
        /** When these values were fetched. */
        val timeMs: Long,
        /** False when the latest attempt failed; the values are then the last good ones. */
        val reachable: Boolean,
    )

    fun serverStatus(): ServerStatus? {
        val raw = prefs.getString(KEY_SERVER_STATUS, null) ?: return null
        return runCatching {
            val j = org.json.JSONObject(raw)
            fun optInt(k: String) = if (j.isNull(k) || !j.has(k)) null else j.getInt(k)
            ServerStatus(
                cpuPercent = optInt("cpu"),
                gpuPercent = optInt("gpu"),
                memoryPercent = optInt("mem"),
                inferenceMs = if (j.isNull("inference") || !j.has("inference")) null else j.getDouble("inference"),
                camerasOnline = j.optInt("online"),
                camerasTotal = j.optInt("total"),
                uptimeSeconds = if (j.isNull("uptime") || !j.has("uptime")) null else j.getLong("uptime"),
                timeMs = j.optLong("time"),
                reachable = j.optBoolean("reachable", true),
            )
        }.getOrNull()
    }

    fun setServerStatus(status: ServerStatus) {
        val j = org.json.JSONObject()
            .put("cpu", status.cpuPercent ?: org.json.JSONObject.NULL)
            .put("gpu", status.gpuPercent ?: org.json.JSONObject.NULL)
            .put("mem", status.memoryPercent ?: org.json.JSONObject.NULL)
            .put("inference", status.inferenceMs ?: org.json.JSONObject.NULL)
            .put("online", status.camerasOnline)
            .put("total", status.camerasTotal)
            .put("uptime", status.uptimeSeconds ?: org.json.JSONObject.NULL)
            .put("time", status.timeMs)
            .put("reachable", status.reachable)
        prefs.edit().putString(KEY_SERVER_STATUS, j.toString()).apply()
    }

    /** When the cached frame of [camera] was taken, or 0 when there is none. */
    fun snapshotTime(camera: String): Long = prefs.getLong(KEY_SNAPSHOT_TIME + camera, 0L)

    fun setSnapshot(camera: String, image: Bitmap, timeMs: Long) {
        // Only a frame that was written moves the time, so the widget never shows a new
        // time over an old picture.
        if (saveImage(cameraImageKey(camera), image)) {
            prefs.edit().putLong(KEY_SNAPSHOT_TIME + camera, timeMs).apply()
        }
    }

    /** Delete the cached frames of every camera not in [keep]. */
    fun pruneSnapshots(keep: Set<String>) {
        pruneImages(CAMERA_IMAGE_PREFIX, keep.map { cameraImageKey(it) }.toSet())
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(KEY_SNAPSHOT_TIME) && it.removePrefix(KEY_SNAPSHOT_TIME) !in keep }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    /** Delete the last event and its picture, when the last event widget is removed. */
    fun clearLastEvent() {
        prefs.edit()
            .remove(KEY_EVENT_ID).remove(KEY_EVENT_CAMERA).remove(KEY_EVENT_TITLE)
            .remove(KEY_EVENT_SUBTITLE).remove(KEY_EVENT_ALERT).remove(KEY_EVENT_TIME)
            .apply()
        deleteImage(LAST_EVENT_IMAGE)
    }

    fun clearServerStatus() {
        prefs.edit().remove(KEY_SERVER_STATUS).apply()
    }

    fun snapshot(camera: String): Bitmap? = loadImage(cameraImageKey(camera))

    private fun cameraImageKey(camera: String) = imageKey(CAMERA_IMAGE_PREFIX, camera)

    /** A file name for [id] under [prefix] that cannot leave the image folder. */
    internal fun imageKey(prefix: String, id: String) = prefix + id.replace(UNSAFE_CHARS, "_")

    /** Delete every image whose key starts with [prefix] and is not in [keepKeys]. */
    internal fun pruneImages(prefix: String, keepKeys: Set<String>) {
        val keepFiles = keepKeys.map { "$it.jpg" }.toSet()
        imageDir.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".jpg") && it.name !in keepFiles }
            ?.forEach { it.delete() }
    }

    /**
     * Write the image to a temporary file and rename it into place, so a widget redraw on
     * another thread never decodes a half-written JPEG. Returns false when it failed.
     */
    internal fun saveImage(key: String, image: Bitmap, maxSide: Int = MAX_IMAGE_SIDE): Boolean {
        val target = File(imageDir, "$key.jpg")
        var temp: File? = null
        return try {
            imageDir.mkdirs()
            // A unique name per write: the periodic refresh and an alert refresh can write
            // the same camera at the same time, and a shared temp file would mix their bytes.
            temp = File.createTempFile("tmp_$key", ".tmp", imageDir)
            val written = temp.outputStream().use {
                scaleDown(image, maxSide).compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
            }
            written && temp.renameTo(target)
        } catch (e: IOException) {
            Log.w(TAG, "Could not cache widget image $key: ${e.message}")
            false
        } finally {
            temp?.delete()
        }
    }

    internal fun loadImage(key: String): Bitmap? {
        val file = File(imageDir, "$key.jpg")
        return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    internal fun deleteImage(key: String) {
        File(imageDir, "$key.jpg").delete()
    }

    companion object {
        private const val TAG = "WidgetStore"
        internal const val PREFS_FILE = "home_widgets"
        private const val IMAGE_DIR = "widgets"
        private const val SEPARATOR = "\n"
        private const val KEY_CAMERAS = "cameras_"
        private const val KEY_EVENT_ID = "last_event_id"
        private const val KEY_EVENT_CAMERA = "last_event_camera"
        private const val KEY_EVENT_TITLE = "last_event_title"
        private const val KEY_EVENT_TIME = "last_event_time"
        private const val KEY_EVENT_SUBTITLE = "last_event_subtitle"
        private const val KEY_EVENT_ALERT = "last_event_alert"
        private const val KEY_SNAPSHOT_TIME = "snapshot_time_"
        private const val KEY_SERVER_STATUS = "server_status"
        private const val LAST_EVENT_IMAGE = "last_event"
        private const val CAMERA_IMAGE_PREFIX = "camera_"
        private const val JPEG_QUALITY = 85
        private val UNSAFE_CHARS = Regex("[^A-Za-z0-9_-]")

        /**
         * Widgets pass their bitmaps to the launcher through a Binder transaction, which
         * fails for large images. 640 pixels on the long side is sharp enough for a widget.
         */
        private const val MAX_IMAGE_SIDE = 640

        internal fun scaledSize(width: Int, height: Int, maxSide: Int = MAX_IMAGE_SIDE): Pair<Int, Int> {
            val longest = maxOf(width, height)
            if (longest <= maxSide || longest <= 0) return width to height
            val factor = maxSide.toDouble() / longest
            return maxOf(1, (width * factor).toInt()) to maxOf(1, (height * factor).toInt())
        }

        private fun scaleDown(image: Bitmap, maxSide: Int): Bitmap {
            val (w, h) = scaledSize(image.width, image.height, maxSide)
            return if (w == image.width && h == image.height) image else Bitmap.createScaledBitmap(image, w, h, true)
        }
    }
}
