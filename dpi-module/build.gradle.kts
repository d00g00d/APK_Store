plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.d00g00d.apkstore.dpi"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.d00g00d.apkstore.dpi"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    // Pine framework for hooking
    implementation("top.canyie.pine:core:0.3.1")
}