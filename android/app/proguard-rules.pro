# ---- Keep rules: preserve runtime-reflected library classes ----
-keep class com.google.mediapipe.** { *; }
-keep class org.tensorflow.** { *; }
-keep class com.google.mlkit.** { *; }

# ---- dontwarn rules: optional references R8 cannot resolve ----
# These classes are referenced by the libraries but are not on the runtime
# classpath (optional features, compile-time-only annotation processing, or
# delegate options we don't use). R8 fails the build on missing classes by
# default; these tell it the absences are known and safe.

# MediaPipe internal profiling / graph-template protos (profiling features
# we do not invoke).
-dontwarn com.google.mediapipe.proto.**

# AutoValue annotation-processing classes — compile-time only, never present
# at runtime (pulled in transitively via the ML stack).
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**

# TensorFlow Lite GPU delegate options — referenced even when the GPU delegate
# is not used (the app uses NNAPI / CPU fallback for the sign detector).
-dontwarn org.tensorflow.lite.gpu.**
