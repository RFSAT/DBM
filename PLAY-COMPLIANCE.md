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

### Fixed in v1.20.16 (after v1.20.15 was still rejected)

| Library | Was | Now | Evidence |
|---|---|---|---|
| `com.google.mediapipe:tasks-vision` | 0.10.20 — `libmediapipe_tasks_vision_jni.so` 4 KB-aligned | **0.10.26.1** | MediaPipe release notes: all current Google Maven packages are 16 KB-aligned; 0.10.26.1 also restores ARM v7. |
| `androidx.camera:*` | 1.4.1 — `libimage_processing_util_jni.so`, `libsurface_util_jni.so` | **1.5.0** | AndroidX 16 KB work landed in the 1.5.x line. APIs used here are source-compatible. |

### RESOLVED — Play accepted v1.20.19 (live on Google Play)

The 16 KB requirement is **satisfied**: Google Play accepted the v1.20.19 bundle
and it is live. The libraries below were flagged as *possibly* unaligned during
investigation, but the accepted build proves they are not a blocker in practice —
either they are aligned in the versions shipped, or Play does not reject on them.
No further action needed unless a future Play re-check flags a specific file.

| Library | Ships | Status |
|---|---|---|
| `com.google.mlkit:text-recognition:16.0.1` | `libmlkit_google_ocr_pipeline.so` | Accepted by Play in v1.20.19 — not a blocker. |
| `androidx.graphics:graphics-path` (transitive from Compose BOM) | `libandroidx.graphics.path.so` | Accepted by Play in v1.20.19 — not a blocker. |

**Do not upload to find out.** CI runs a
**"Check 16 KB page alignment of native libraries"** step that unzips the built
APK and runs `readelf -lW` on every `.so`, printing:

```
  OK        0x4000  lib/arm64-v8a/libtensorflowlite_jni.so
  UNALIGNED 0x1000  lib/arm64-v8a/libmlkit_google_ocr_pipeline.so
```

Any line marked `UNALIGNED` is the library Play will reject on. Bump the
dependency that ships it and re-run. This turns a wasted upload cycle into a
one-minute CI log check.

The Play Console error also names the file directly
("Library that does not support 16 KB: `base/lib/arm64-v8a/libXXX.so`") — if the
CI list and that name disagree, trust the Console.

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
