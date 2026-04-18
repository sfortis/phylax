// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// Redirect build output to local filesystem (source lives on NFS mount).
// Override with: -PfrigateViewerBuildRoot=/custom/path or FRIGATE_VIEWER_BUILD_ROOT env var.
val configuredBuildRoot = providers.gradleProperty("frigateViewerBuildRoot").orNull
    ?: System.getenv("FRIGATE_VIEWER_BUILD_ROOT")
    ?: (System.getProperty("user.home") + "/frigate-viewer-build")
val localBuildRoot = file(configuredBuildRoot)
allprojects {
    val subDir = if (path == ":") "root" else path.removePrefix(":").replace(":", "/")
    layout.buildDirectory = localBuildRoot.resolve(subDir)
}