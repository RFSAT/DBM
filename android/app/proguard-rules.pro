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
# --- MediaPipe + R8 stack-walk crash fix (issue google-ai-edge/mediapipe#6138) --
# FaceLandmarker.createFromOptions -> TaskRunner -> Graph.<clinit> runs a helper
# that WALKS THE CALL STACK to find its caller BY ORIGINAL CLASS NAME. R8 both
# (a) renames the classes involved and (b) — because we use the *optimizing*
# proguard config — INLINES/MERGES the very stack frames the walk expects, so at
# runtime on the release build (only!) it throws:
#   NoClassDefFoundError: com.google.mediapipe.framework.Graph
#   Caused by: IllegalStateException: no caller found on the stack for: <renamed>
# and DriverAnalyzer init fails => NO driver / microsleep detection.
#
# Two-part fix. (1) Keep the framework class NAMES (not just the classes) so the
# stack-walk's name lookup still matches:
-keepnames class com.google.mediapipe.framework.** { *; }
-keep class com.google.mediapipe.framework.** { *; }
# (2) Stop R8 from inlining/merging away the frames the walk relies on. These
# optimizations are the ones that remove caller frames; disabling them app-wide
# is the safe choice for a safety-critical feature (tiny size cost, correctness
# guaranteed). Class-merging and method inlining are the culprits here.
-optimizations !method/inlining/*,!class/merging/*
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
# MediaPipe's caller-identification helper lives in its shaded Guava/flogger deps,
# which are NOT under com.google.mediapipe.* — keep those names too so the walk
# can resolve whatever class it expects on the stack.
-keep class com.google.common.** { *; }
-keepnames class com.google.common.** { *; }
-dontwarn com.google.common.**
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
# (Attributes are kept above via the MediaPipe fix, which is a superset.)
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
# --- protobuf-lite + R8 field-reflection crash fix -----------------------------
# MediaPipe's Graph.loadBinaryGraph serialises a protobuf (com.google.protobuf.Any
# etc.). Protobuf-Lite finds fields by their ORIGINAL names via reflection; R8
# renames them (typeUrl_ -> c), so at runtime it throws:
#   RuntimeException: Field typeUrl_ for com.google.protobuf.Any not found
# and FaceLandmarker/DriverAnalyzer init fails. The official protobuf fix is to
# keep generated message classes and the runtime's fields from being renamed.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keep class com.google.protobuf.** { *; }
-keepclassmembers class com.google.protobuf.** { <fields>; }
-keepnames class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# --- MapLibre GL Native (navigation maps) ------------------------------------
# MapLibre uses JNI heavily; its native<->Java bridge classes must be kept intact
# or the release (R8-minified) build crashes at runtime when the map initializes.
-keep class org.maplibre.android.** { *; }
-keep class com.mapbox.** { *; }
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.**
