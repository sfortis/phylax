package com.asksakis.freegate.download

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import com.asksakis.freegate.utils.ClientCertManager
import com.asksakis.freegate.utils.OkHttpClientFactory
import com.asksakis.freegate.utils.UrlUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream

/**
 * Handles all Frigate download flows.
 *
 * A download runs in one of two places. Android's system [DownloadManager] handles it
 * by default, because it contributes progress notifications and retries that this class
 * would otherwise have to write itself. A download that needs the saved client
 * certificate skips it and runs in-process from the start, since the system downloader
 * has no way to present one. A download the system downloader starts and the server
 * then rejects as unauthorised is retried in-process once, which is where the app's
 * stored credentials apply. See [requiresClientCertificate] and [monitorDownloadManager].
 *
 * The in-process path owns no TLS state of its own. Its client comes from
 * [OkHttpClientFactory], which is the single place that decides trust, client
 * certificates and preemptive Basic Auth for every Frigate request the app makes.
 *
 * UI-side effects (toasts, snackbars, opening files with an intent) go through
 * [Callbacks] so this class stays independent of the Fragment/Activity lifecycle.
 */
class DownloadHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val clientCertManager: ClientCertManager,
    private val callbacks: Callbacks
) {

    private val notifier = DownloadNotifier(context)

    interface Callbacks {
        /** A download has been accepted and started. Show optional progress UI. */
        fun onDownloadStarted(fileName: String)

        /** Download completed; caller may offer to open the file. */
        fun onDownloadCompleted(fileName: String, file: File)

        /** Download failed; [error] is a user-friendly message. */
        fun onDownloadFailed(fileName: String, error: String)
    }

    fun handleWebViewDownload(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimetype: String?,
        currentPageUrl: String?
    ) {
        try {
            val absoluteUrl = toAbsoluteUrl(url, currentPageUrl)
            val fileName = resolveFileName(absoluteUrl, contentDisposition, mimetype)
            Log.d(TAG, "Download requested: $absoluteUrl -> $fileName (mime=$mimetype)")

            val cookies = CookieManager.getInstance().getCookie(absoluteUrl)

            if (UrlUtils.isPrivateIpUrl(absoluteUrl) || requiresClientCertificate()) {
                Log.d(TAG, "Downloading in-process so the app's own TLS and credentials apply")
                downloadDirect(absoluteUrl, fileName, cookies, userAgent, currentPageUrl)
            } else {
                downloadViaSystemManager(absoluteUrl, fileName, cookies, userAgent, currentPageUrl, mimetype)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download setup failed: ${e.message}", e)
            callbacks.onDownloadFailed("download", e.message ?: "Unknown error")
        }
    }

    /** Convert a relative URL to absolute using the current page's origin. */
    private fun toAbsoluteUrl(url: String, currentPageUrl: String?): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (currentPageUrl == null) return url
        val pathSep = currentPageUrl.indexOf('/', 8) // after "https://"
        val origin = if (pathSep > 0) currentPageUrl.substring(0, pathSep) else currentPageUrl
        return origin + if (url.startsWith("/")) url else "/$url"
    }

    private fun resolveFileName(url: String, contentDisposition: String?, mimetype: String?): String {
        var name = ""

        if (!contentDisposition.isNullOrEmpty()) {
            val patterns = listOf(
                Regex("filename\\*?=['\"]?([^'\"\\s;]+)['\"]?"),
                Regex("filename=([^;\\s]+)")
            )
            name = patterns
                .firstNotNullOfOrNull { it.find(contentDisposition) }
                ?.groupValues?.get(1)
                ?.replace("\"", "")
                ?.replace("'", "")
                ?.replace("UTF-8''", "")
                .orEmpty()
        }

        if (name.isEmpty()) {
            Uri.parse(url).path?.let { path ->
                name = path.substringAfterLast('/')
            }
        }

        name = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")

        if (name.length < 3) {
            val ext = when {
                mimetype?.contains("video") == true -> "mp4"
                mimetype?.contains("image") == true -> "jpg"
                else -> "bin"
            }
            name = "frigate_${System.currentTimeMillis()}.$ext"
        }
        return name
    }

    private fun downloadViaSystemManager(
        url: String,
        fileName: String,
        cookies: String?,
        userAgent: String,
        currentPageUrl: String?,
        mimetype: String?
    ) {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            if (!mimetype.isNullOrEmpty()) setMimeType(mimetype)
            cookies?.let { addRequestHeader("Cookie", it) }
            currentPageUrl?.let { addRequestHeader("Referer", it) }
            addRequestHeader("User-Agent", userAgent)
            setTitle(fileName)
            setDescription("Downloading from Frigate")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
            )
            applyDestination(this, fileName)
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(request)
        callbacks.onDownloadStarted(fileName)
        Log.d(TAG, "Download enqueued id=$id")
        monitorDownloadManager(id, fileName) {
            Log.w(TAG, "System download was rejected as unauthorised; retrying in-process")
            downloadDirect(url, fileName, cookies, userAgent, currentPageUrl, announceStart = false)
        }
    }

    private fun applyDestination(request: DownloadManager.Request, fileName: String) {
        val pref = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("download_location", "downloads") ?: "downloads"
        when (pref) {
            "pictures" -> request.setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, "Frigate/$fileName")
            "movies" -> request.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "Frigate/$fileName")
            "downloads_root" -> request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            else -> request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Frigate/$fileName")
        }
    }

    private fun destinationFile(fileName: String): File {
        val pref = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("download_location", "downloads") ?: "downloads"
        val (baseDir, subDir) = when (pref) {
            "pictures" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) to "Frigate"
            "movies" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES) to "Frigate"
            "downloads_root" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) to null
            else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) to "Frigate"
        }
        val parent = if (subDir != null) File(baseDir, subDir).apply { if (!exists()) mkdirs() } else baseDir
        return File(parent, fileName)
    }

    /**
     * True when the user has configured a client certificate, which the system
     * [DownloadManager] can never present.
     *
     * DownloadManager runs in its own process. It is handed the cookie and the
     * User-Agent, but the certificate lives in KeyChain and only reaches the network
     * through an SSLContext this app builds. A reverse proxy that requires the
     * certificate therefore answers 403 to a download that the WebView itself performs
     * successfully, which is what issue #36 reported for a Cloudflare Access deployment.
     *
     * Stored credentials are deliberately not part of this test. They are used both for
     * Frigate's own login, where the session cookie is enough and DownloadManager works,
     * and for Basic Auth on a reverse proxy, where it does not. The two cannot be told
     * apart here, so the credentials case is left to the retry in
     * [monitorDownloadManager] rather than paying the cost of an in-process download for
     * everyone who has ever entered a password.
     */
    private fun requiresClientCertificate(): Boolean =
        clientCertManager.getSavedAlias() != null

    /**
     * Resolve a destination the app is allowed to create.
     *
     * Scoped storage only lets an app write files it owns, so opening a path another app
     * wrote fails with EACCES even though the directory itself is writable. That is how a
     * download of an export whose file was already fetched once by the system
     * DownloadManager failed on a device here. Numbering a fresh name is what
     * DownloadManager does on a collision, so this both avoids the refusal and stops a
     * download quietly replacing a file the user may still want.
     */
    private fun newDestinationFile(fileName: String): File {
        val first = destinationFile(fileName)
        if (!first.exists()) return first

        val parent = first.parentFile ?: return first
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        for (n in 1..MAX_NAME_ATTEMPTS) {
            val candidate = File(parent, "$stem-$n$extension")
            if (!candidate.exists()) return candidate
        }
        // Absurdly unlikely, but a name is still needed and this one cannot collide.
        return File(parent, "$stem-${System.currentTimeMillis()}$extension")
    }

    /**
     * Fetch the file inside the app, using the shared Frigate HTTP client so the request
     * carries the same trust settings, client certificate and credentials as every other
     * call the app makes.
     *
     * The transfer runs in the injected [scope], which for the app is process-scoped, so
     * it survives the screen that started it. A download that ends any way other than
     * successfully takes its half-written file with it, so a truncated export is never
     * left behind looking like a finished one.
     *
     * [announceStart] is false when this is the retry after the system downloader was
     * turned away, so the user is not told twice that the same file is downloading.
     */
    private fun downloadDirect(
        url: String,
        fileName: String,
        cookies: String?,
        userAgent: String,
        currentPageUrl: String?,
        announceStart: Boolean = true,
    ) {
        if (announceStart) callbacks.onDownloadStarted(fileName)
        val notificationId = notifier.newId()
        notifier.started(notificationId, fileName)
        scope.launch {
            var unfinished: File? = null
            try {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", userAgent)
                        .apply {
                            cookies?.let { header("Cookie", it) }
                            currentPageUrl?.let { header("Referer", it) }
                        }
                        .build()

                    val client = OkHttpClientFactory.build(
                        url,
                        clientCertManager,
                        OkHttpClientFactory.Timeouts(connectSeconds = 30, readSeconds = 60),
                    )

                    client.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "Server returned ${response.code}" }
                        val body = checkNotNull(response.body) { "Server sent no content" }

                        val file = newDestinationFile(fileName)
                        unfinished = file
                        copyWithProgress(body, file, notificationId, fileName)
                        unfinished = null

                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(file.absolutePath),
                            arrayOf(null),
                            null
                        )

                        notifier.completed(notificationId, fileName, file)
                        withContext(Dispatchers.Main) {
                            callbacks.onDownloadCompleted(fileName, file)
                        }
                        Log.d(TAG, "Direct download finished: ${file.absolutePath}")
                    }
                }
            } catch (e: CancellationException) {
                unfinished?.delete()
                notifier.failed(notificationId, fileName, "cancelled")
                throw e
            } catch (e: Exception) {
                unfinished?.delete()
                val reason = e.message ?: "Unknown error"
                Log.e(TAG, "Direct download failed: $reason", e)
                notifier.failed(notificationId, fileName, reason)
                withContext(Dispatchers.Main) {
                    callbacks.onDownloadFailed(fileName, reason)
                }
            }
        }
    }

    /**
     * Stream the body to [file], updating the notification as it goes.
     *
     * The buffer is larger than the 8 KB default of `copyTo` because a Frigate export
     * runs to hundreds of megabytes, where the smaller buffer costs tens of thousands of
     * extra read and write calls for no benefit. Progress is reported on a timer rather
     * than per chunk, so a fast transfer does not spend its time rebuilding a
     * notification the user cannot read that quickly anyway.
     */
    private fun copyWithProgress(
        body: ResponseBody,
        file: File,
        notificationId: Int,
        fileName: String,
    ) {
        val total = body.contentLength()
        var written = 0L
        var lastUpdate = 0L
        body.byteStream().use { input ->
            FileOutputStream(file).use { out ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    written += read
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                        lastUpdate = now
                        notifier.progress(notificationId, fileName, written, total)
                    }
                }
            }
        }
    }

    /**
     * Poll the system DownloadManager until the requested download completes or fails.
     * Runs on the main-thread handler so snackbar/open callbacks land on the UI thread.
     *
     * A failure the server describes as unauthorised calls [onServerRejected] instead of
     * reporting the error, so the caller can retry the transfer in-process with the
     * app's own credentials. The failed entry is removed first, so the user is not left
     * with a failure notification next to a download that then succeeds. The retry does
     * not use DownloadManager, so it cannot bounce back here.
     */
    private fun monitorDownloadManager(
        downloadId: Long,
        fileName: String,
        onServerRejected: () -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        val startedAt = android.os.SystemClock.uptimeMillis()
        handler.postDelayed(object : Runnable {
            override fun run() {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                    ?: return
                val query = DownloadManager.Query().setFilterById(downloadId)
                dm.query(query)?.use { cursor ->
                    if (!cursor.moveToFirst()) return
                    val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                    when (cursor.getInt(statusIdx)) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val file = destinationFile(fileName).takeIf { it.exists() }
                            if (file != null) {
                                callbacks.onDownloadCompleted(fileName, file)
                            } else {
                                callbacks.onDownloadFailed(fileName, "File not found after download")
                            }
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reason = if (reasonIdx >= 0) cursor.getInt(reasonIdx) else -1
                            if (isAuthRejection(reason)) {
                                dm.remove(downloadId)
                                onServerRejected()
                            } else {
                                callbacks.onDownloadFailed(fileName, describeFailure(reason))
                            }
                        }
                        DownloadManager.STATUS_RUNNING,
                        DownloadManager.STATUS_PENDING,
                        DownloadManager.STATUS_PAUSED -> {
                            // Cancel the download if it's been sitting for over 15 minutes.
                            // DownloadManager will keep a paused job alive silently otherwise.
                            if (android.os.SystemClock.uptimeMillis() - startedAt > 15 * 60 * 1000L) {
                                dm.remove(downloadId)
                                callbacks.onDownloadFailed(fileName, "Download timed out")
                            } else {
                                handler.postDelayed(this, 1_000)
                            }
                        }
                    }
                }
            }
        }, 1_000)
    }

    /**
     * True when the server turned the download away for want of credentials.
     *
     * COLUMN_REASON carries an ERROR_* constant for transport failures but the raw HTTP
     * status for a response DownloadManager did not handle itself, and which of the two
     * a 401 or 403 arrives as varies by Android version, so both forms are matched.
     */
    private fun isAuthRejection(reason: Int): Boolean =
        reason == DownloadManager.ERROR_UNHANDLED_HTTP_CODE ||
            reason == HTTP_UNAUTHORIZED ||
            reason == HTTP_FORBIDDEN

    private fun describeFailure(reason: Int): String = when (reason) {
        DownloadManager.ERROR_CANNOT_RESUME -> "Cannot resume download"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage not found"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "File already exists"
        DownloadManager.ERROR_FILE_ERROR -> "File error"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP data error"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Insufficient space"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many redirects"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Unhandled HTTP code"
        DownloadManager.ERROR_UNKNOWN -> "Unknown error"
        in HTTP_STATUS_RANGE -> "Server returned $reason"
        else -> "Download failed (code: $reason)"
    }

    companion object {
        private const val TAG = "DownloadHandler"

        /** Copy buffer for the in-process download. See [copyWithProgress]. */
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /** Minimum gap between two progress notification updates. */
        private const val PROGRESS_INTERVAL_MS = 700L

        /** How many numbered names [newDestinationFile] tries before falling back. */
        private const val MAX_NAME_ATTEMPTS = 999

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403

        /** HTTP status codes DownloadManager may report verbatim in COLUMN_REASON. */
        private val HTTP_STATUS_RANGE = 400..599

        /** Media type for [file], shared by the open intent and the download notification. */
        fun mimeTypeFor(file: File): String = when {
            file.name.endsWith(".mp4", true) -> "video/mp4"
            file.name.endsWith(".avi", true) -> "video/x-msvideo"
            file.name.endsWith(".mov", true) -> "video/quicktime"
            file.name.endsWith(".mkv", true) -> "video/x-matroska"
            file.name.endsWith(".jpg", true) || file.name.endsWith(".jpeg", true) -> "image/jpeg"
            file.name.endsWith(".png", true) -> "image/png"
            else -> "video/*"
        }

        /** Launch an intent to open [file] with an external viewer. */
        fun openFile(context: Context, file: File) {
            try {
                val mimeType = mimeTypeFor(file)
                val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                } else {
                    Uri.fromFile(file)
                }
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                } else {
                    Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error opening file: ${e.message}")
                Toast.makeText(context, "Error opening file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
