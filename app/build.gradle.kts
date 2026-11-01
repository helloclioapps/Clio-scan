import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val versionProps = Properties().apply { load(rootProject.file("version.properties").inputStream()) }

android {
    namespace = "app.cliopdf.scan"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.cliopdf.scan"
        minSdk = 26
        targetSdk = 35
        versionCode = versionProps.getProperty("code").toInt()
        versionName = versionProps.getProperty("name")
        // phones only (drops emulator x86 builds of the bundled text-recognition engine; ~25 MB smaller)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("CLIO_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("CLIO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CLIO_KEY_ALIAS")
                keyPassword = System.getenv("CLIO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Google document scanner (edge detection, crop, filters) and QR code scanner, both run on the phone
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // On-device text recognition, model bundled in the app (works with no connection at all)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // PDF editing (Apache License 2.0)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
