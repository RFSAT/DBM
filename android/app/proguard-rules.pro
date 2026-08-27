# ============================================================================
# DBM R8 / ProGuard keep rules
# ----------------------------------------------------------------------------
# R8 (minification + obfuscation) is ENABLED for the release build to meet
# Google Play's app-optimisation requirement. The risk on this app is that R8
# strips classes reached only via JNI or reflection (the ML stack), causing
# crashes that appear ONLY in the release build, never in the CI debug build.
#
# The LiteRT, MediaPipe and ML Kit AARs already ship their own *consumer* keep
# rules that AGP merges automatically. The rules below are explicit
# belt-and-suspenders on top of that, plus app-specific keeps.
# ============================================================================

# ---- ML runtimes: preserve everything reached via JNI / reflection ----------
# LiteRT (the runtime DBM uses for all .tflite inference incl. sign detector).
-keep class com.google.ai.edge.litert.** { *; }
-keep class org.tensorflow.lite.** { *; }
# MediaPipe (Face Landmarker for driver analysis).
-keep class com.google.mediapipe.** { *; }
# ML Kit (text recognition for speed-limit OCR).
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }

# Native-method-bearing classes must keep their native methods and the classes
# that declare them, or the JNI lookup fails at runtime.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# TensorFlow Lite / LiteRT use @UsedByReflection and keep annotations internally;
# preserve members the runtime looks up by name.
-keepclassmembers class org.tensorflow.lite.** { *; }
-keepclassmembers class com.google.ai.edge.litert.** { *; }

# ---- App model-decoding / data classes referenced across the ML boundary ----
# Keep the app's detector data classes (results parsed from native outputs) with
# their fields, so any reflective/serialised access stays valid.
-keep class com.rfsat.dms.detect.** { *; }
-keep class com.rfsat.dms.fusion.** { *; }

# ---- Kotlin / Compose metadata (safe, standard keeps) -----------------------
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ---- dontwarn: optional references R8 cannot resolve (known-safe absences) ---
# MediaPipe internal profiling / graph-template protos (features we don't invoke).
-dontwarn com.google.mediapipe.proto.**
# AutoValue annotation-processing classes — compile-time only, absent at runtime.
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**
# TFLite/LiteRT GPU delegate options — referenced even when a delegate is unused.
-dontwarn org.tensorflow.lite.gpu.**
-dontwarn com.google.ai.edge.litert.**
# Protobuf / gRPC-style optional deps pulled transitively by the ML stack.
-dontwarn com.google.protobuf.**
