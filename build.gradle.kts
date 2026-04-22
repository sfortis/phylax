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

// Optional: redirect build output to a local filesystem (useful when source lives on
// an NFS mount or similar). Opt-in via `-PfrigateViewerBuildRoot=/path` or the
// `FRIGATE_VIEWER_BUILD_ROOT` env var — when neither is set, we fall back to Gradle's
// default `<module>/build` so external build servers (F-Droid CI) find the APK under
// the standard `app/build/outputs/apk/...` path they expect.
val configuredBuildRoot = providers.gradleProperty("frigateViewerBuildRoot").orNull
    ?: System.getenv("FRIGATE_VIEWER_BUILD_ROOT")
if (configuredBuildRoot != null) {
    val localBuildRoot = file(configuredBuildRoot)
    allprojects {
        val subDir = if (path == ":") "root" else path.removePrefix(":").replace(":", "/")
        layout.buildDirectory = localBuildRoot.resolve(subDir)
    }
}