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

## Item 3 — Why not AGP 9 yet

AGP 9.0 is a major release with breaking changes: a new DSL (the old one is
deprecated), built-in Kotlin support enabled by default (adds a KGP runtime
dependency), Gradle 9.1+ required, and API-36.1 max support. Projects can opt out
of the new DSL with `android.newDsl=false`, but the opt-out disappears in
AGP 10.0.

None of the two **required** items need AGP 9 — AGP 8.12 satisfies both. So the
sensible order is:

1. Ship compliance on AGP 8.x (this version).
2. Migrate to AGP 9 as a **separate, isolated change**, using Android Studio's
   **AGP Upgrade Assistant**, on its own branch, so a build break is attributable
   to the migration and nothing else.

Combining the AGP 9 migration with the SDK bump and R8 in one change would make
any failure very hard to attribute. Keep them separate.
