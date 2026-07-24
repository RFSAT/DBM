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

## Item 1 — 16 KB page size: root cause found

Our app ships **no native code of its own**; every `.so` comes from a library, and
each must be built 16 KB-aligned **by its publisher**. `useLegacyPackaging = false`
(added in v1.20.13) was necessary but **not sufficient** — Play still rejected
v1.20.14.

### Confirmed offenders, fixed in v1.20.15

| Library | Problem | Fix |
|---|---|---|
| `org.tensorflow:tensorflow-lite*:2.16.1` | Prebuilt `.so` aligned to 4 KB. Confirmed unfixed in 2.17 too. Cannot be re-aligned with linker flags. | Migrated to **LiteRT** `com.google.ai.edge.litert:{litert,litert-gpu,litert-gpu-api}:1.4.0` — Google's official TF Lite successor, built 16 KB-aligned. Java packages (`org.tensorflow.lite.Interpreter`, `NnApiDelegate`, `GpuDelegateFactory`) are unchanged, so this was a **coordinate swap with no source change**. |
| `org.tensorflow:tensorflow-lite-task-vision:0.4.4` | Not 16 KB aligned; 0.4.4 is the **last release** of an abandoned line, so no fixed version will ever exist. | **Removed entirely.** It only backed the EfficientDet-Lite0 fallback in `RoadAnalyzer`, which was *unreachable*: the fallback runs only when `yolo26n.tflite` is absent, and that asset is committed (see `.gitignore`). Dead code removed, plus its CI model download. |

### Remaining: verify the other native libraries

These also ship `.so` files and may still be unaligned. Check them **before**
the next upload rather than guessing:

```
libmediapipe_tasks_vision_jni.so   com.google.mediapipe:tasks-vision:0.10.20
libmlkit_google_ocr_pipeline.so    com.google.mlkit:text-recognition:16.0.1
libimage_processing_util_jni.so    androidx.camera:* 1.4.1
libsurface_util_jni.so             androidx.camera:*
libandroidx.graphics.path.so       androidx.graphics:graphics-path
```

**Procedure (do this before uploading):**

1. Build the release AAB/APK in CI and download the artifact.
2. Android Studio → **Build > Analyze APK** → expand `lib/arm64-v8a/`.
   Any entry warning `4 KB LOAD section alignment, but 16 KB is required` is a
   remaining offender. (Equivalently, from a shell:
   `unzip -o app.apk 'lib/arm64-v8a/*' -d /tmp && readelf -lW /tmp/lib/arm64-v8a/*.so | grep -A1 LOAD` — aligned libraries show `0x4000`, unaligned show `0x1000`.)
3. For each offender, bump that dependency to the newest version (Android Studio
   → **Help > Check for Updates** / the Gradle version catalog warning, or the
   library's release notes) and re-check. Bump **one at a time** so you learn
   which version actually fixed it.
4. Re-check in the Play Console: bundle details → **Memory page size**.

The libraries above are all actively maintained by Google/AndroidX, so unlike the
TF Lite Task library a fixed version should exist — it is a version bump, not a
migration.

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

---

## The two Console WARNINGS (neither blocks release)

### "No deobfuscation file associated with this App Bundle"

**Expected, and not applicable.** A deobfuscation (mapping) file only exists when
R8/ProGuard obfuscates the build. `isMinifyEnabled = false`, so nothing is
renamed and stack traces are already readable — there is no mapping file to
upload. Play shows this notice generically on any bundle without one.

It becomes relevant only if/when R8 is enabled (item 2 above); AGP then produces
`mapping.txt` and uploads it with the bundle automatically.

### "Contains native code, and you've not uploaded debug symbols"

`ndk { debugSymbolLevel = "FULL" }` **is** set on the release build, so this is
worth understanding rather than chasing.

Two things prevent it from producing anything useful here:

1. **The CI runner has no NDK installed.** That is why the build log prints
   `Unable to strip the following libraries, packaging them as they are: ...` —
   AGP cannot run the NDK `strip`/`objcopy` tools, so it can neither strip the
   libraries nor extract symbols from them into
   `BUNDLE-METADATA/com.android.tools.build.debugsymbols/`.
2. **More fundamentally, the symbols do not exist to extract.** Every `.so` in
   this app comes from a third-party AAR (LiteRT, MediaPipe, ML Kit, CameraX) and
   those are shipped **already stripped** by their publishers. We compile no
   native code of our own, so there is nothing of ours to symbolicate.

So even installing the NDK in CI (a large download, meaningful build-time cost)
would likely yield empty or near-empty symbol metadata. And a native crash inside,
say, MediaPipe could not be symbolicated by us regardless — we do not have their
private symbol files.

**Recommendation: accept this warning.** It is advisory, it does not block
release, and the app cannot meaningfully satisfy it. All first-party code is
Kotlin, and those crashes already report with full line numbers. Revisit only if
the app ever adds its own `externalNativeBuild` C/C++ code, at which point the
setting is already in place and only the CI NDK install would be needed.
