plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val libboxAar = file("libs/libbox.aar")
if (!libboxAar.exists()) {
    logger.warn(
        "VProxies SA: app/libs/libbox.aar is missing. Build it with the 'Build VProxies SA Android APK' " +
            "GitHub Actions workflow or follow android/README.md before assembling the app.",
    )
}

// Release signing comes from environment variables so no key is stored in the repository.
val keystorePath: String? = System.getenv("VPROXIES_KEYSTORE")
val hasReleaseKey = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

android {
    namespace = "app.vproxies.sa"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.vproxies.sa"
        minSdk = 29
        targetSdk = 35
        versionCode = 10400
        versionName = "1.4.0"
        base.archivesName.set("VProxiesSA-$versionName")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("VPROXIES_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VPROXIES_KEY_ALIAS")
                keyPassword = System.getenv("VPROXIES_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        // Release builds are assembled in CI; run `./gradlew lint` separately for the full report.
        checkReleaseBuilds = false
        abortOnError = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
}
