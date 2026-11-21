import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.tristan.freshwall"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lewisfab.freshwall"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }

    // Signing keys live in signing/, which isn't in the repository (see README.md). Without them,
    // debug builds use Android's standard debug key and release builds come out unsigned.
    signingConfigs {
        // Optional fixed debug key, so debug builds keep updating an installed copy across machines.
        val sideload = rootProject.file("signing/debug.keystore")
        if (sideload.exists()) {
            create("sideload") {
                storeFile = sideload
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        // Google Play upload key. Play re-signs releases with its own key.
        val upload = rootProject.file("signing/upload.properties")
        if (upload.exists()) {
            val props = Properties().apply { upload.inputStream().use { load(it) } }
            create("upload") {
                storeFile = rootProject.file("signing/" + props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("sideload")?.let { signingConfig = it }
        }
        getByName("release") {
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    buildFeatures {
        buildConfig = true // for the version in the Wallhaven user agent
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Debug builds use their own ID, so they install alongside the Play version.
// The release applicationId above is permanent on Google Play.
androidComponents {
    onVariants(selector().withBuildType("debug")) { it.applicationId.set("dev.tristan.freshwall") }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
