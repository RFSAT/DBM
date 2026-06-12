# Changelog

Versioning convention: **major** version increments when new features are
added; **minor** version increments for corrections. The version appears in
every produced package filename (e.g. `DMS-v1.0-release.apk`) and in the
in-app About screen.

## v6.4 — portrait operation (correction -> minor increment)
- Application now rotates freely (fullSensor): in portrait the two video
  views are positioned one under the other with the detection messages at
  the bottom; landscape keeps the side-by-side arrangement
- Camera capture, analysis and the activity state survive rotation (the
  service owns the pipeline; previews re-attach via the existing surface-
  refresh mechanism)

## v6.3 — About view additions (corrections -> minor increment)
- "by RFSAT Limited" in the About header now links to https://www.rfsat.com
- About view lists all detectable issues as a bullet list (generated from
  the risk-type catalogue, so future detection types appear automatically)

## v6.2 — About view text (correction -> minor increment)
- Removed the versioning-policy note from the About view (policy remains
  documented in CHANGELOG.md and build.gradle.kts)

## v6.1 — duration display units (correction -> minor increment)
- All displayed durations (detection messages, event details, history
  records) are shown in seconds with one decimal place, so sub-second
  values appear as fractions (e.g. "eyes closed 0.8 s") instead of
  milliseconds

## v6.0 — selective detection elements (new feature -> major increment)
- New "Detection elements" section in Settings: individually switch on/off
  road signs (speed limits), lane-marking overlay, single/double line-
  crossing events, hard-shoulder detection, road objects (vehicles,
  pedestrians, cyclists...), and driver-state monitoring
- Switches are persisted, applied live to the running pipeline, and skip the
  corresponding computation entirely when off (lower CPU and battery use)
- Lane-crossing and hard-shoulder events are filterable independently of the
  lane overlay, so markings can stay visible without raising events

## v5.2 — preview recovery on tab switching (corrections -> minor increment)
- Fixed camera previews freezing or going blank after switching menus: the
  long-lived PreviewViews are now detached from their disposed parent before
  Compose re-attaches them on return to the Detector tab, and CameraX
  surface providers are re-issued when the tab becomes visible again (the
  TextureView surface is destroyed on detach and must be re-provided)

## v5.1 — UI corrections (corrections -> minor increment)
- Camera previews now display reliably at start: PreviewView switched to
  COMPATIBLE (TextureView) mode, and camera capture now starts only after
  the privacy notice is accepted (binding previously occurred while the
  preview surfaces were not yet attached, leaving SurfaceView previews blank;
  this is also the correct privacy ordering)
- Edge-to-edge insets handled: the UI is padded by the safe-drawing area so
  system navigation buttons and the status-bar icons no longer overlap the
  application (edge-to-edge is enforced by default when targeting SDK 35)
- Screen is kept on while the application is active (FLAG_KEEP_SCREEN_ON)
- Camera view row height increased by 10 %

## v5.0 — restructured UI in RFSAT/ENACT style (new feature -> major increment)
- New navigation: scrollable tab menu at the top of the screen — Detector,
  History, Log, Settings, About
- Detector view: front (driver) and rear (road) camera views side by side
  under the menu, with the live detection messages panel underneath and a
  compliance/speed status strip above
- Graphical style matched to ShimmerENACT: RFSAT/ENACT dark-green palette
  (EnactDark background, EnactGreen primary, lime accents), rounded gradient
  cards, EnactDarkMid top bar, Material 3 dark colour scheme, matching
  status/navigation bar colours
- Log is now a full tab with Refresh and Share; About restyled with the
  ShimmerENACT-style branded header card
- New Settings tab: toggles for audio alert tones and spoken (TTS) warnings,
  persisted and applied live to the monitoring service

## v4.0 — diagnostic logging facility (new feature -> major increment)
- Persistent file logging (filesDir/logs/dbm-YYYYMMDD.log): timestamped
  entries mirrored to logcat, daily rotation, 7-day retention
- Uncaught-exception handler writes the full crash stack trace to the log
  file before the process dies — installed in a new Application class so it
  is active from the first moment of app start
- Operational logging across the app: activity lifecycle, per-permission
  grant results, service binding, foreground start, analyzer initialisation,
  camera binding decisions (concurrent vs multiplexed) and bind failures,
  per-frame analysis failures
- Crash-proofing of the suspected startup failure: foreground notification
  is now started BEFORE analyzer initialisation (5-second contract), and
  DriverAnalyzer/RoadAnalyzer construction is guarded — a missing model
  asset now logs an explicit error and degrades (lane/sign analysis and the
  other camera continue) instead of crashing
- New "Logs" button: in-app viewer of today's log with a Share action
  (FileProvider) to export the file for support

## v3.6 — lint clean-up (corrections -> minor increment)
- StreamPlayer: opted in to Media3's unstable RTSP API at class level
  (@OptIn(UnstableApi::class)) — fixes 6 UnsafeOptInUsageError findings
- Foreground service type reduced to camera|location; connectedDevice type
  and permission removed (not used in Phase 1, and it demands device-class
  permissions DBM does not request) — fixes ForegroundServicePermission
- POST_NOTIFICATIONS requested only on API 33+; foreground-service type
  mask now built per API level (camera type requires API 30)
- mipmap-anydpi-v26 merged into mipmap-anydpi (minSdk is 26); removed unused
  app_name_short string
- Orientation lock annotated as intentional (in-car landscape use)
- Lint re-enabled as a hard build gate (abortOnError = true); dependency-
  version and targetSdk-36 advisories tracked as scheduled maintenance

## v3.5 — lint compliance (corrections -> minor increment)
- Added ACCESS_COARSE_LOCATION alongside FINE in the manifest and the runtime
  permission request (Android 12+ requirement; app degrades gracefully if the
  user grants only approximate location — GNSS speed still works)
- Replaced deprecated Compose Divider with HorizontalDivider
- Lint configured to report without aborting pre-release builds; CI now
  uploads the full HTML lint report as a build artifact so all findings are
  reviewable (to be tightened before Play submission)

## v3.4 — build fixes (corrections -> minor increment)
- Fixed compile errors: AnalysisResult constructed with positional arguments
  placed risk events into the laneLines parameter in DriverAnalyzer and
  RoadAnalyzer; now passed as named arguments
- Added kotlinx-coroutines-play-services dependency required by
  SignAnalyzer's Task.await() (root cause of the unresolved-reference
  cascade in the CI log)
- Migrated deprecated kotlinOptions to the kotlin compilerOptions DSL
- Room: exportSchema = false to silence the schema-location warning

## v3.3 — Play Store package ID (correction -> minor increment)
- applicationId set to "com.DBM" for Google Play registration (Kotlin
  namespace remains com.rfsat.dms; the two are independent in Gradle)

## v3.2 — Play Store icon asset (correction -> minor increment)
- Added store-assets/DBM-play-store-icon-512.png: 512 x 512 px 32-bit PNG
  app icon required by Google Play Console registration, rendered from the
  same artwork as the launcher icon (4x supersampled, Lanczos downscale)
- store-assets/README.md noting the remaining listing assets (feature
  graphic, screenshots)

## v3.1 — rebranding (correction -> minor increment)
- Application renamed to "Driver Behavior Monitor" (short: DBM); package
  filenames now use the DBM prefix (e.g. DBM-v3.1-release.apk)
- New launcher icon: driver (head and shoulders) behind a steering wheel,
  as an adaptive vector icon with monochrome (themed-icon) support

## v3.0 — violation recording & history browser (new feature -> major increment)
- All violation types are recorded on the smartphone with evidential
  snapshots; speed violations now also attach the most recent road frame
- NEW History screen: browse all past records, newest first, grouped by day,
  filterable by severity (All / Critical / Warning / Info)
- Detail view per record: evidence image, full timestamp (ms), camera,
  severity, confidence, detail text and SHA-256 integrity hash
- Graceful handling of evidence files removed by 30-day retention

## v2.0 — speed-source resilience (new feature -> major increment)
- GNSS health tracking (availability + fix staleness): GPS used for
  positioning/speed whenever available
- NEW: visual speed estimation from the road-facing camera as automatic
  fallback when GNSS is unavailable (tunnels, urban canyons): block-matching
  optical flow on the road surface, median-filtered, with continuous
  auto-calibration of the px/s -> km/h scale against GPS while GPS is healthy
- Speed source (GPS / estimated / none) shown in the header; speeding
  tolerance widened and event confidence reduced when relying on the
  visual estimate

## v1.0 — initial feature release (Phase 1: phone cameras)
- Dual phone-camera capture: front camera = driver/interior, rear camera =
  road ahead (CameraX ConcurrentCamera, with time-multiplex fallback on
  devices without concurrent support)
- Driver-state detection: microsleep / prolonged eye closure (EAR + PERCLOS),
  gaze off road, mirror-check intervals (MediaPipe Face Landmarker)
- Road object detection + tracking + collision-risk proxy (EfficientDet-Lite0)
- Lane-marking detection: dashed / solid / double-solid classification,
  line-crossing, lane-drift and hard-shoulder heuristics
- Road-sign recognition v1: speed-limit reading via on-device ML Kit OCR
- Speed compliance: GNSS speed vs posted limit
- Driver-compliance score 0–100 with weighted penalties and clean-time recovery
- Evidential log: Room DB + SHA-256-hashed JPEG snapshots, 30-day retention
- Audio alerts (tones + TTS), foreground service, privacy disclosure, About
  screen with version display
- Raspberry Pi 3-node RTSP infrastructure retained for Phase 2 (`pi/`)
