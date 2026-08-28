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
val dmsVersionEpoch = 1
val dmsVersionMajor = 20
val dmsVersionMinor = 64
val dmsVersionName = "$dmsVersionEpoch.$dmsVersionMajor.$dmsVersionMinor"

android {
    namespace = "com.rfsat.dms"
    compileSdk = 36

    defaultConfig {
        // Play Store package ID (permanent once published). Kotlin namespace stays
        // com.rfsat.dms so no source refactor is needed — the two are independent.
        applicationId = "com.DBM"
        minSdk = 26
        targetSdk = 36
        versionCode = dmsVersionEpoch * 1_000_000 + dmsVersionMajor * 1000 + dmsVersionMinor
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
            // Minification (R8) is ENABLED for Play's app-optimisation / obfuscation
            // requirement. R8 on an ML-heavy app (LiteRT + MediaPipe + ML Kit) can
            // strip reflection/JNI-loaded code and cause release-only crashes, so:
            //  - the ML AARs ship their own consumer keep rules (merged by AGP), and
            //  - proguard-rules.pro adds explicit keeps for those packages + the
            //    app's own model-decoding classes as belt-and-suspenders.
            // MUST be verified on a real device with the RELEASE build — R8
            // breakage does not appear in the debug build that CI compiles.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Include native debug symbols (FULL) in the bundle so crashes/ANRs
            // inside the TFLite .so libraries show readable function names in the
            // Play Console instead of raw addresses. FULL gives the most detail;
            // use SYMBOL_TABLE if bundle size becomes a concern.
            ndk { debugSymbolLevel = "FULL" }
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
    packaging {
        jniLibs {
            // 16 KB page-size support: native .so libraries must be stored
            // UNCOMPRESSED and page-aligned so the loader can mmap them directly
            // on devices with 16 KB memory pages. Legacy packaging compresses
            // them, which defeats alignment. This is the part of 16 KB
            // compliance that is under our control — the rest depends on each
            // dependency shipping 16 KB-aligned .so files (see README/CHANGELOG).
            useLegacyPackaging = false
        }
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Tracked as scheduled maintenance, not build-breakers: dependency
        // bumps. (targetSdk is now 36; OldTargetApi stays suppressed only so a
        // future API bump doesn't break the build before it's scheduled.)
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
    // 16 KB: CameraX 1.4.x ships libimage_processing_util_jni.so and
    // libsurface_util_jni.so aligned to 4 KB. Bumped to the 1.5.x line, which is
    // 16 KB-aligned. The APIs used here (ProcessCameraProvider, Preview,
    // ImageAnalysis, PreviewView) are source-compatible across 1.4 -> 1.5.
    val camerax = "1.5.0"
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

    // Driver analysis — MediaPipe Face Landmarker.
    // 16 KB: 0.10.20 ships libmediapipe_tasks_vision_jni.so aligned to 4 KB.
    // Per the MediaPipe release notes, all current Google Maven packages are
    // 16 KB-aligned, and 0.10.26.1 additionally restores ARM v7 (32-bit).
    implementation("com.google.mediapipe:tasks-vision:0.10.26.1")

    // Road object detection — LiteRT (formerly TensorFlow Lite).
    //
    // 16 KB PAGE SIZE: org.tensorflow:tensorflow-lite:2.16.1 (and 2.17) ship
    // native .so files aligned to 4 KB, which Google Play rejects. Those are
    // prebuilt binaries and cannot be re-aligned with linker flags. LiteRT is
    // Google's official successor to TF Lite and its natives ARE 16 KB aligned,
    // so this is a coordinate swap: the Java API (org.tensorflow.lite.Interpreter,
    // NnApiDelegate, GpuDelegateFactory) keeps the same package names, so no
    // source change was needed for the Interpreter path.
    //
    // org.tensorflow:tensorflow-lite-task-vision:0.4.4 was REMOVED: it is the
    // last release of an abandoned line, is not 16 KB aligned, and had no fixed
    // version. It only backed the EfficientDet-Lite0 fallback in RoadAnalyzer,
    // which was unreachable because yolo26n.tflite is committed. See
    // PLAY-COMPLIANCE.md.
    val litert = "1.4.0"
    implementation("com.google.ai.edge.litert:litert:$litert")
    implementation("com.google.ai.edge.litert:litert-gpu:$litert")
    // GPU delegate factory/options classes (GpuDelegateFactory$Options) live in
    // the -gpu-api artifact; without it the GPU delegate fails to resolve at
    // runtime (NoClassDefFoundError) and the app silently falls back to NNAPI.
    implementation("com.google.ai.edge.litert:litert-gpu-api:$litert")

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
