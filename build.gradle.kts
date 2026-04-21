// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    // Walk the whole source tree — Gradle excludes build/generated dirs on its own.
    source.from(files("app/src/main/java", "app/src/test/java"))
    parallel = true
    ignoreFailures = true // non-blocking for build; surfaced in console + HTML report
    config.from(files("$rootDir/config/detekt/detekt.yml"))
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