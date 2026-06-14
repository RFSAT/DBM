# Changelog

Versioning convention: **major** version increments when new features are
added; **minor** version increments for corrections. The version appears in
every produced package filename (e.g. `DMS-v1.0-release.apk`) and in the
in-app About screen.

## v14.1 — sign recognition made functional + CI hardening (correction -> minor increment)
- FIX road-sign recognition coverage: the GTSRB classifier was only fed YOLO
  "stop sign" boxes, so most sign types (speed-limit, warning, prohibition)
  were never classified. SignAnalyzer now includes a colour/shape region
  proposer (red/blue/yellow sign borders in the upper frame) that supplies
  candidates independent of YOLO, so all 43 GTSRB classes can be recognised;
  classifier confidence rejects non-sign crops
- CI: no longer overwrites the committed, validated yolo26n.tflite with the
  int8 export (whose raw format the decoder cannot parse); only the
  non-committed face_landmarker.task and EfficientDet fallback are fetched.
  .gitignore de-duplicated and updated to keep the three committed detection
  models in the repo

## v14.0 — learned traffic-light detector (new feature -> major increment)
- NEW TrafficLightDetector: bundled YOLOv8-nano model (traffic_light.tflite,
  classes red/green/off/yellow, validated) detects AND colour-classifies
  traffic signals, replacing the colour-blob heuristic when present
- Brake-light rejection: red detections enclosed by a vehicle box and low in
  the frame are discarded, so car tail-lights no longer trigger false red-light
  events — the main weakness of the previous heuristic
- TrafficLightAnalyzer uses the learned detector when the asset is present and
  falls back to the colour heuristic otherwise; the red/amber crossing state
  machine (serious/warning) is unchanged. Vehicle boxes from the object
  detector are passed in for the brake-light check
- nano model chosen over small (6 MB vs 22 MB float16) — both validated with
  identical detections; nano is the right fit alongside YOLO26 + GTSRB

## v13.0 — traffic-sign recognition + road pipeline reconciliation (new feature -> major increment)
- NEW two-stage traffic-sign recognition: a bundled GTSRB 43-class classifier
  (gtsrb_sign.tflite, MobileNet, validated) identifies regulatory, warning and
  information signs from candidate regions; speed-limit classes set the active
  limit, complementing the OCR path. Recognised signs shown as a colour-coded
  pictogram strip above the cameras (red regulatory, amber warning, blue info)
- IMPORTANT FIX: the road-analysis pipeline (analyzeRoad) was reconciled — the
  per-element detection toggles (objects, lane markings, line-crossing,
  hard-shoulder, following distance, signs) and the traffic-light detector
  were declared but not actually applied in the analysis body due to earlier
  patches that silently failed to match. analyzeRoad now correctly honours all
  toggles and runs the traffic-light and two-stage sign stages

## v12.2 — real YOLO26 decoder; model bundled (correction -> minor increment)
- Inspected the supplied yolo26n_int8.tflite: float32 input [1,640,640,3],
  single raw output [1,84,8400] (NMS-free), COCO labels. The TFLite Task API
  cannot parse this, so v12.0/12.1's Task-based loading would not have worked
- NEW YoloDetector: raw TFLite Interpreter with letterbox preprocessing,
  YOLO box decode (normalized cx,cy,w,h), score thresholding, class selection
  and Non-Maximum Suppression; NNAPI delegate with CPU fall-back
- RoadAnalyzer uses YoloDetector when yolo26n.tflite is present, else the
  EfficientDet-Lite0 Task detector; ByteTrack and all downstream logic
  unchanged
- The YOLO26-nano model is now bundled in assets; added the core
  tensorflow-lite interpreter dependency

## v12.1 — YOLO26 detector enabled via CI (correction -> minor increment)
- CI now fetches the official Ultralytics YOLO26-nano int8 TFLite model
  (yolo26n_int8.tflite -> yolo26n.tflite); RoadAnalyzer already prefers it
  automatically, falling back to EfficientDet-Lite0 if the asset is absent
- models/README documents the direct download URL and the export command

## v12.0 — algorithm-review recommendations (new features + performance -> major increment)
- Hardware-accelerated inference: object detector uses NNAPI and the face
  landmarker uses the GPU delegate, each with automatic CPU fall-back — the
  single largest performance gain, no accuracy change
- ByteTrack-style tracker replaces the greedy IoU tracker: constant-velocity
  prediction plus two-stage (high- then low-score) association for stable
  identities, fewer identity switches and fewer one-frame spurious tracks;
  collision and vulnerable-road-user events now require a confirmed track
  (>= 3 frames) at the source
- Yawning detection: mouth-aspect-ratio cue raises a fatigue warning and, via
  the cross-checker, independently corroborates microsleep findings
- Class-specific vehicle widths sharpen monocular following-distance (trucks/
  buses 2.5 m, motorcycles 0.8 m, cars 1.8 m)
- Adaptive analysis rate: road analysis drops to ~2 fps when the vehicle is
  stationary and returns to ~6 fps when moving, reducing CPU and battery use
- Activatable model upgrades: a fine-tuned YOLO26-nano object model is used
  automatically when yolo26n.tflite is present; integration points and a
  models/README document the eye-state CNN, two-stage sign recogniser,
  learned traffic-light detector and row-anchor (UFLD) lane model, each
  falling back to the existing method until its asset is supplied

## v11.1 — labelled, colour-coded road-user marking (correction/enhancement -> minor increment)
- The front (road) video now marks each detected road user with a distinct
  colour and a text label: pedestrians (amber), cyclists/motorbikes (orange,
  as vulnerable users), cars (blue), trucks/buses (purple), signs (cyan),
  traffic signals (red); risky objects override to red. Labels are drawn as
  chips above each box, in both the live overlay and the recorded video
- Detection class grouping centralised in a DetClass type

## v11.0 — traffic-light violations and violations summary (new features -> major increment)
- NEW traffic-light crossing detection: passing under a RED signal while
  moving is a serious (critical) violation; passing on AMBER is a warning.
  Signal state from a saturated-colour blob detector in the upper road ROI;
  a crossing requires the light to have been red/amber AND the vehicle still
  moving as it passes beneath (a normal stop at red is not flagged). New
  detection-element toggle; assistance-grade confidence, rate-limited
- NEW Summary tab: violations over time with totals per category, overall
  violation count, the period covered, and the live compliance score;
  categories ordered by frequency and tagged Serious/Warning
- NEW Reset all counters: clears all recorded violations and resets the
  compliance score on demand (with confirmation); saved video recordings
  are not affected

## v10.0 — cross-detector consensus / co-training (new feature -> major increment)
- New CrossChecker fuses INDEPENDENT detector signals to corroborate or
  suppress each event before it fires, cutting false positives:
  * SPEEDING: GPS speed and visual-flow speed must agree for a full-confidence
    alert; disagreement demotes it to a cautious warning
  * UNSAFE_FOLLOWING_DISTANCE: a close gap is only corroborated when the lead
    vehicle is also closing (box-area growth); a close but steady gap (matched
    cruising speed) is demoted/dropped — removes the commonest false positive
  * lane crossings: corroborated against gyroscope yaw-rate — a crossing with
    no real heading change is treated as paint/shadow noise and dropped
    (double-solid kept regardless, as the higher-severity case)
  * collisions / vulnerable road users: gated by tracker persistence to reject
    single-frame spurious detections
- New YawRateMonitor: phone gyroscope with auto-detected yaw axis provides the
  independent heading-change signal for lane corroboration
- These independent pairings also lay the groundwork for consistency-based
  labels (one confident detector teaching another) in a future release

## v9.0 — in-practice self-calibration + typed lane overlay (new feature -> major increment)
- Parameter-level self-learning (safe adaptation against trusted references,
  no model retraining, all bounded and reversible):
  * per-driver eye-closure threshold learned from the personal open-eye EAR
    baseline (closure = 55 % of personal median, clamped 0.12–0.22)
  * straight-ahead head-pose neutral learned per mount, so "not looking
    ahead" is judged relative to the actual mounting angle
  * following-distance focal factor refined at runtime from successive
    lead-vehicle widths against own speed (bounded 0.4–1.6)
  * (existing) visual speed scale auto-calibrated against GPS
- Settings: new Self-calibration section explaining the adaptation, with a
  "Reset calibration" button
- Lane overlay now reflects the detected line TYPE and is drawn thicker:
  dashed lines rendered dashed (lime), single solid as one thick line
  (amber), double solid as two parallel thick lines (red)

## v8.1 — log-save API compatibility (correction -> minor increment)
- Fixed lint NewApi error: MediaStore.Downloads requires API 29; log saving
  now branches — public Downloads via MediaStore on API 29+, and the app's
  external files directory (USB/file-manager accessible) on API 26–28

## v8.0 — alignment, tables, log export and UI refinements (features + corrections -> major increment)
- Previews restored deterministically on menu switching: re-attachment now
  triggers a full debounced use-case rebind (the same recovery path that
  minimise/restore exercised)
- Lane/detection overlay geometry fixed twice over: analysis frames are now
  rotated per sensor orientation before analysis (root cause of diagonal/
  shifted lines), and overlays are mapped through the PreviewView
  FILL_CENTER scale-and-crop so they align with the visible video
- RiskType gains an "implemented" flag: About and the Settings weight list
  now show only detections active in this release (phone use, hands-off-
  wheel, seatbelt, rear collision and illegal turn marked as planned)
- Default warning volume halved (tone generator 90 -> 45)
- About: only the URL is clickable; description and bottom note fully
  justified; bottom note reworded; copyright line added; build number
  removed (major.minor shown)
- Settings: option rows compacted to fit more lines; retention note reworded
- Log tab: new Save action writes the log to public Downloads for sharing
  (e.g. with an AI assistant when debugging)
- History: violations presented as a table (Time | Issue | Detail | Severity
  | Evidence), one violation per row, day-grouped, tappable for full detail
- Detector messages: sentence case, single line each with ellipsis, so more
  fit on screen

## v7.0 — following distance, score weights, overlay recording (new features -> major increment)
- NEW unsafe-following-distance detection: monocular distance to the lead
  vehicle (bounding-box width model) compared against the stopping distance
  at current speed (1 s reaction + v²/2a braking); warning when closer,
  critical below 60 % of the required gap; persistence and rate limiting to
  suppress jitter
- Required gap adjustable in Settings as 50–200 % of the computed stopping
  distance (e.g. raise for wet roads); new detection-element toggle
- NEW per-issue compliance-score weights: every detection type's point
  deduction is adjustable (0–25) via sliders in Settings, persisted and
  applied live
- NEW overlay video recording: both camera streams can be recorded to MP4
  with detections, lane lines, event banners and timestamps burnt in
  (H.264/MediaCodec at analysis frame rate, wall-clock timing, 7-day
  retention, on-device only); toggle in Settings

## v6.6 — reliable preview restoration (correction -> minor increment)
- Fixed videos frequently staying blank after switching menus: surface
  providers are now re-issued from an OnAttachStateChangeListener at the
  moment each PreviewView is re-attached to the window (posted after the
  layout pass), instead of at tab composition time — the previous approach
  raced the view attachment and only sometimes won
- Manual refreshSurfaces() retained for explicit recovery paths

## v6.5 — Settings scrolling (correction -> minor increment)
- Settings view content is now vertically scrollable (the detection-element
  switches made it taller than smaller/landscape screens)

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
