plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "aa.projek.mp.max"
    compileSdk = 35

    defaultConfig {
        applicationId = "aa.projek.mp.max"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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



dependencies {
    // Klien ADB di dalam aplikasi (debug nirkabel + debugging USB/TCP), tanpa Shizuku.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
