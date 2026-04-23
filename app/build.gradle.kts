import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

android {
    namespace = "com.asksakis.freegate"
    compileSdk = 35

    signingConfigs {
        create("release") {
            // Local dev reads from local.properties; CI injects via env vars. Falling
            // through to env keeps the gradle file itself free of any secret material.
            val storeFilePath = localProperties.getProperty("RELEASE_STORE_FILE")
                ?: System.getenv("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
                    ?: System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                    ?: System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
                    ?: System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.asksakis.freegate"
        minSdk = 29
        targetSdk = 35
        versionCode = 9
        versionName = "2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            // Reproducible-build: strip VCS info so commit-hash embedding doesn't bleed
            // into the manifest. AGP 8.3+.
            vcsInfo.include = false
        }
    }

    // F-Droid scanner flags the AGP-emitted "Dependency metadata" extra signing block
    // as non-free content. Disable for both APK and AAB to keep the scanner happy.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Reproducible-build: F-Droid's guide (https://f-droid.org/docs/Reproducible_Builds/)
    // recommends disabling AGP's auto-generated baseline profile because the `.prof` /
    // `.profm` ordering is non-deterministic across build hosts and its checksum leaks
    // into classes.dex via R8 optimization.
    tasks.whenTaskAdded {
        if (name.contains("ArtProfile")) {
            enabled = false
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            // Default flavor: includes the in-app updater that polls GitHub Releases
            // and installs APKs via REQUEST_INSTALL_PACKAGES.
            buildConfigField("boolean", "ENABLE_UPDATE_CHECK", "true")
        }
        create("fdroid") {
            dimension = "distribution"
            // F-Droid handles updates through its own repository, so the in-app
            // updater is compiled out (and REQUEST_INSTALL_PACKAGES is stripped via
            // the flavor-specific AndroidManifest override).
            buildConfigField("boolean", "ENABLE_UPDATE_CHECK", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

androidComponents {
    onVariants { variant ->
        val versionName = android.defaultConfig.versionName
        variant.outputs.forEach { output ->
            val apkOutput = output as? com.android.build.api.variant.impl.VariantOutputImpl
            apkOutput?.outputFileName?.set("phylax-${versionName}-${variant.name}.apk")
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    
    // Coroutines support for better performance
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Frigate WebSocket client + API login (system WebView cookie store drives the app UI,
    // but the background notification service needs an independent HTTP/WS stack).
    implementation(libs.okhttp)

    // EncryptedSharedPreferences for the Frigate account password.
    implementation(libs.androidx.security.crypto)

    // WorkManager periodic watchdog that revives the alert FGS when OEM doze kills it.
    implementation(libs.androidx.work.runtime.ktx)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}