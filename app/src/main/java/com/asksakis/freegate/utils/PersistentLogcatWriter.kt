package com.asksakis.freegate.utils

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Captures the device's logcat stream to rotating files so a bug report can carry the
 * history that led up to the fault, rather than whatever the buffer still holds when the
 * user finally goes looking.
 *
 * logcat does the rotation itself through `-f`, `-r` and `-n`, so the cost is one forked
 * process plus disk writes. Files land in the app's external files dir under `logs/`.
 *
 * Retention is a day: [cleanupOldLogs] runs at start and drops anything older, while the
 * size rotation is the backstop that keeps a noisy day bounded.
 *
 * Lifecycle: started once from [com.asksakis.freegate.FrigateViewerApp]. The forked process
 * dies with the app process, so there is no stop path to get wrong.
 */
object PersistentLogcatWriter {

    private const val TAG = "PersistentLog"
    private const val MAX_KB_PER_FILE = 2_048
    private const val MAX_FILE_COUNT = 4
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    private const val SUMMARY_ENTRY = "device.txt"
    // How long a built archive stays in the cache. Long enough for a share target that is
    // still holding the granted URI, such as a half-written mail draft, to read it.
    private const val SNAPSHOT_KEEP_MS = 60L * 60L * 1000L

    @Volatile private var process: Process? = null

    @Synchronized
    fun start(context: Context) {
        if (process != null) return
        val logsDir = File(context.getExternalFilesDir(null), "logs")
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            Log.w(TAG, "Could not create logs dir at ${logsDir.absolutePath}")
            return
        }
        // Enforce retention before logcat reopens the files, so we never delete one it is
        // actively rotating.
        cleanupOldLogs(logsDir)
        val target = File(logsDir, "app.log").absolutePath
        try {
            process = Runtime.getRuntime().exec(
                arrayOf(
                    "logcat",
                    "-v", "threadtime",
                    "-f", target,
                    "-r", MAX_KB_PER_FILE.toString(),
                    "-n", MAX_FILE_COUNT.toString(),
                )
            )
            Log.d(TAG, "Logging to $target ($MAX_KB_PER_FILE KB x $MAX_FILE_COUNT)")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start the log writer: ${e.message}")
        }
    }

    private fun cleanupOldLogs(logsDir: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        logsDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("app.log") && it.lastModified() < cutoff }
            ?.forEach { stale ->
                if (stale.delete()) Log.d(TAG, "Dropped stale log ${stale.name}")
            }
    }

    /**
     * Build a share [Intent] carrying the retained history as one zip, with [SUMMARY_ENTRY]
     * first so a reader sees the versions and the connection setup before the log itself.
     *
     * Every line passes through [redact] on the way in. The app logs the Frigate URL and,
     * on login, the head of the session cookie, and this archive is built to be attached to
     * a public issue.
     *
     * Returns null when there is nothing to send. Android does not always let an app read
     * its own logcat, and then no files exist at all, which the caller has to say out loud
     * rather than open an empty share sheet.
     */
    suspend fun buildShareIntent(context: Context): Intent? = withContext(Dispatchers.IO) {
        val logsDir = File(context.getExternalFilesDir(null), "logs")
        // Oldest first, so the archive reads in chronological order.
        val files = logsDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("app.log") }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()
        if (files.isEmpty()) {
            Log.w(TAG, "No log files at ${logsDir.absolutePath}")
            return@withContext null
        }

        val sharedDir = File(context.cacheDir, "shared_logs").apply { mkdirs() }
        // Keep recent archives. Deleting them all on every build would pull the file out
        // from under a share target that holds the URI but has not read it yet.
        val staleBefore = System.currentTimeMillis() - SNAPSHOT_KEEP_MS
        sharedDir.listFiles()?.forEach { if (it.lastModified() < staleBefore) it.delete() }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zipFile = File(sharedDir, "phylax-logs-$stamp.zip")
        var written = 0
        return@withContext try {
            ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(SUMMARY_ENTRY))
                zip.write(buildSummary(context, stamp).toByteArray())
                zip.closeEntry()
                val writer = OutputStreamWriter(zip)
                for (file in files) {
                    // logcat owns these files and rotates them underneath us, so a name
                    // listed a moment ago can already be gone. Skip what vanished rather
                    // than lose the whole archive to one exception.
                    val copied = runCatching {
                        zip.putNextEntry(ZipEntry(file.name))
                        file.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                writer.write(redact(line))
                                writer.write("\n")
                            }
                        }
                        writer.flush()
                        zip.closeEntry()
                    }
                    if (copied.isSuccess) {
                        written++
                    } else {
                        Log.w(TAG, "Skipped ${file.name}: ${copied.exceptionOrNull()?.message}")
                    }
                }
            }
            if (written == 0) {
                Log.w(TAG, "Every log file vanished while zipping")
                return@withContext null
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zipFile,
            )
            Log.i(TAG, "Sharing $written log files as ${zipFile.name} (${zipFile.length() / 1024} KB)")
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Phylax logs $stamp")
                // ClipData makes the read grant stick on targets that take the attachment
                // from the clip rather than from EXTRA_STREAM.
                clipData = ClipData.newRawUri("Phylax logs", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not build the log archive: ${e.message}")
            null
        }
    }

    /** Strip session material so an attached archive cannot hand anyone a way in. */
    private fun redact(line: String): String =
        line.replace(TOKEN_REGEX, "frigate_token=<redacted>")
            .replace(BEARER_REGEX, "Authorization: <redacted>")

    /**
     * The facts a report otherwise costs a round trip to establish: which build, which
     * phone, how the app reaches Frigate, and whether the pieces that break most often are
     * even configured. Credentials themselves are never read, only whether they are set.
     */
    private fun buildSummary(context: Context, stamp: String): String {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val appVersion = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val credentials = com.asksakis.freegate.auth.CredentialsStore.getInstance(context)
        return buildString {
            appendLine("Phylax log bundle")
            appendLine("captured: $stamp")
            appendLine("app: $appVersion (${context.packageName})")
            appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("connection mode: ${prefs.getString("connection_mode", "auto")}")
            appendLine("internal url set: ${!prefs.getString("internal_url", null).isNullOrBlank()}")
            appendLine("external url set: ${!prefs.getString("external_url", null).isNullOrBlank()}")
            appendLine("credentials stored: ${credentials.hasCredentials()}")
            appendLine("client certificate: ${ClientCertManager.getInstance(context).getSavedAlias() != null}")
            appendLine("notifications enabled: ${prefs.getBoolean("notifications_enabled", false)}")
        }
    }

    private val TOKEN_REGEX = Regex("frigate_token=[^;\\s\"]+")
    private val BEARER_REGEX = Regex("Authorization: [^\\s]+ [^\\s\"]+")
}
