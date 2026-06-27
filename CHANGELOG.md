# DBM Changelog

## v19.9 — brake-light corroboration for lead hard-braking
- The lead-vehicle hard-braking detector now uses the lead's BRAKE LIGHTS as a
  direct cue, not just bounding-box growth (closing speed). brakeLightStrength()
  samples the lower band of the lead's box and requires bright, saturated red on
  BOTH the left and right halves (the two lamps), rejecting a single red blob,
  body paint, and dim running tail-lights.
- Decision now combines the two signals:
  * brake lights lit + growth -> CRITICAL, high confidence (0.85),
    "lead vehicle braking hard (brake lights on)"; a smaller growth suffices, so
    the alert is earlier.
  * growth with no frame available -> unchanged CRITICAL 0.7 (geometry only).
  * strong growth but NO visible brake lights -> demoted to WARNING 0.55
    ("closing on lead vehicle fast") — likely own-speed closing, not the lead
    braking, which cuts false hard-braking alerts.
- frame is now passed into checkLeadHazards(); Python-validated the lamp detector
  (two-lamp ON=1.0, single blob/paint/dim=0.0).

## v19.8 — traffic-light temporal smoothing
- TrafficLightAnalyzer now smooths the per-frame colour before the crossing logic
  acts on it, removing single-frame flicker that caused false RED_LIGHT_CROSSING
  events. A new colour must persist for CONFIRM_FRAMES (3) consecutive frames to be
  confirmed; a confirmed colour is HELD for HOLD_MS (600 ms) across brief dropouts,
  so a one/two-frame miss is not treated as the signal vanishing. Applied to BOTH
  the learned-model path and the colour-blob fallback (shared smooth()).
- Overlay box still tracks the raw per-frame detection; only the crossing decision
  uses the smoothed state. Python-validated across glitch/transition/dropout cases.

## v19.7 — targeted, guarded fix for blank/black preview after bind
Addresses the long-standing camera symptoms WITHOUT the v19.4 regression:
  (1) rear view blank on cold start, (2) one/both black on return from another app.
- Added nudgeNonStreamingSurfaces(): after each bind it waits ~1.2 s, then checks
  each PreviewView's previewStreamState and re-issues the surface provider ONLY for
  a view that is NOT already STREAMING. A view that is streaming is never touched —
  this is the exact guard the v19.4 blanket refresh lacked, so it cannot detach a
  working stream. previewStreamState is the real "frames flowing" signal, more
  reliable than layout size or fixed timers.
- rebind() now schedules this guarded nudge; resume() routes through rebind() so the
  return-from-background (black-until-tab-switch) case gets the same safe treatment.
- The old blanket refreshSurfaces() is no longer auto-called from any path (kept as a
  manual function only). The unconditional re-issue that caused the v19.4/19.5
  degradation can no longer fire on its own.

NOTE: best-effort and unverifiable in this environment; needs a device check. If a
view still fails to stream, the new "nudge[ROLE]: not streaming -> surface re-issued"
log line will show whether the nudge fired and for which camera.

## v19.6 — revert the v19.4 surface re-issue that broke camera streams
- REGRESSION FIX: v19.4 added a refreshSurfaces() call ~600 ms after EVERY
  laid-out rebind, intending to auto-start the rear view on cold start. But that
  runnable fires on every tab switch too, so each switch re-issued BOTH surface
  providers shortly after the bind — racing the freshly-bound surfaces and
  detaching one or both streams. This made driver and/or road previews fail to
  show, on cold start and on tab switches. Removed that call entirely; the camera
  binding path is now back to the v19.3 behaviour that drive logs confirmed
  working (both streams binding at full rate via the layout-wait rebind).
- The rear-view-on-first-switch issue this was meant to fix is therefore NOT
  addressed here — reverting to a known-good streaming path takes priority. It
  needs a safer approach than a blanket surface re-issue.
- Thermal auto-recovery (v19.4) and the on-screen thermal pause/resume notice
  (v19.5) are retained — neither touches camera binding.

## v19.5 — on-screen thermal pause/resume notice for the driver
- When thermal management pauses the road pipeline (sign & vehicle detection) or
  resumes it after the phone cools, a brief centred banner now appears so a paused
  pipeline reads as the app managing heat, not a fault. Light-red with a ⚠ when
  paused ("Phone hot — road sign & vehicle detection paused to cool down"),
  light-green with a ✓ when resumed ("Cooled down — ... resumed"). Large bold
  text, auto-dismisses after ~3 s, centred for an at-a-glance read while driving.
- Implemented via a ThermalNotice flow on PhoneCameraManager (emitted once per real
  suspend/resume transition through a single setRoadSuspended() path) collected by a
  ThermalNoticeToast overlay in the Detector screen.

## v19.4 — thermal recovery, rear-view first-bind, keep-direction gate (from drive log)
Diagnosed from the 10:33 drive log:
- DETECTION-DEATH ROOT CAUSE was THERMAL, not the v19.3 deadlock. The phone hit
  CRITICAL thermal and the road pipeline was suspended (factor 5.0); it never
  resumed because Android's thermal listener only fires on CHANGES and did not
  deliver a cool-down event — and an in-app restart began while still CRITICAL,
  so it started suspended. Map limits kept working throughout (GPS path). Fix:
  added reevaluateThermal(), called ~every 3 s from the frame loop, which reads
  the live thermal status and RESUMES the road pipeline once the phone cools to
  MODERATE or below (hysteresis: suspend at CRITICAL, resume at <=MODERATE). No
  longer depends on the OS delivering a change event.
- REAR-VIEW FIRST-SWITCH: the log shows that on the first cold-start bind the rear
  (FRONT-role) camera does not stream until a later rebind — the user had to switch
  tabs. Fix: re-issue both surface providers ~600 ms after the cold-start bind (the
  same refresh the tab-switch/resume paths use), so the road view comes up on first
  start without a manual tab switch.
- KEEP_LEFT/KEEP_RIGHT: confirmed a model confusion (wrong calls cluster at 45-60%
  confidence, correct ones at 80%+). Added an app-side gate requiring >=0.65
  confidence for those two classes specifically, suppressing the low-confidence
  mirror-image misreads. A proper fix still needs model retraining.

The v19.3 ImageProxy try/finally close remains in place (correct defensive fix),
but was not the cause of this drive's detection stoppage.

## v19.3 — critical pipeline-deadlock fix, recents restore, Greek yellow warnings
- CRITICAL: object/sign detection could stop permanently mid-drive and not recover
  even after an in-app restart (map-based limits kept working). Cause: the camera
  ImageProxy was only closed on the normal path; if any frame work threw (an OOM
  in bitmap rotation, or anything inside the detector), the proxy was left open,
  and with STRATEGY_KEEP_ONLY_LATEST + a small thread pool the camera stopped
  delivering frames for good. Wrapped the analyzer body in try/finally so the
  proxy is ALWAYS closed, and catch so one bad frame never kills the stream.
- App could not be restored from the Android recents/background list. Cause:
  launchMode=singleTask. Changed to singleTop.
- Greek warning signs: the triangle (warning) drawables now have a YELLOW interior
  per Greek convention (children, curve_left, curve_right, pedestrians, roadworks,
  slippery_road). Round restriction signs stay white inside; yield unchanged.
- Sign overlay: de-dupe held/shown signs by name so the same sign cannot appear
  multiple times (the dual-pass detector could report one sign twice). NOTE: the
  "stuck >3 s" sign was most likely the pipeline deadlock above stalling the road
  frames so expiry never ran; the close fix should resolve it.

Known/with the model, not fixed in app: keep_left vs keep_right are occasionally
swapped by the sign model itself (our class->name->drawable mapping is correct and
consistent). This needs model retraining / a higher confidence gate, not an app
change.

## v19.2 — speed-limit display: always-on, camera-overrides-map, hold-until-different
The shown speed limit now follows a clear rule:
- Always show a current limit (camera-recognised, else the map value for the
  segment); the display never blanks on a momentary unknown.
- A camera-recognised limit overrides the map (already the fuser's priority).
- The shown limit is LATCHED: it changes only when a DIFFERENT valid limit
  appears. Re-seeing the same value, or a map value equal to what is shown, does
  not change it.
- A camera limit is held until a NEW sign appears or the vehicle moves onto a
  DIFFERENT road segment — it no longer times out after 90 s and reverts to the
  map. (Fuser: removed the time-based sign expiry; sign confidence still eases
  off for the confidence value but the limit is retained until sign/segment
  change. Service: added a shownLimitKmh latch.)

## v19.1 — sign hold timeout, map manager grouping/grey-out/delete
- Non-speed-limit road signs now disappear from the overlay 3 s after the camera
  last sees them, instead of lingering. Speed-limit signs are unaffected (they
  follow the speed-limit fusion/cache lifecycle and their own display).
- Map manager now groups regions by country under collapsible headers (needed for
  the full ~526-region Geofabrik catalogue; countries start collapsed, tap to
  expand). A country with any downloaded region is marked "downloaded".
- Installed maps are greyed out to distinguish them from available ones.
- Installed maps can be deleted to free phone space, now behind a confirmation
  dialog (delete removes the .db file and the installed-registry entry).

## v19.0 — final EU sign artwork, distant-sign detection, full Geofabrik map catalogue
- Item 10 COMPLETE: replaced the last three non-EU-standard sign drawables with the
  supplied official artwork — no_overtaking (red/black car pair), pedestrians
  (pedestrian on crossing; replaces the old yellow icon), slippery_road (skidding
  car with tracks). All 21 supported signs now use proper EU artwork.
- Item 11: distant signs are often too small at the concurrent-mode 960x720 road
  resolution and were missed. Added a dual-pass sign detector: the full frame plus
  a 2x-upscaled upper-centre crop (the vanishing-point region where approaching
  signs appear), with crop hits remapped to full-frame coords and merged (IoU
  de-dup). Doubles effective resolution for far signs without changing the camera.
  (Per decision, both cameras stay always-on; road stays ~960x720.)
- Client map download already wired (Settings → map manager, rfsat.com index.json);
  confirmed functional. Ships alongside a ready-made index.json listing ALL ~526
  Geofabrik regions for hosting on rfsat.com.

# Changelog

## v18.14 — coalesce the laid-out rebind (one rebind, not one per view)
The 2026-06-22 17:07 log confirmed the v18.13 layout-wait works: both views bind
at 1038x674/675 (laid out) and both cameras stream ~30 fps. One refinement: the
per-view layout callbacks (driver + road) each scheduled their own rebind via a
freshly-created Runnable, so removeCallbacks could not dedupe them and TWO rebinds
fired ~0.5 s apart — briefly restarting one stream before the other (the rare
"only one active for a moment" flash). Replaced with a single shared
laidOutRebindRunnable so both views' triggers coalesce into ONE rebind. No change
to the proven layout-wait logic.

Note (not a bug): in concurrent camera mode CameraX caps each stream, so the road
analysis frames are ~960x720 rather than the requested 1280x720 — an inherent
limit of running both cameras at once; forcing higher risks failing the
concurrent bind entirely.

## v18.13 — fix rear stream on cold start (rebind after layout, not just attach)
Root cause of "driver streams on startup but rear does not, yet both work after a
tab switch": on a cold start the Detector preview views are composed for the first
time and are ATTACHED but not yet LAID OUT (width/height still 0). The rebind
fired on a fixed delay after attach, so the larger rear-camera surface bound with
no dimensions and never started; after a tab switch the views were already
measured, so it worked. Fix: rebind only once the attached preview views actually
have a layout (size > 0) — via an OnLayoutChangeListener on cold start, an
immediate debounced path when the views are already measured (tab return), and a
700 ms fallback so a rebind always eventually happens. Keeps the v18.12 diagnostic
logging, which will now show non-zero view sizes at bind time.

## v18.12 — camera diagnostic logging (no behaviour change)
Added detailed, targeted logging to find why the road stream fails while the
driver stream works. No camera logic changed. New log lines:
- singleConfig[ROLE]: logs, per camera at bind time, whether its PreviewView is
  attached, its size, and whether the surface provider is set. (If the road view
  is attached=false / 0x0 at bind, that is the root cause.)
- rebind requested: now logs both views' attached state and size.
- bound CONCURRENT ... (both use cases bound): confirms concurrent bind reached.
- frames[ROLE]: N received: logs per-camera frame ARRIVAL rate every ~3 s. This
  is the decisive signal — if frames[FRONT] (road) never appears, the road camera
  is not producing frames (a binding/acquisition issue); if it appears but the
  preview is blank, it is a surface/render issue.
Push this, reproduce the blank-road problem (incl. a tab switch back), and send
the log; the singleConfig and frames lines will pinpoint the cause.

## v18.11 — fix stream connection (rebind on every Detector view attach)
v18.9/10 left two stream bugs: only the driver video showed on startup, and after
switching tabs and back NO stream showed. Cause: the attach handler rebound only
ONCE (everAttached flag), and that single rebind raced (fired before the road
view had attached, so only driver connected); and because it never fired again,
returning to the Detector tab (which detaches/re-creates the views) had no rebind
to reconnect, so both streams stayed dead. Fix: rebind on EVERY (re)attach of a
Detector PreviewView, debounced (250 ms) so the two views' near-simultaneous
attaches coalesce into ONE rebind and rapid tab flips are absorbed — connecting
both cameras to the real surfaces on first show and on every return, without the
old rebind storm. Removed the eager startup surface refreshes; the attach-driven
rebind is now the single source of truth for connecting a stream to its view.

## v18.10 — fix compile error in v18.9 (nullable SignOutput access)
v18.9 made the sign analyser nullable but left the unrecognised-sign diagnostic
logging block outside the new null guard, so it accessed out.unrecognised on a
nullable SignOutput? and CI failed to compile (MonitorService.kt:684/687). Moved
that block inside the if (out != null) guard. No behavioural change from the
intent of v18.9; this only fixes the compile.

## v18.9 — fix About freeze (off-thread model load) and stream init (bind on attach)
Two root causes identified from the 2026-06-22 00:26 log:

- ABOUT FREEZE (6+ s): the service onCreate constructed the heavy analysers
  (Driver/Road/Sign TFLite models + GPU delegate) SYNCHRONOUSLY on the main
  thread, blocking the UI for ~6 s. Moved that model loading to a background
  coroutine; submitFrame already null-guards the analysers, so frames are simply
  skipped until they are ready. signs is now nullable to allow this.

- STREAMS BLACK / ONLY ONE STARTS: the app now opens on About (v18.7), so when
  the cameras bound at startup the Detector PreviewViews did not yet exist — the
  Preview use cases bound to surfaces that were never created, and v18.8's
  surface-only re-issue on attach does NOT start a stream bound to a non-existent
  surface. FIX: the FIRST time the PreviewViews actually attach, do ONE debounced
  rebind so CameraX connects to the real, laid-out surfaces; later tab switches
  still only re-issue the surface (no rebind storm). One rebind total on first
  attach, not per role, not repeated.

## v18.8 — fix camera rebind storm (regression) and thermal sign throttling
Critical fixes after the 2026-06-21 evening drive log, which showed 188 full
camera rebinds in one session and worse stream reliability than before.

- ROOT CAUSE of black/frozen streams and the About-view freeze: the view-attach
  handler and startup were calling rebind() (unbindAll + full re-bind of BOTH
  cameras) repeatedly, on the MAIN THREAD. v18.6 amplified this (double rebind per
  attach + two at startup). Every rebind briefly tore both streams down, and the
  main-thread churn froze the UI (About) on start.
  FIX: tab re-attach now only RE-ISSUES the preview surface provider (cheap, no
  teardown); startup binds once then does two cheap surface refreshes (no
  rebinds); resume() does one rebind (the OS really did unbind) + one surface
  refresh. rebind() is now reserved for genuine app background/restore only.
  This collapses ~188 rebinds/session down to roughly one bind + a few surface
  refreshes, eliminating the stream teardown churn and the About freeze.
- Sign recognition while hot: the drive heated the device (thermal up to x2.5),
  and thermal back-off had stretched sign detection to every 5th-6th frame, so
  signs were passed unread. Capped thermal back-off for SIGN at x1.5 (=> at worst
  ~every 3rd frame), keeping speed-limit signs catchable when hot while other
  roles still throttle fully.

## v18.7 — open on About; About moved to leftmost tab
The app now opens on the About view, which is moved to the leftmost (default) tab
position with Detector immediately to its right (order: About, Detector, Summary,
History, Log, Settings). Starting on About gives the cameras time to initialise in
the background before the Detector view is shown, reducing the chance of a blank/
frozen road preview on first view. Camera startup is independent of the active tab
(it runs on service-connect / permission-grant), so the head-start is real.
History back-button updated to return to Detector.

## v18.6 — remove consent dialog; harden road-view startup
- Removed the initial consent/info dialog. The app now goes straight to the
  Detector view; camera startup no longer waits on the dialog. (The PrivacyNotice
  composable is retained in code but not shown.)
- Road view occasionally froze or was blank on startup and only recovered after
  switching tabs. On a cold start the road TextureView surface is sometimes not
  ready when CameraX first binds, and only a single rebind was scheduled at that
  point. Added staggered self-heal rebinds: the view-attach handler now does the
  same double rebind (150 ms + 600 ms) that app-resume uses, and start() now
  rebinds twice (700 ms + 1500 ms) to cover variable cold-boot surface timing.
  This makes the blank/frozen road view self-heal without a manual tab switch.

## v18.5 — lane overlay: straight converging lines, lower half only
Reworked the forward-tilt model after feedback. The previous version bowed the
lines, which is wrong: a straight road has straight lines. Now the overlay draws
STRAIGHT lane lines in the LOWER HALF of the road view (bottom edge up to
mid-height), and forward tilt controls how far the two lines CONVERGE toward the
view centre as they rise (the vanishing-point / perspective effect). Real road
bends are preserved (the detected line direction is carried through before
convergence). 0 = no convergence; 1 = tops meet at centre. Validated: bottom
anchored at the bottom edge, top fixed at mid-height, straight segments, lines
narrow toward the top as tilt rises.

## v18.4 — lane forward-tilt calibration, driver-view zoom-out, AAB in CI
- Lane mount calibration gains a Forward tilt parameter: warps the lane overlay
  with a perspective bow so the lines match the real road lines at a distance,
  pivoting on the horizon anchor so it does NOT move where the lines meet at the
  horizon (validated: both endpoints fixed for all tilt values; only the mid-line
  bends). Settings slider 0..1.
- Driver view can be zoomed out (Settings slider, 50-100%): shrinks the rendered
  preview and its overlay together for mounts where the camera sits close to the
  driver's face and the head fills the frame. View-only; sensor FOV and detection
  are unchanged (a lens cannot optically zoom wider than its native FOV).
- CI: the signed build now also produces a Google Play Android App Bundle
  (bundleRelease) and uploads it as DBM-aab-release. Same release signing config
  as the APK; built only when signing secrets are present.

## v18.3 — enable GPU delegate (add gpu-api artifact)
On-device the GPU delegate failed to load (NoClassDefFoundError on
GpuDelegateFactory$Options) and the detector fell back to NNAPI. The factory/
options classes live in tensorflow-lite-gpu-api, which was not declared; added it
(pinned to 2.16.1). The three-tier GPU -> NNAPI -> CPU selection can now actually
try GPU. NNAPI (NPU) remains the proven working fallback. Confirmed from the
2026-06-21 drive log that NNAPI was active and the app ran cleanly post-v18.2.

## v18.2 — fix startup crash from thermal monitor init
ThermalMonitor was built as a field initializer (private val thermal =
ThermalMonitor(this)) whose constructor immediately called getSystemService.
Because the activity binds the service with BIND_AUTO_CREATE while the consent
dialog is shown, onCreate/field-init ran before the Service context was fully
attached, so getSystemService crashed the service at startup (right after the
info window, before "I understand"). Fixed: the monitor is now constructed in
onCreate (context ready); its PowerManager lookup is lazy and exception-guarded;
and start() is wrapped so a thermal failure disables backoff rather than
crashing. All thermal references are null-safe.

## v18.1 — real thermal backoff wired to the governor
Added ThermalMonitor: reads the OS thermal STATUS (API 29+ listener) and HEADROOM
forecast (API 30+), maps them to a backoff multiplier (NONE/LIGHT x1, MODERATE
x1.5, SEVERE x2.5, CRITICAL x4+), and feeds it to the ProcessingGovernor so the
expensive analyses slow down as the device heats up — easing off before the OS
forcibly throttles. Headroom gives earlier backoff than the discrete status.
Driver monitoring stays full-rate until CRITICAL. All thermal readings are logged
(status changes immediately; headroom every ~15 s) and the governor's effective
intervals are logged every ~10 s, so the behaviour can be verified on a drive.
Version-guarded for older devices (no-ops below API 29/30); needs no permission.

## v18.0 — performance pipeline: map-first eviction, predictive OCR, context throttling, GPU
Four linked performance features:
- Map-first cache eviction: a remembered sign that fails re-confirmation N times
  (Settings-configurable, default 3) when there was a genuine read opportunity is
  forgotten, reverting that segment to the map. Handles temporary signs being
  removed. Validated: normal eviction, no false eviction without a read chance,
  miss-streak reset on re-read.
- GPS-speed-predicted OCR timing (SignApproachPredictor): OCR fires at the frame
  where an approaching sign is largest within its readable window, not every
  frame. Fast approaches read early; distant readable signs still always get a
  read (fallback). The map carries the limit meanwhile, so deferral is free.
- Context-gated throttling (ProcessingGovernor): driver monitoring always full
  rate; sign/lights/following/lane throttled by map context, lead presence and
  lane stability. Cuts heat on long open stretches, surges in town. Driver path
  resists thermal backoff except under severe pressure.
- Sign detector delegate upgraded to GPU -> NNAPI -> CPU (was NNAPI -> CPU), for
  better sustained throughput and lower heat; logs which delegate is active.

These were validated as components in Python mirrors; on-device behaviour (and
the GPU delegate availability on the S24) needs a real run with logs.

## v17.27 — no_turns harmonised
no_turns redrawn as two curved arrows (left + right) sharing a stem, both under a
single red slash, consistent with the curved no_left_turn/no_right_turn signs.

## v17.26 — stop sign from supplied artwork
Replaced the hand-built STOP sign with the user-supplied artwork (AB4.svg):
clean red octagon, white border, proper STOP lettering.

## v17.25 — curve and children signs from supplied artwork
Replaced the hand-drawn curve_left, curve_right and children signs with the
user-supplied official EU artwork (A1b -> curve_left, A1a -> curve_right,
A13a -> children). Converted from SVG with nested transforms resolved and
strokes/fills preserved.

## v17.24 — pedestrian and roadworks signs from supplied artwork
Replaced the hand-drawn pedestrian and roadworks pictograms with the user-supplied
vector artwork: pedestrians is now the Greek-style yellow-background crossing sign
(figure walking on zebra stripes); roadworks is the standard worker-with-shovel.
Pictograms extracted from the source SVGs and composited into the sign templates.

## v17.23 — curve signs centred on triangle incentre
curve_left/curve_right centred on the triangle's incentre so spacing to each red
edge is equal; curve enlarged slightly while staying clear of the edges.

## v17.22 — curve sign sizing
curve_left/curve_right symbols reduced to sit comfortably within the triangle
with clear margin from the red edges.

## v17.21 — curve signs as continuous curve
curve_left/curve_right redrawn as a smooth continuous curve (no straight segment),
with a larger arrowhead, centred within the triangle.

## v17.20 — u-turn and curve sign tweaks
no_u_turn arrow mirrored horizontally and slightly reduced; curve_left/curve_right
fitted within the triangle with arrowheads now wider than the line stroke.

## v17.19 — no-turn, no-u-turn and curve signs redrawn to EU standard
Checked against the Vienna Convention designs and corrected:
- no_left_turn / no_right_turn now use a curved arrow (rising then hooking to the
  side) with the 45-degree red slash, instead of the previous angular shaft.
- no_u_turn now a smooth U-shaped arrow with downward head and red slash.
- curve_left / curve_right now the EU bent-road shape (no arrowhead).

## v17.18 — sign artwork refinements (round 3)
roundabout arcs moved nearer the outer edge with arrowheads wider than the line;
slippery_road car moved lower with wheels tucked under the body; no_left_turn /
no_right_turn arrows enlarged and solid black with the broken stub aligned under
the shaft.

## v17.17 — sign artwork refinements (round 2)
keep_left/keep_right now use the ahead_only arrow rotated diagonally;
roundabout arrows moved nearer the outer edge with larger gaps and arrowheads
aligned to the arc centrelines; slippery_road car made smaller with the two
skid lines now vertical.

## v17.16 — sign artwork refinements
Per-sign visual corrections: larger ahead_only arrow; end_limit ring widened to
match restriction signs with thicker diagonals reaching the ring; keep_left/right
now proper diagonal arrows; centred STOP text; larger no_straight arrow;
roundabout shown as three arrows around the border; no_left_turn/no_right_turn
now broken (gapped) turn arrows; slippery_road now a car over two wavy lines.

## v17.15 — fix no-overtaking and roadworks sign artwork
Audited all 21 sign drawables by rendering them. Two more were wrong:
- no_overtaking showed two plain rectangles; redrawn as the correct two cars
  (red + black) in a red ring.
- roadworks showed an unclear figure; redrawn as a worker with a shovel at a
  mound, distinct from the pedestrian/children warnings.
The other 19 (including the v17.14 stop/yield fixes) verified correct against
EU/Vienna Convention designs. Overlay artwork only; detection unchanged.

## v17.14 — correct stop and give-way sign artwork
Two sign icons were drawn incorrectly (reported from a test drive):
- Stop sign was a red octagon with a plain white bar, which read as a no-entry
  sign. Redrawn as the correct red octagon with white "STOP" text.
- Give-way (yield) had a white interior. Greece (per the Vienna Convention,
  like Finland/Poland/Sweden/Iceland) uses the YELLOW-background give-way sign;
  redrawn with a red border and yellow interior.
Both verified by rendering the vector drawables. Detection/classification was
already correct — this was overlay artwork only.


Versioning convention: **major** version increments when new features are
added; **minor** version increments for corrections. The version appears in
every produced package filename (e.g. `DMS-v1.0-release.apk`) and in the
in-app About screen.

## v17.13 — real EU sign graphics in the lower-left overlay
The lower-left "recent signs" overlay previously drew a red ring + TEXT label.
Replaced with actual EU-standard sign graphics, transparent OUTSIDE the sign
shape (the sign keeps its proper white/coloured interior; no white box around it).
- Added 21 VectorDrawables (res/drawable/sign_*.xml) for the full detector class
  set, authored to EU/Vienna-Convention style: prohibition (red ring, white
  interior) for the no-turn/no-overtaking signs; red disc + white bar for no
  entry; blue discs for keep-left/right, ahead-only, roundabout; red octagon for
  stop; inverted triangle for yield; white/red warning triangles for
  pedestrians/children/roadworks/curves/slippery; derestriction circle for end
  of limit; and a generic speed-limit ring.
- signDrawable(classId) maps each of the 21 detector classes to its drawable;
  the overlay now renders the graphic at 77 dp, keeping the 3 s linger.
Authored as vectors (not third-party image assets) for correct licensing, crisp
scaling, transparent background, and minimal APK size.

## v17.12 — CRITICAL OCR fix (digit clipping) + dual-sign split
Real drive proved the box misalignment is NOT cosmetic: 50 read as 5, 100 as 10,
worse for signs on the RIGHT — the crop was clipping the trailing digit (box
shifted toward centre, so a right-side sign loses its right edge). Fixes:
- OCR crop horizontal padding widened 0.12 -> 0.45 (asymmetric: wide horizontally
  to recover a clipped digit even when the detector box is offset; vertical pad
  0.20). Reading the whole number matters more than a tight crop.
- DUAL-SIGN SPLIT (user idea): a box taller than ~1.6x its width is treated as
  possibly two stacked signs; it is split into top/bottom halves, each OCR'd
  separately, and the plausible speed-limit reading wins (prefers the top). This
  targets the commonly-missed stacked-sign case.
- Retains the v17.8 "OCR crop:" diagnostic so the exact box vs crop numbers are
  visible next drive.
STILL OPEN (next): the ROOT cause of the box shift (detector coordinate mapping)
needs the OCR-crop log numbers from a drive to pin down — the wide pad is a
robust mitigation, not the root fix. Also pending: 3-axis lane calibration
(up/down, left/right, perspective), camera-init delay on Detector view, optional
log tab. These follow once the OCR fix is confirmed.

## v17.11 — fix release build (R8 missing-classes) so signed APK completes
The v17.10 signing wiring worked (validateSigningRelease passed — keystore,
alias, passwords all accepted), but the release build then failed at
minifyReleaseWithR8: R8 fails on missing classes by default, and MediaPipe/
TensorFlow-Lite-GPU/AutoValue reference optional classes not on the runtime
classpath. Fixes:
- Added -dontwarn rules (proguard-rules.pro) for every missing reference R8
  reported: com.google.mediapipe.proto.**, javax.lang.model.**,
  autovalue.shaded.**, com.google.auto.value.**, org.tensorflow.lite.gpu.**.
- Disabled R8 minification on release for now (isMinifyEnabled=false): on an
  ML-heavy app, shrinking can strip reflection-loaded code and crash at launch
  in ways that don't show at build time. During active development a reliable
  signed APK matters more than a shrunk one. The proguard rules remain in place,
  so re-enabling for a Play Store build is one line + thorough testing.
Result: assembleRelease now completes and produces a SIGNED release APK
(DBM-apk-release artifact) that installs as an update over prior releases.

## v17.10 — release APK signing via GitHub secrets
CI now produces a properly SIGNED release APK, so app updates install over each
other without requiring an uninstall (the debug-keystore mismatch is resolved).
- build.gradle.kts: added a `release` signingConfig that reads the keystore path
  (KEYSTORE_FILE) and STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD from environment
  variables. Applied to the release build type only when the keystore is present,
  so local/secret-less builds still succeed (unsigned) instead of failing.
- android-ci.yml: decodes KEYSTORE_BASE64 secret into a keystore file, passes the
  alias/passwords as env vars, and runs assembleRelease (plus assembleDebug).
  Uploads DBM-apk-debug and DBM-apk-release artifacts separately.
Secrets used (must exist in the repo): KEYSTORE_BASE64, KEY_ALIAS (com-dbm),
KEY_PASSWORD, STORE_PASSWORD. applicationId = com.DBM.
NOTE: the KEY_ALIAS value must match the alias actually inside the keystore
(verify with: keytool -list -keystore <file>).

## v17.9 — lane overlay alignment + wider horizon calibration
Fixes the "lane lines sit too high / do not match the road" positioning:
- ROOT CAUSE found: the overlay drew every lane line's TOP at a hardcoded 0.55
  of frame height, regardless of the horizon calibration or where detection
  actually started. So adjusting the horizon moved the detection ROI but NOT the
  drawn lines. The overlay now draws to the actual calibrated ROI top
  (result.roiTopFrac), so lines track the road region and follow calibration.
- Horizon calibration range widened from +-0.2 to +-0.4, and the internal ROI
  clamp relaxed from 0.35..0.75 to 0.15..0.90, so a steeply tilted/offset mount
  can pull the road region far enough.
NOTE (honest scope): this fixes lane-line POSITIONING. It does NOT improve how
well the classical detector LOCKS ONTO real markings — that, and reliable
solid/double-solid/dashed distinction, need a learned lane-segmentation model
(UFLD-style), planned AFTER the two pipeline features (map-first sign procedure,
GPS-predicted OCR timing).

## v17.8 — box-alignment diagnostics (shifted speed-sign boxes)
Investigating the reported speed-sign bounding boxes shifting toward frame centre
(right near the left edge, left near the right edge — the signature of an
aspect-ratio mismatch). Code review found:
- The detector's coordinate math is correct (letterbox fit + proper inverse).
- The overlay display math accounts for PreviewView FILL_CENTER crop via
  result.frameAspect.
- IMPORTANTLY: the OCR crop uses the detector's normalized coords against the
  actual frame dimensions — INDEPENDENT of the overlay display mapping. So the
  visible shift is most likely cosmetic (overlay-only) and is probably NOT
  cropping the wrong region for OCR.
Added diagnostics to confirm at runtime: logs the analysed frame dimensions/aspect
per camera, and logs each OCR crop's normalized + pixel region. These will show
whether the crop lands on the sign (cosmetic shift) or off it (real OCR problem),
so the fix — if any is needed — targets the right path instead of guessing.

## v17.7 — sign overlay UI + MediaPipe timestamp & evidence fixes
Map now confirmed WORKING on-device (v17.6 fix verified: zero rtree errors, db
opened schema=3, 1.26M segments). This release adds the requested overlay and
fixes two issues seen in the 19-June-2 log:
- Speed-limit roundel (lower-right) reduced 20% (96 -> 77 dp).
- NEW lower-left overlay: other detected signs (no-left/right/U-turn, no-entry,
  warnings, etc.) shown ~3 s after they leave the frame, so the driver can
  register turn restrictions at lights/junctions. Up to 3 at a time.
- MediaPipe "smaller timestamp than processed" errors (which killed driver
  analysis repeatedly): now feeds a strictly-monotonic timestamp and catches any
  landmarker timestamp exception so one bad frame can't break the run.
- Map db open no longer logs a scary permission error: picks the first READABLE
  candidate path (the unreadable legacy /sdcard path was tried first and threw).
Observations 1-4 (wrong limits on Agios Thomas/highway/Markopoulou) are real
OSM map-data / matching issues now that the map works — these are the validation
targets for the next step (map-first sign procedure with cache eviction).

## v17.6 — CRITICAL fixes: map R-tree unavailable on Android + crash
The 19-June drive exposed two serious bugs:
- MAP DID NOT WORK ON DEVICE: "no such module: rtree" fired on every GPS fix
  (28,848 times). Android's built-in SQLite omits the R-tree extension, so the
  spatial query always failed and the map contributed nothing. Fixed WITHOUT a
  new dependency: the pre-processor now stores plain bbox columns
  (minLat,maxLat,minLon,maxLon) with an index on segments, and the app queries
  those instead of an rtree virtual table. DB schema bumped to v3 — REGENERATE
  greece.db with the updated tool (v2 dbs lack the bbox columns).
- FATAL CRASH "Can't copy a recycled bitmap" in EvidenceStore: the camera could
  recycle the road frame before the async evidence copy ran. Now guards
  isRecycled and catches the race so evidence-saving can never crash the service.
(Camera front-view init still needs the earlier tab-switch workaround in some
cases; and the map-first sign procedure + GPS-timed OCR you proposed are coming
next — they are features, handled separately from these stop-the-bleeding fixes.)

## v17.5 — map download manager (countries/regions, versioning) (step 3)
The app can now download speed-limit maps from the RFSAT portal, with the
country/region hierarchy mirroring geofabrik (Greece is a single country-level
entry, as geofabrik does not subdivide it; countries geofabrik splits — Germany,
France, UK — would appear with their sub-regions automatically).
New package com.rfsat.dms.maps:
- MapCatalog — parses the manifest at www.rfsat.com/products/maps/index.json
  (nested countries->regions, or flat regions). Mirrors geofabrik ids/names.
- MapRepository — tracks installed regions and versions (installed.json), and
  compares against the catalog to flag NOT_INSTALLED / INSTALLED /
  UPDATE_AVAILABLE / UNSUPPORTED_SCHEMA.
- MapDownloader — downloads a region .db with progress, VERIFIES sha256 before
  committing (downloads to .part, renames on success), and can fetch the catalog.
Settings -> Speed-limit maps: "Check for maps" lists regions with size/version;
Download/Update (with a confirm dialog showing size + data date and a Wi-Fi
hint), Delete, and Import file (for manual/offline install). Outdated maps are
detected by version and re-downloaded only after user confirmation.
Validated: catalog parse + version-status logic (incl. Greece-no-subregion,
Germany-with-subregions, outdated->UPDATE_AVAILABLE, future-schema->UNSUPPORTED).
Note: the live fetch/download works against the real server once index.json and
the .db files are hosted; sha256/size come from the server manifest.

## v17.4 — sign-recognition timing instrumentation + CPU fallback bump
Speed-limit recognition measured 2-3 s on a desktop screen test. To find where
the time goes on-device (detector vs OCR, and whether the NPU is used), added:
- Per-stage timing logs: "timing: detector N ms" and "timing: OCR N ms (crop WxH)".
- Delegate log at startup: whether the sign detector runs on the NNAPI (NPU)
  delegate or fell back to CPU — the most likely cause of a slow detector stage.
- CPU fallback raised from 2 to 4 threads (S24 has 8 cores) — roughly halves
  detector time IF it is running on CPU.
No behavioural change to detection logic; this is to gather real on-road numbers
before optimising (e.g. a possible input-size reduction, which trades against
small-sign range and should not be done blind).

## v17.3 — map import via file picker (fix for scoped storage / Galaxy S24)
A manually-placed greece.db in the Download folder could not be opened on modern
Android (13+, e.g. Galaxy S24): scoped storage blocks the app from reading
arbitrary shared-storage files, and the legacy /sdcard path does not resolve on
SD-card-less devices. Fixes:
- New "Import map database…" button in Settings -> Speed-limit map. It opens the
  system file picker; pick greece.db wherever it is, and the app copies it into
  its own private storage (filesDir/maps/greece.db) where it can always be read.
  This works regardless of scoped-storage rules. Restart monitoring to load it.
- OsmMap search paths now prefer the app private/external dirs (always readable,
  no permission) and only fall back to public Download paths for older devices.
After importing, the log shows "opened map db ... region=Greece schema=2".

## v17.2 — slim map database (schema v2) + RFSAT-DBM dev path
All-of-Greece came to 537 MB (74% of it geometry stored as float64). Schema v2
slims it, handled in the off-device pre-processor (dbm-tools); the app side is
the matching decoder change:
- Coordinates now decoded as scaled int32 (degrees * 1e7, ~1 cm) — half the size
  of float64, lossless at GPS scale. App unpackCoords reads the v2 int32 format.
- (Pre-processor also: Douglas-Peucker polyline simplification at 2 m, road-type
  filtering of non-driveable ways, and an optional --drop-untagged-minor flag.)
- OsmMap now also searches /sdcard/Download/RFSAT-DBM for the db (matches where
  the test file was placed), in addition to the app dirs and /sdcard/Download.
IMPORTANT: regenerate greece.db with the v2 tool — a v1 (float64) db will not
decode correctly in this app version.

## v17.1 — map backend: SQLite + R-tree spatial database (step 2)
OsmMap now reads a SQLite + R-tree speed-limit database (produced off-device by
dbm-tools/osm_to_speedlimitdb.py) instead of parsing a bundled .osm in memory.
Per GPS fix it queries the R-tree for segments NEAR the point and applies the
SAME validated heading/hysteresis matching to that small candidate set — so it
scales to a whole-country database (e.g. all of Greece) without loading the
network into memory. The matching/fusion logic is unchanged.
- Database lookup order: app files/maps, app external files/maps, then
  /sdcard/Download (for manual adb-push during development).
- Default db name greece.db; absent db -> sign+cache only, no crash.
- Validated: R-tree returns only nearby candidates and matching correctly
  disambiguates parallel roads (60 road vs parallel 30 service road).
Next (step 3): region-download manager fetching per-region .db from
www.rfsat.com/public/maps/v1/ with an index.json.

## v17.0 — speed-limit fusion ported into the app (major feature)
Ports the validated MATLAB fusion prototype to Kotlin. The current speed limit is
now derived by combining three sources instead of the camera alone:
  1. LIVE camera sign (highest priority; also recorded to the cache)
  2. Remembered-sign CACHE (per-segment, from earlier passes)
  3. OSM MAP baseline (bundled extract)
New package com.rfsat.dms.fusion:
- OsmMap.kt — loads a bundled OSM extract and matches GPS to road segments with
  heading-consistency + hysteresis (ports loadOSM.m + the upgraded matchSegment.m).
- SignLimitCache.kt — per-segment remembered-sign store with confirm-before-trust,
  age decay, and roadworks-as-temporary (ports the signCache .m files). Local only.
- SpeedLimitFuser.kt — the three-source priority ladder (ports fuseSpeedLimit.m).
Integration: fusion runs in the 1 Hz GPS loop, consuming SpeedMonitor.position
(v16.3), the camera's committed OCR read, and the warn_roadworks class; the fused
limit feeds the existing compliance scorer / speed-limit roundel.
Map data: optional bundled assets/speedlimits.osm (see assets/README-speedlimits.md).
If absent, fusion runs sign+cache only — no crash. Map parsing is off the main
thread. Logic validated against the MATLAB two-pass behaviour before shipping.
Also added: docs/context-gated-throttling-sketch.md (next-step design).

## v16.9 — improve speed-limit sign reading rate (fix)
Ground-truth scoring of the 18-June-2 drive showed the camera read only 7 of 19
speed signs, so fusion was no better than map-only. Funnel analysis found the
bottleneck: of 51 speed-sign sightings, 42 were "too small for OCR" — the OCR
itself succeeded 7/9 times when it ran, but it rarely ran. Two fixes:
- Sign detection throttle raised from every-3rd to every-2nd road frame, giving
  more chances to catch the brief window where a passing sign is readable.
- OCR-attempt gate lowered 0.035 -> 0.028 (more signs attempt OCR), WITH a new
  safeguard: a read from a crop smaller than 0.036 (the smallest proven-readable
  size) is tentative and must be confirmed by a second agreeing read before it
  commits — so lowering the gate cannot let a tiny-crop misread set the limit.
Modelled on the real drive: OCR-eligible sightings roughly double (9 -> 17), and
the finer sampling adds more on top. Reads from large crops still commit
immediately as before.

## v16.8 — camera-restore robustness + large speed-limit sign overlay
- Recents restore: added launchMode="singleTask" so tapping the app from the
  Recents list (not just the launcher icon) brings the existing screen to front
  instead of landing on a stale task.
- Frozen/blank camera on start: the camera could bind before the preview surface
  was ready, showing a frozen or blank image until the user switched tabs. start()
  and resume() now re-issue the surface providers and rebind shortly after
  (700 ms / a second pass at 600 ms), self-healing this without a tab switch.
- resume() now does a double rebind to cover slow surfaces on foreground return.
- NEW large speed-limit sign: a big (96 dp) red-ring roundel showing the current
  limit is overlaid in the lower-right of the road view, ~3-4x the small status
  roundel, for at-a-glance visibility while driving.
- NOTE on the 18-June-2 drive: the speed icon appearing "stuck" was the OCR
  missing most signs (7 of 19 ground-truth signs read; 0 of the 70s and 120s,
  which pass fastest), compounded by thermal throttling (status 3 most of the
  drive). The limit held its last read value through the gaps. This is what the
  map-fusion (in development) is designed to fix; the ground-truth CSV will help
  validate it.

## v16.7 — fix blank screen when returning from another app (fix)
- Switching to another app and back left the camera previews blank. Cause:
  stopping the activity makes CameraX unbind the cameras, but because the
  PreviewView does not always detach/re-attach, the attach-listener rebind never
  fired on return. Added MainActivity.onResume -> PhoneCameraManager.resume(),
  which re-establishes the camera binding (or starts from scratch if the
  provider was not yet ready). Safe no-op before cameras are set up.

## v16.6 — manual start + configurable mirror-check reminders (feature)
- Analysis no longer auto-starts when the app loads; it waits for the user to
  press Start. This avoids false warnings while the vehicle is still stationary
  (parked / GPS settling) before the drive begins. The mirror-check timers also
  re-arm on Start, so the countdown excludes parked time.
- Mirror-check reminders are now SEPARATE for the rearview mirror and the side
  mirrors, each with its own configurable interval in Settings
  ("Mirror-check reminders", 0-300 s, 0 = disabled). Previously a single 120 s
  timer was re-armed by any mirror glance, so a side glance could mask a missing
  rearview check; they are now tracked independently with distinct warnings.

## v16.5 — speed-limit reading actually works now (fix)
The 18-June drive log (with v16.2 OCR diagnostics) revealed the speed icon never
showed because of TWO bugs, both fixed:
- OCR read the numbers CORRECTLY (log shows reads 20, 80, 60, 70, 120 matching
  the route) but they were never adopted: the majority vote required 2 matching
  reads, yet at driving speed each sign is legible for only ~1 frame, so two
  agreeing reads never accumulated. A single confident read is now ADOPTED
  IMMEDIATELY, with a misread guard (switching to a DIFFERENT value needs strong
  detection confidence or a confirming read).
- The adopted limit was a per-frame local reset to null every frame, so even the
  one adoption that occurred was lost immediately. The committed limit now
  PERSISTS across frames (committedLimit field) so the speed icon stays shown
  until a new sign changes it.
- Lowered SPEED_OCR_MIN_BOX 0.04->0.035 (legible signs sat just under the gate)
  and SPEED_MIN_CONF 0.55->0.45 (good reads were rejected at 0.45-0.46 conf).
- Removed the now-unused vote machinery.

## v16.4 — lane mount-calibration (feature)
- NEW Settings: "Lane detection — mount calibration" with two sliders. The lane
  detector assumed a level, centred, forward-facing camera; a tilted or offset
  phone mount shifted where the road sits in frame and degraded lane tracking.
  * Horizon (-0.2..+0.2): shifts the road region-of-interest up/down for camera
    tilt.
  * Centre (-0.2..+0.2): shifts the expected road centre left/right for an
    off-centre mount.
  Persisted and applied live. (From the 18-June drive: lane tracking failed to
  follow road markings, likely mount-tilt related.)
- NOTE: the 960-imgsz sign model (patience=75) was evaluated and NOT adopted —
  it matches the 640 model's accuracy (mAP50 0.541 vs 0.54) while costing ~2.25x
  more compute per frame (18900 vs 8400 anchors), worsening thermal load for no
  accuracy gain. Kept the 640 model.

## v16.3 — GPS trace logging for map-based speed-limit cross-check (feature)
- SpeedMonitor now exposes the latest GNSS position (lat, lon) as a flow, for
  the planned map-based speed-limit cross-check, and can log each fix as a
  machine-parseable line: "GPS lat=.. lon=.. spd=.. acc=..".
- New Settings toggle "Log GPS trace (for map cross-check dev)", DEFAULT OFF.
  It records a precise location trace (personal data), so it is opt-in for
  development use; the trace stays in the local on-device log and is never
  transmitted. Enable it for one drive to capture a trace for the offline
  map-fusion development, then disable.
- No behavioural change when the toggle is off.

## v16.2 — drive-log fixes: speed OCR, road-box mirroring, thermal balance
From the 2026-06-17 drive (signs detected well, but numbers never read, plate
box mirrored, signs sparse later in drive):
- Speed-limit OCR: added full diagnostic logging (box size, read value, adopt);
  lowered SPEED_OCR_MIN_BOX 0.06->0.04 (signs were passing before reaching the
  old gate, so OCR never fired); relaxed vote 3-of-5 -> 2-of-4; OCR crop now
  padded ~12% and upscaled to >=96px shorter side (ML Kit reads small digits
  poorly). Should now actually read and commit numbers — and if not, the log
  will say whether OCR fired and what it read.
- Road/plate overlay mirroring: the rear preview is mirrored on some devices,
  flipping road/plate boxes left-right (same class of bug as the driver face
  box). Added a "Mirror road/plate boxes" Settings toggle (default off); flip
  it once if boxes track the wrong way. (The OCR/evidence crop already used true
  frame coordinates, so only the on-screen overlay was affected.)
- Thermal balance: road pipeline now fully suspended only at CRITICAL (was
  SEVERE). At SEVERE it keeps running rate-limited, so sign/hazard detection is
  preserved. The previous setting silently killed road detection for long
  stretches of a hot drive (53 signs in first 8 min vs 42 in next 2.5 hrs).

## v16.1 — build fix (correction)
- FIX SignAnalyzer compile errors: the new ocrSpeedLimit function had been
  inserted INSIDE analyze() (making it a local function — hence "private not
  applicable to local function", "unresolved reference ocrSpeedLimit" at the
  call site, and the cascaded Any-vs-Int type mismatch). Moved it out to be a
  proper sibling method of analyze(); the fallback-OCR block stays within
  analyze() as intended. Pure placement fix; logic unchanged.

## v16.0 — 21-class EU sign model + speed-limit tracking/OCR (major)
- Integrated the newly trained 21-class Mapillary EU sign detector
  (sign_eu.tflite, output [1,25,8400] = 4 box + 21 classes), replacing the old
  27-class model. SignDetector NAMES/category/turn-IDs rewritten to match
  labels.txt; numClasses now 21.
- Speed limits are a SINGLE generic class (speed_limit, id 9). The number is
  read at runtime by OCR with deferred reading: OCR runs only once the sign box
  is large enough (SPEED_OCR_MIN_BOX) — i.e. close enough to be legible — and a
  plausible value (multiple of 5, 5..130) is committed via the existing
  majority-vote buffer. This removes the digit-confusion of the old per-number
  classes.
- TurnMonitor updated for the shifted class IDs (no_entry now 6, ahead_only 14,
  no_turns 4 added). Turn logic is now driven ONLY by EU-detector signs
  (fromEuDetector flag); GTSRB fallback IDs no longer mis-feed turn detection.
- Model performance (validation): overall mAP50 0.54. Strong: speed_limit 0.75,
  stop 0.73, yield 0.72, no_overtaking 0.73, no_u_turn 0.66. Weak (as expected
  from sparse data): no_left_turn 0.23, no_right_turn 0.34; no_turns
  effectively untrained (1 val image).

## v15.11 — build fix (correction)
- FIX two missing imports introduced in v15.9/15.10:
  * FollowingDistanceMonitor used abs() (lead-sway detection) without importing
    kotlin.math.abs;
  * MonitorService used Detection as a parameter type (plate/evidence methods)
    without importing com.rfsat.dms.Detection — which cascaded into all the
    .left/.right/.top/.bottom member errors the compiler reported.
  Both added. Audited every kotlin.math and com.rfsat.dms reference across the
  module; all others are either imported or fully-qualified.

## v15.10 — smarter plate reading (refinement)
- Plate reading is now decoupled from hazard events and runs whenever a lead
  vehicle is closer than the safe distance, with a read-once-per-vehicle policy:
  * reads only while the lead is closer than safe distance (closest/clearest);
  * does NOT re-read the same lead vehicle once read with good confidence;
  * re-reads if the prior read was low-confidence, or when a DIFFERENT vehicle
    becomes the lead (detected via box position/size discontinuity, since track
    IDs are not propagated into Detection).
- Hazard evidence images now annotate with the plate already read for that lead
  (no fresh OCR triggered at event time). Plate reading remains OFF by default,
  local-only, never transmitted.

## v15.9 — mirror-aware gaze + lead-vehicle hazards + evidence capture (new features)
- Feature 1: mirror glances no longer flagged as eyes-off-road. Head PITCH is
  now estimated alongside yaw, and a glance is recognised as a mirror check
  using real mirror geometry — rearview (up + centred) vs side mirrors (down +
  to a side). Such glances suppress the off-road event and count as mirror
  checks
- Feature 2: NEW LEAD_VEHICLE_SWAYING — flags the vehicle ahead moving widely
  side-to-side within its lane (large lateral span without a sustained one-way
  move). Evaluated ONLY when the lead is closer than the safe distance (more
  reliable and cheaper, per the brief)
- Feature 3: NEW LEAD_HARD_BRAKING — flags the vehicle ahead braking hard /
  closing fast (rapid bounding-box growth). Also gated on proximity
- Evidence: on a serious lead-vehicle hazard, an image of the lead vehicle is
  captured to the local evidence store. OPTIONAL best-effort plate OCR (new
  Settings toggle, OFF by default) appends a plate reading when legible; stored
  locally only, never transmitted (user-consented forensic capture). Plate
  reading is unreliable at distance by nature — a failed read records no plate
  rather than a wrong one
- Thermal (from v15.8) retained

## v15.8 — stronger thermal mitigation (correction)
From the 2026-06-17 drive log: v15.6/15.7 fixes confirmed working (no launch
crash; MediaPipe timestamp errors down from 197 to 3; EU sign model recognising
signs well). However the phone still climbed to CRITICAL thermal and was
force-stopped by Android after ~4 min, restarting the app.
- Thermal response strengthened: at SEVERE+ the heavy road pipeline (4 ML
  models) is now SUSPENDED entirely, keeping only the lighter driver pipeline,
  which is the real way to shed heat. Rate factors also raised (severe x3,
  critical x5). Road analysis resumes automatically when the device cools.

## v15.7 — lint fix for thermal API level (correction)
- FIX lint error: PowerManager.addThermalStatusListener and the
  OnThermalStatusChangedListener type require API 29, but minSdk is 26. Both
  call sites are now guarded by Build.VERSION.SDK_INT >= Q and the listener is
  lazily created so its API-29 references never resolve on Android 8/9. On
  those older devices thermal throttling is simply inactive (analysis runs
  full-rate); on Android 10+ it works as intended. The APK compiled fine in
  v15.6 — this was the lint quality gate catching a real older-device crash

## v15.6 — drive-log crash fixes + thermal throttling (corrections)
From a ~7-hour real drive log (Galaxy S24, Android 16):
- FIX launch crash (SecurityException): a camera-typed foreground service may
  only be started AFTER the CAMERA runtime permission is granted on Android 14+.
  The service is now started from the permission result, not eagerly in
  onCreate. (App previously crashed once on first launch, then recovered.)
- FIX Exit crash (IllegalArgumentException "Service not registered"): the
  activity onDestroy unbound the service a second time after Exit had already
  unbound it. Guarded both unbind paths.
- FIX 197 MediaPipe "smaller timestamp" errors: under thermal load frames
  arrived out of order; the face landmarker now skips any frame whose timestamp
  is not strictly newer than the last processed (MediaPipe video API requires
  increasing timestamps).
- NEW thermal throttling: analysis frame intervals stretch as the device heats
  (moderate x1.6, severe x2.5, critical x4), shedding load to mitigate the
  overheating seen on long drives instead of letting the phone cook.

## v15.5 — UI simplification + overlay-alignment fix (corrections)
- Header: score shown as percentage (e.g. "94%") instead of "Compliance N/100";
  current speed shown plainly in km/h without the "GPS" suffix; speed limit
  shown as the sign roundel with a very short "Limit" label; the internal
  "concurrent"/"multiplexed" camera-mode word removed (only a "single-cam"
  hint shows in the fallback case)
- FIX sign/road bounding boxes "floating" around their target: the overlay
  mapped coordinates assuming a 4:3 frame, but v15.0 raised the analysis
  resolution to 16:9. The overlay now uses the real frame aspect ratio
  (carried on AnalysisResult), and the camera previews are pinned to 16:9 to
  match — boxes now align with the video
- When detection is stopped/paused: no overlays are drawn and the Detector
  screen's detection list is cleared
- Control buttons (Pause/Stop/Exit) made shorter (reduced height, tight padding)
- History: "Violation history" -> "Violations"; severity filter chips show
  first-capital labels ("Critical", "Warning", "Info")
- About: data statement split into two lines — "Data processing and storage
  occurs ONLY on this device." and "NO data or information is transmitted to
  3rd parties."
- Speed-limit fault count moved conceptually to History (the live header no
  longer shows the raw compliance fraction)

## v15.4 — speed-limit robustness, header icon, Exit fix (corrections)
- Speed-limit adoption hardened: requires higher detector confidence AND a
  majority vote over the last few readings before adopting, instead of two
  consecutive frames. Reduces wrong numbers; small bounded delay. (Note:
  mis-read digits ultimately reflect sign-model accuracy — see notes)
- Header: speed limit shown as a small red-ring sign roundel with the value
  inside, next to a shortened "Speed limit" label
- Exit button now fully closes the app: stops analysis, releases cameras,
  unbinds and stops the foreground service (which was keeping the app alive),
  and clears the foreground notification. Previously Exit closed only the UI
- (Sign bounding boxes not fully enclosing the sign reflect the trained
  model's box tightness, not a decode error)

## v15.3 — driver overlay mirror made user-correctable (correction)
- The driver face-box mirroring (added in v15.2, correct for the mirrored
  front-camera preview) is now a Settings toggle "Mirror driver face box",
  default ON. This keeps the box aligned on the standard mirrored preview and
  lets it be flipped once on any device that behaves differently, without a
  code change. Read fresh each frame so the toggle takes effect on return to
  the Detector tab

## v15.2 — driver face overlay fix (correction)
- FIX driver face box labelled "Object": the overlay always used the generic
  detection-class group name. Driver-state boxes carry a descriptive label
  ("EYES CLOSED", "yaw N") which is now shown instead
- FIX driver face box not tracking the face: the front (selfie) camera preview
  is mirrored by the system but the landmark coordinates were not, so the box
  moved opposite to the face. The driver card now mirrors the box x-coordinates
  to match the preview. Road overlays unchanged

## v15.1 — build fix (correction)
- FIX compile error: the resolution code used CameraRole.ROAD, but the road
  camera's enum value is FRONT (DRIVER/FRONT/REAR). Corrected both references.
  Audited all CameraRole, SignDetector constant and theme-colour references
  added in v15.0 — no others affected

## v15.0 — drive-test fixes, EU sign detector, UI controls (major)
DETECTION (from road-test feedback):
- Analysis resolution raised from the ~640x480 default to 1280x720 (road) /
  960x540 (driver). Distant and oncoming vehicles, motorbikes and pedestrians
  were too small to detect at the old resolution; this roughly doubles their
  pixel size. Addresses missed oncoming cars, intermittent pedestrians and
  missed motorbikes
- Lane fit now rejects physically-impossible "diverging" fits (lines that open
  outward with distance instead of converging to a vanishing point) and
  enforces a minimum coverage and correct side, fixing the lane overlay that
  did not follow the road
- (Bounding boxes that were too small were a symptom of the low resolution;
  the YOLO decode itself was verified correct against the reference image)

EU SIGN DETECTOR:
- NEW SignDetector: the Mapillary-trained one-stage detector (sign_eu.tflite,
  27 classes including no-left-turn, no-right-turn, no-U-turn) both localises
  and classifies signs, and is now the preferred sign path; the GTSRB
  classifier remains as a fallback. TurnMonitor now does DIRECTIONAL illegal-
  turn detection: a left turn under a no-left-turn sign fires, a right turn
  under it does not; no-U-turn fires on a near-reversal

UI (from feedback):
- Detection message list shortened (fewer lines) and camera areas enlarged
  vertically to use the freed space
- NEW control bar: Start/Pause toggle, Stop, and Exit buttons. Pause halts
  analysis (frames dropped) and resumes on demand; Exit closes the app

## v14.6 — build fix (correction -> minor increment)
- FIX compile error: MonitorService referenced TurnMonitor without importing it
  (every other detect-package class used by the service is imported explicitly).
  Added the missing import. Audited all newly-added detector classes for the
  same issue — none others affected (the rest are referenced only within the
  detect package and need no import)

## v14.5 — diagnostic logging of unrecognised signs (correction -> minor increment)
- The sign region proposer now records each candidate's dominant border colour
  (red/blue/yellow). When a sign-shaped region cannot be confidently classified
  by GTSRB, its colour and the best low-confidence guess are logged (rate-
  limited) to the diagnostic log. This is to measure how often EU signs absent
  from GTSRB — notably the no-turn prohibition signs (no left/right/U-turn) —
  appear during real driving, to inform whether a Mapillary-trained model is
  warranted. SignClassifier.inspect() returns the best class regardless of the
  confidence threshold for this purpose
- No behavioural change to recognition or events; logging only

## v14.4 — illegal-turn detection (new feature -> would be major, grouped as minor with docs)
- NEW ILLEGAL_TURN now active: TurnMonitor integrates gyroscope yaw-rate to
  detect a completed turn (heading change > 55 deg) and raises a warning when
  it occurs within 12 s of a sign that prohibits it — no-entry/no-vehicles
  (any turn into the restricted way) or ahead-only (any turn). Model-free,
  rate-limited, assistance-grade confidence; corroborated by the independent
  yaw signal. RecognisedSign now carries the GTSRB class id to drive this
- Documentation regenerated to reflect the full v14.x feature set

## v14.3 — road-sign recognition finalized + CI actions updated (correction -> minor increment)
- FIX sign category mapping: 15 of 43 GTSRB classes were mis-categorised
  (priority/yield/stop and the mandatory blue signs and derestrictions all
  defaulted to "Information"). Verified the full taxonomy and corrected to the
  proper two-way split — 28 Regulatory, 15 Warning (GTSRB has no information
  class). Colour-coding in the pictogram strip is now correct (e.g. Stop shows
  as regulatory, not info)
- Recognised signs are now logged (deduplicated) to the diagnostic log so they
  are traceable; speed-limit signs continue to set the active limit
- CI: bumped actions/checkout, setup-java and upload-artifact to v5 for
  Node.js 24 compatibility (clears the runner deprecation warning); no app
  code affected

## v14.2 — build fix (correction -> minor increment)
- FIX compile error: drawText was imported from the wrong package
  (androidx.compose.ui.graphics.drawscope) — the DrawScope.drawText extension
  taking a TextLayoutResult lives in androidx.compose.ui.text. Corrected the
  import; the detection-box label chips in the camera overlay now compile.
  First green-after-red fix from the CI build log

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
