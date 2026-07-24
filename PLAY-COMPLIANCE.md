# Google Play compliance — four Console items

Two of the four are **hard requirements**; two are **optional recommendations**.
The Console presents them together, but they carry very different urgency and
risk. Treat them separately.

| # | Item | Status | Risk to this app |
|---|------|--------|------------------|
| 1 | 16 KB memory page size | **REQUIRED** (deadline passed May 31 2026) | Low config risk; depends on dependency versions |
| 4 | Target Android 16 (API 36) | **REQUIRED** for new uploads/updates | Low–moderate (behaviour changes on 16) |
| 2 | Enable R8 optimization | Recommended | **HIGH** — can break the ML stack at runtime |
| 3 | Upgrade to AGP 9.0 | Recommended | **HIGH** — breaking DSL/Kotlin changes |

## Done in this version

- `compileSdk = 36`, `targetSdk = 36` (item 4).
- AGP `8.7.3 -> 8.12.0` and CI Gradle `8.10.2 -> 8.14` — needed because
  `compileSdk 36` is not supported by AGP 8.7.x. Stays on the **8.x line** on
  purpose (see "Why not AGP 9 yet").
- `packaging { jniLibs { useLegacyPackaging = false } }` — stores native `.so`
  files uncompressed so they can be page-aligned and mmap'd on 16 KB devices.
  This is the part of item 1 that is under our control.

## Item 1 — 16 KB page size: the remaining work is DEPENDENCIES

Our app ships **no native code of its own**; every `.so` comes from a library.
From the CI `stripReleaseDebugSymbols` output, the native libraries are:

```
libtensorflowlite_jni.so, libtensorflowlite_gpu_jni.so, libtask_vision_jni.so
libmediapipe_tasks_vision_jni.so
libmlkit_google_ocr_pipeline.so
libimage_processing_util_jni.so   (CameraX)
libandroidx.graphics.path.so, libsurface_util_jni.so
```

Each of these must be built 16 KB-aligned **by its publisher**. No build-config
setting can align a third-party `.so`. So the fix is: bump each native-shipping
dependency until the alignment warning clears. Current suspects (oldest first):

- `org.tensorflow:tensorflow-lite*:2.16.1` — early 2024, the most likely
  offender. Note TF Lite has been superseded by **LiteRT**
  (`com.google.ai.edge.litert`); moving is a larger migration but is the
  long-term path.
- `org.tensorflow:tensorflow-lite-task-vision:0.4.4` — old.
- `com.google.mediapipe:tasks-vision:0.10.20`
- `com.google.mlkit:text-recognition:16.0.1`
- CameraX / `androidx.graphics` — bump with the rest.

### How to verify (do this per bump, it's fast)

1. Build the release bundle/APK.
2. Android Studio → **Build > Analyze APK** → open `lib/arm64-v8a/` and look for
   the warning `4 KB LOAD section alignment, but 16 KB is required`.
3. Or check the Play Console bundle details → **Memory page size**.

Bump one library at a time and re-check — that identifies exactly which
dependency is non-compliant instead of changing everything at once.

## Item 2 — R8: prepared, deliberately NOT enabled

`isMinifyEnabled` stays `false`. This is a considered decision, not an oversight:

R8 strips code it believes is unreachable. This app loads TF Lite, MediaPipe and
ML Kit classes **reflectively at runtime**, so R8 can remove classes that are
genuinely needed. The failure mode is a **launch-time or first-inference crash in
the release build only** — it will not show up at build time, and it will not
show up in a debug build. That is the most expensive kind of regression.

`proguard-rules.pro` already carries the keep + dontwarn rules for exactly this,
so enabling R8 is a one-line change *when you are ready to test it properly*:

```kotlin
isMinifyEnabled = true      // in the release buildType
```

**Test procedure before shipping an R8 build** (do not skip):
1. Enable the flag, build a **release** APK (not debug).
2. Install and launch — confirm no crash on startup.
3. Run monitoring and confirm **each** ML path actually produces output:
   driver analysis, road/sign detection, traffic-light detection, ML Kit OCR.
   A missing keep rule usually manifests as one model silently failing.
4. Check logcat for `ClassNotFoundException` / `NoSuchMethodException`.
5. If something breaks, add a `-keep` rule for that package and repeat.

Benefit when it works: smaller download and some runtime gain. The models
(~25 MB of `.tflite`) dominate app size, so expect a modest reduction, not a
dramatic one — another reason not to rush it.

## Item 3 — Why not AGP 9 yet (but why it's now on the clock)

**New constraint discovered in CI:** AGP 8.x **cannot run under Gradle 9.6+**.
It uses `org.gradle.api.problems.internal.InternalProblems`, which Gradle removed
in 9.6.0. The GitHub runner's pre-installed Gradle is already 9.6.x, so the CI
step that ran `gradle wrapper` inside the project failed while *configuring* the
build — before compiling anything.

Worked around (not papered over) by generating the wrapper in an empty scratch
project, so the system Gradle never evaluates our build scripts; the build itself
runs under the pinned wrapper Gradle 8.14, which is compatible with AGP 8.12.
That is stable and insulates CI from further system-Gradle bumps.

But the direction is clear: the AGP 8.x line is at the end of its compatible
range, and AGP 10.0 removes the new-DSL opt-out. **The AGP 9 migration should be
scheduled, not indefinitely deferred.**

It is still deliberately NOT bundled here, because it is a multi-version
migration that cannot be validated without a build:

- New DSL (old one deprecated); opt out with `android.newDsl=false` initially.
- Built-in Kotlin support is enabled by default and pulls **KGP 2.2.10**, while
  this project pins Kotlin **2.0.21**, the Compose compiler plugin 2.0.21, and
  KSP **2.0.21-1.0.27**. Those four move in lockstep — KSP and the Compose
  plugin must match the Kotlin version exactly.
- Requires Gradle 9.1+.

So the migration is a coordinated bump of AGP + Gradle + Kotlin + KSP + Compose
plugin. Do it on its own branch with the **AGP Upgrade Assistant**, one change
at a time, so a failure is attributable. Do not combine it with an SDK bump or
with enabling R8.
