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
import com.asksakis.freegate.utils.UrlUtils
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * Handles all Frigate download flows:
 *  - External/public hosts: delegates to Android's system [DownloadManager].
 *  - Internal/self-signed hosts: downloads directly with relaxed trust and, when
 *    configured, the user's saved client certificate (mTLS) so Frigate proxies
 *    behind Cloudflare Access / nginx client-cert auth still serve downloads.
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

            if (UrlUtils.isPrivateIpUrl(absoluteUrl)) {
                Log.d(TAG, "Internal URL - using direct download with trust-all + optional mTLS")
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
            for (pattern in patterns) {
                val match = pattern.find(contentDisposition) ?: continue
                name = match.groupValues[1]
                    .replace("\"", "")
                    .replace("'", "")
                    .replace("UTF-8''", "")
                break
            }
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
        monitorDownloadManager(id, fileName)
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

    private fun downloadDirect(
        url: String,
        fileName: String,
        cookies: String?,
        userAgent: String,
        currentPageUrl: String?
    ) {
        callbacks.onDownloadStarted(fileName)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 30_000
                        readTimeout = 30_000
                        setRequestProperty("User-Agent", userAgent)
                        cookies?.let { setRequestProperty("Cookie", it) }
                        currentPageUrl?.let { setRequestProperty("Referer", it) }
                    }

                    if (connection is HttpsURLConnection) {
                        configureHttpsTrustAll(connection)
                    }

                    try {
                        connection.connect()
                        val code = connection.responseCode
                        if (code != HttpURLConnection.HTTP_OK) {
                            throw IllegalStateException("Server returned $code")
                        }

                        val file = destinationFile(fileName)
                        connection.inputStream.use { input ->
                            FileOutputStream(file).use { out ->
                                input.copyTo(out)
                            }
                        }
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(file.absolutePath),
                            arrayOf(null),
                            null
                        )

                        withContext(Dispatchers.Main) {
                            callbacks.onDownloadCompleted(fileName, file)
                        }
                        Log.d(TAG, "Direct download finished: ${file.absolutePath}")
                    } finally {
                        try { connection.disconnect() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Direct download failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    callbacks.onDownloadFailed(fileName, e.message ?: "Unknown error")
                }
            }
        }
    }

    /** Install a trust-all SSL context and the saved client cert (mTLS) if available. */
    private fun configureHttpsTrustAll(connection: HttpsURLConnection) {
        val trustAll = arrayOf<X509TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(clientCertManager.buildKeyManagers(), trustAll, java.security.SecureRandom())
        connection.sslSocketFactory = ctx.socketFactory
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
    }

    /**
     * Poll the system DownloadManager until the requested download completes or fails.
     * Runs on the main-thread handler so snackbar/open callbacks land on the UI thread.
     */
    private fun monitorDownloadManager(downloadId: Long, fileName: String) {
        val handler = Handler(Looper.getMainLooper())
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
                            callbacks.onDownloadFailed(fileName, describeFailure(reason))
                        }
                        DownloadManager.STATUS_RUNNING,
                        DownloadManager.STATUS_PENDING -> {
                            handler.postDelayed(this, 1_000)
                        }
                    }
                }
            }
        }, 1_000)
    }

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
        else -> "Download failed (code: $reason)"
    }

    companion object {
        private const val TAG = "DownloadHandler"

        /** Launch an intent to open [file] with an external viewer. */
        fun openFile(context: Context, file: File) {
            try {
                val mimeType = when {
                    file.name.endsWith(".mp4", true) -> "video/mp4"
                    file.name.endsWith(".avi", true) -> "video/x-msvideo"
                    file.name.endsWith(".mov", true) -> "video/quicktime"
                    file.name.endsWith(".mkv", true) -> "video/x-matroska"
                    file.name.endsWith(".jpg", true) || file.name.endsWith(".jpeg", true) -> "image/jpeg"
                    file.name.endsWith(".png", true) -> "image/png"
                    else -> "video/*"
                }
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
