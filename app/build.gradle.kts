plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The Canon driver (Gutenprint) is cross-compiled by native/build-android.sh before Gradle runs
// (the GitHub build does this). Without it the app still builds; printing then reports the driver is missing.
val gutenprintLib = rootProject.file("native/prebuilt/arm64-v8a/install/lib/libgutenprint.a")
val hasGutenprint = gutenprintLib.exists()

android {
    namespace = "io.printprep.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.printprep.app"
        minSdk = 33          // Android 13+
        targetSdk = 35
        versionCode = 1
        versionName = "0.2-phase2"
        ndk { abiFilters += "arm64-v8a" }
    }

    providers.gradleProperty("ndkVer").orNull?.let { ndkVersion = it }
    if (hasGutenprint) {
        externalNativeBuild {
            cmake { path = file("src/main/cpp/CMakeLists.txt") }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
