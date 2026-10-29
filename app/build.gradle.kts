plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aastudio.live"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aastudio.live"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "2.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        // Keystore tetap di repo agar pembaruan APK bisa dipasang menimpa versi lama.
        // Ini kunci publik untuk sideload pribadi, bukan untuk Play Store.
        create("aa") {
            storeFile = rootProject.file("ci/aa-studio.keystore")
            storePassword = "aastudio"
            keyAlias = "aastudio"
            keyPassword = "aastudio"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("aa")
        }
        debug {
            signingConfig = signingConfigs.getByName("aa")
        }
    }
}

// Sengaja tanpa dependensi tambahan: hanya Android SDK (APK kecil, RAM hemat).
