import com.android.build.api.variant.impl.VariantOutputImpl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// ---------------------------------------------------------------------------
// Versioning policy (project convention):
//   MAJOR — incremented when new features are added
//   MINOR — incremented for corrections/bug fixes
//   The version number appears in every produced package filename and in the
//   in-app About screen (via BuildConfig.VERSION_NAME).
// ---------------------------------------------------------------------------
val dmsVersionMajor = 18
val dmsVersionMinor = 7
val dmsVersionName = "$dmsVersionMajor.$dmsVersionMinor"

android {
    namespace = "com.rfsat.dms"
    compileSdk = 35

    defaultConfig {
        // Play Store package ID (permanent once published). Kotlin namespace stays
        // com.rfsat.dms so no source refactor is needed — the two are independent.
        applicationId = "com.DBM"
        minSdk = 26
        targetSdk = 35
        versionCode = dmsVersionMajor * 1000 + dmsVersionMinor
        versionName = dmsVersionName
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            // Values come from environment variables populated by CI from GitHub
            // secrets (KEYSTORE_BASE64 is decoded to a file by the workflow).
            // If the keystore file is absent (e.g. local debug build with no
            // secrets), this config is simply not applied — see buildTypes.
            val storePath = System.getenv("KEYSTORE_FILE")
            if (storePath != null && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = System.getenv("STORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Minification is OFF during active development: R8 on an ML-heavy
            // app (TFLite + MediaPipe + ML Kit) can strip reflection-loaded code
            // and cause launch-time crashes that don't show at build time. The
            // proguard-rules.pro (keep + dontwarn rules) is already in place, so
            // re-enabling for a Play Store build is just flipping this to true
            // and testing the shrunk APK thoroughly.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Apply the release signing config only when the keystore is present
            // (CI with secrets). Otherwise the build stays unsigned rather than
            // failing — useful for local/debug CI without secrets.
            val storePath = System.getenv("KEYSTORE_FILE")
            if (storePath != null && file(storePath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    androidResources { noCompress += listOf("tflite", "task") }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Tracked as scheduled maintenance, not build-breakers:
        // dependency bumps and the targetSdk 36 move (needs AGP/Gradle bump).
        disable += listOf("GradleDependency", "OldTargetApi")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Versioned package filenames: DMS-v1.0-debug.apk / DMS-v1.0-release.apk
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as? VariantOutputImpl)?.outputFileName?.set(
                "DBM-v${dmsVersionName}-${variant.name}.apk")
        }
    }
}

dependencies {
    val media3 = "1.5.1"
    val camerax = "1.4.1"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // Phone cameras (Phase 1)
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // RTSP playback (Phase 2 — Raspberry Pi nodes)
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    // Driver analysis — MediaPipe Face Landmarker
    implementation("com.google.mediapipe:tasks-vision:0.10.20")

    // Road object detection — TFLite
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
    // Core interpreter + NNAPI delegate for the raw YOLO26 decode path
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    // GPU delegate factory/options classes (GpuDelegateFactory$Options) live in the
    // -gpu-api artifact; without it the GPU delegate fails to resolve at runtime
    // (NoClassDefFoundError) and the app silently falls back to NNAPI. Pin to match.
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.16.1")

    // Speed-limit sign reading — ML Kit on-device text recognition
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Task.await() bridge used by SignAnalyzer
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Vehicle speed via GNSS
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Event database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
}
