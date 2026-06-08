// :radio-helper — standalone companion APK whose ONLY job is to toggle
// Wi-Fi and Bluetooth on behalf of Super Drop.
//
// WHY A SEPARATE APK: the radio-enable APIs are targetSdkVersion-gated, not
// permission-gated. Per the AOSP docs, WifiManager.setWifiEnabled() works
// only for apps targeting API <= 28, and BluetoothAdapter.enable() only for
// apps targeting API <= 32. The main :app must target a modern SDK (scoped
// storage, FGS types, notifications, the HCE wake, ...), so it CANNOT hold
// the legacy capability itself. This module targets API 28 so the OS applies
// "legacy rules" and lets it flip both radios silently — the same trick used
// by Tasker Settings / MacroDroid Helper. DO NOT raise targetSdk here.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.superdrop.radiohelper"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "dev.superdrop.radiohelper"
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        // INTENTIONALLY LOW — this is the whole point of the module.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Play bars apps targeting an old API; this is a sideloaded helper,
        // so the deliberately-low targetSdk must not fail the build.
        disable += "ExpiredTargetSdkVersion"
    }
}

kotlin {
    jvmToolchain(17)
}

// No AndroidX/3rd-party deps on purpose: a low-targetSdk APK kept to pure
// framework APIs avoids manifest-merger / library-minSdk friction.
dependencies {
}
