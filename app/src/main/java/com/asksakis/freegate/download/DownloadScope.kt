package com.asksakis.freegate.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The scope in-process downloads run in.
 *
 * A Frigate export can be hundreds of megabytes, so a transfer has to outlive the screen
 * that started it. Running it in a Fragment's lifecycleScope would cancel it the moment
 * the user rotated the device or navigated away, and the half-written file would be
 * deleted with it. This scope is tied to the process instead, which is the same lifetime
 * the system DownloadManager gives the downloads it handles.
 *
 * A SupervisorJob keeps one failed download from cancelling any other. Nothing cancels
 * this scope: it holds no resources of its own when idle, and the work it carries is
 * exactly the work that must not be cancelled early.
 */
object DownloadScope {

    val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
