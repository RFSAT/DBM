package com.rfsat.dms.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import com.rfsat.dms.AnalysisResult
import com.rfsat.dms.CameraRole
import com.rfsat.dms.Detection
import com.rfsat.dms.RiskType
import com.rfsat.dms.RiskEventCandidate
import com.rfsat.dms.alert.Alerter
import com.rfsat.dms.data.EvidenceStore
import com.rfsat.dms.data.OverlayVideoRecorder
import com.rfsat.dms.detect.ComplianceScorer
import com.rfsat.dms.detect.CrossChecker
import com.rfsat.dms.detect.FollowingDistanceMonitor
import com.rfsat.dms.location.YawRateMonitor
import com.rfsat.dms.detect.DriverAnalyzer
import com.rfsat.dms.fusion.OsmMap
import com.rfsat.dms.detect.LaneAnalyzer
import com.rfsat.dms.detect.RoadAnalyzer
import com.rfsat.dms.detect.SignAnalyzer
import com.rfsat.dms.detect.TrafficLightAnalyzer
import com.rfsat.dms.detect.TurnMonitor
import com.rfsat.dms.detect.VisualSpeedEstimator
import com.rfsat.dms.SpeedSource
import com.rfsat.dms.location.SpeedMonitor
import com.rfsat.dms.util.DLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Foreground service owning analyzers, compliance scoring, evidence store and
 * alerter. Frames arrive from PhoneCameraManager (Phase 1) or RTSP players
 * (Phase 2) — the analysis pipeline is source-agnostic.
 *
 * Road frames run three analyses: object detection (every frame), lane
 * analysis (every frame, cheap), sign OCR (every 3rd frame, costlier).
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "MonitorService"
        // How long a non-speed-limit sign stays on screen after the camera last
        // saw it, before being dropped.
        private const val SIGN_HOLD_MS = 3000L
        // Plate-read lead-identity thresholds (box continuity) and confidence.
        private const val PLATE_SAME_CX = 0.15f   // lead centre within this = same
        private const val PLATE_SAME_W = 0.35f    // lead width within 35% = same
        private const val PLATE_GOOD_CONF = 0.8f  // skip re-read above this
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val roadPermit = Semaphore(1)

    private var driver: DriverAnalyzer? = null

    // --- speed-limit fusion (map + remembered signs + live camera) ---
    private var osmMap: OsmMap? = null
    private val signCache = com.rfsat.dms.fusion.SignLimitCache()
    private val fuser = com.rfsat.dms.fusion.SpeedLimitFuser(signCache)
    private val governor = ProcessingGovernor()
    private val signApproach = com.rfsat.dms.detect.SignApproachPredictor()
    // Constructed in onCreate, not as a field initializer: its constructor calls
    // getSystemService, which is unsafe before the Service context is attached
    // (it ran during construction and crashed the service at startup).
    private var thermal: ThermalMonitor? = null
    /** Latest live speed-limit read from the camera, consumed by the fuser.
     *  -1 when none pending. Confidence proxied from detection. */
    @Volatile private var pendingSignLimit = -1
    @Volatile private var pendingSignConf = 0.0
    // true when a speed-limit sign was DETECTED (a real read opportunity) since the
    // last fuse step, even if OCR did not yield a confident number. Drives cache
    // re-confirmation/eviction: a genuine chance that didn't confirm counts as a miss.
    @Volatile private var pendingSawSignCandidate = false
    @Volatile private var lastMatchedSegId = -1L
    private var lastGovernorLogMs = 0L
    @Volatile private var roadworksInView = false
    private var lastFusePos: Pair<Double, Double>? = null
    // The speed limit currently shown to the driver. Latched: it persists until a
    // DIFFERENT valid limit (camera or map, camera taking priority via the fuser)
    // replaces it, so the display never blanks on a momentary unknown.
    @Volatile private var shownLimitKmh = -1
    private var road: RoadAnalyzer? = null
    private lateinit var lanes: LaneAnalyzer
    private var signs: SignAnalyzer? = null
    private val lights = TrafficLightAnalyzer(this)
    private val turns = TurnMonitor()
    private val visualSpeed = VisualSpeedEstimator()
    @Volatile private var visualSpeedKmh: Int? = null
    /** Copy of the most recent road frame, attached to speed violations. */
    @Volatile private var latestRoadFrame: Bitmap? = null
    private lateinit var evidence: EvidenceStore
    private lateinit var alerter: Alerter
    lateinit var speed: SpeedMonitor; private set
    val scorer = ComplianceScorer()
    val following = FollowingDistanceMonitor()
    val crossChecker = CrossChecker()
    private lateinit var yawRate: YawRateMonitor
    private var prevLeadWNorm = 0f
    private var prevLeadTms = 0L
    @Volatile private var lastLeadGrowth = 0f
    private var recDriver: OverlayVideoRecorder? = null
    private var recRoad: OverlayVideoRecorder? = null
    @Volatile private var recordVideo = false


    /** Selective detection-element switches (persisted in "dbm" prefs). */
    @Volatile var detectSigns = true; private set
    @Volatile var detectLaneMarkings = true; private set
    @Volatile var detectLaneCrossing = true; private set      // single/double line events
    @Volatile var detectHardShoulder = true; private set
    @Volatile var detectRoadObjects = true; private set
    @Volatile var detectFollowingDistance = true; private set
    /** Optional: attempt to OCR the lead vehicle's plate on a serious lead
     *  hazard. OFF by default — stored locally only, never transmitted; the
     *  user must explicitly opt in (privacy/GDPR: user-consented forensic
     *  capture, like a dashcam). */
    @Volatile var capturePlate = false; private set
    @Volatile var detectTrafficLights = true; private set
    @Volatile var detectDriverState = true; private set

    fun setElement(key: String, on: Boolean) {
        getSharedPreferences("dbm", MODE_PRIVATE).edit().putBoolean(key, on).apply()
        applyElement(key, on)
        DLog.i(TAG, "detection element $key = $on")
    }

    private fun applyElement(key: String, on: Boolean) = when (key) {
        "det_signs" -> detectSigns = on
        "det_lanes" -> detectLaneMarkings = on
        "det_lane_cross" -> detectLaneCrossing = on
        "det_shoulder" -> detectHardShoulder = on
        "det_objects" -> detectRoadObjects = on
        "det_distance" -> detectFollowingDistance = on
        "capture_plate" -> capturePlate = on
        "log_gps" -> speed.logTrace = on
        "det_lights" -> detectTrafficLights = on
        "det_driver" -> detectDriverState = on
        else -> Unit
    }

    val recognisedSigns = MutableStateFlow<List<com.rfsat.dms.RecognisedSign>>(emptyList())
    private var lastLoggedSign: String? = null
    private var lastUnrecognisedLogMs = 0L
    // Non-speed-limit signs are held on screen for up to SIGN_HOLD_MS after the
    // camera last saw them, then dropped — so a sign does not linger once it is
    // out of view. Keyed by sign name -> last-seen wall-clock ms. Speed-limit
    // signs are NOT held here; they are governed by the speed-limit fusion/cache.
    private val signLastSeen = mutableMapOf<String, Pair<com.rfsat.dms.RecognisedSign, Long>>()

    val results = mapOf(
        CameraRole.DRIVER to MutableStateFlow(AnalysisResult()),
        CameraRole.FRONT to MutableStateFlow(AnalysisResult()),
        CameraRole.REAR to MutableStateFlow(AnalysisResult()),
    )

    inner class LocalBinder : android.os.Binder() { val service get() = this@MonitorService }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        DLog.i(TAG, "onCreate: starting foreground")
        // Foreground FIRST: startForegroundService() requires startForeground()
        // within 5 s; a slow/failed analyzer init must not break that contract.
        startForegroundWithNotification()
        DLog.i(TAG, "foreground started; initialising analyzers")
        // The heavy analysers load TFLite models (and the GPU delegate), which
        // took ~6 s and BLOCKED THE MAIN THREAD in onCreate — freezing the UI on
        // startup. Load them on a background thread; submitFrame already
        // null-guards them, so frames are simply skipped until they are ready.
        lanes = LaneAnalyzer()
        evidence = EvidenceStore(this)
        alerter = Alerter(this)
        kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            val d = runCatching { DriverAnalyzer(this@MonitorService) }
                .onFailure { DLog.e(TAG, "DriverAnalyzer init FAILED", it) }
                .onSuccess { DLog.i(TAG, "DriverAnalyzer ready") }.getOrNull()
            val r = runCatching { RoadAnalyzer(this@MonitorService, CameraRole.FRONT) }
                .onFailure { DLog.e(TAG, "RoadAnalyzer init FAILED", it) }
                .onSuccess { DLog.i(TAG, "RoadAnalyzer ready") }.getOrNull()
            val s = runCatching { SignAnalyzer(this@MonitorService) }
                .onFailure { DLog.e(TAG, "SignAnalyzer init FAILED", it) }
                .onSuccess { DLog.i(TAG, "SignAnalyzer ready") }.getOrNull()
            driver = d; road = r; signs = s
            DLog.i(TAG, "analyzers ready (driver=${d!=null} road=${r!=null} signs=${s!=null})")
        }
        getSharedPreferences("dbm", MODE_PRIVATE).let { p ->
            alerter.audioEnabled = p.getBoolean("alerts_audio", true)
            alerter.ttsEnabled = p.getBoolean("alerts_tts", true)
            capturePlate = p.getBoolean("capture_plate", false)
            following.factor = p.getInt("stop_dist_pct", 100) / 100f
            com.rfsat.dms.RiskType.entries.forEach { rt ->
                if (p.contains("weight_${rt.name}"))
                    scorer.setWeight(rt, p.getInt("weight_${rt.name}", rt.scorePenalty))
            }
            setVideoRecording(p.getBoolean("record_video", false))
            listOf("det_signs", "det_lanes", "det_lane_cross", "det_shoulder",
                   "det_objects", "det_driver", "det_distance", "det_lights")
                .forEach { applyElement(it, p.getBoolean(it, true)) }
        }
        speed = SpeedMonitor(this)
        speed.logTrace = getSharedPreferences("dbm", MODE_PRIVATE)
            .getBoolean("log_gps", false)
        // Open the on-device speed-limit database (SQLite + R-tree) off the
        // main thread. Looks in the app files dir (where the downloader places
        // it) then /sdcard/Download (manual adb-push during dev). Until present,
        // fusion runs sign+cache only — safe, no crash.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            osmMap = OsmMap.open(this@MonitorService, "greece.db")
            DLog.i(TAG, "map db ready: ${osmMap != null}")
        }
        getSharedPreferences("dbm", MODE_PRIVATE).let { p ->
            lanes.horizonOffset = p.getFloat("lane_horizon", 0f)
            lanes.centerOffset = p.getFloat("lane_center", 0f)
            lanes.forwardTilt = p.getFloat("lane_forward_tilt", 0f)
            driver?.rearviewIntervalMs = p.getInt("mirror_rearview_sec", 120) * 1000L
            driver?.sideMirrorIntervalMs = p.getInt("mirror_side_sec", 120) * 1000L
            signCache.evictMisses = p.getInt("cache_evict_misses",
                com.rfsat.dms.fusion.SignLimitCache.DEFAULT_EVICT_MISSES)
        }
        yawRate = YawRateMonitor(this)
        thermal = ThermalMonitor(this)
        thermal?.start()
        DLog.i(TAG, "onCreate complete (driver=${driver != null}, road=${road != null})")

        // 1 Hz speed-compliance loop: GPS preferred, visual estimate as fallback
        scope.launch {
            while (true) {
                delay(1000)
                val t = System.currentTimeMillis()
                val (v, src) = when {
                    speed.healthy -> speed.speedKmh.value to SpeedSource.GPS
                    visualSpeedKmh != null -> visualSpeedKmh!! to SpeedSource.VISUAL
                    else -> 0 to SpeedSource.NONE
                }
                cameraManager?.vehicleMoving = v >= 5

                // --- speed-limit fusion: map + remembered signs + live camera ---
                val pos = speed.position.value
                if (pos != null) {
                    val (lat, lon) = pos
                    val heading = lastFusePos?.let { (plat, plon) ->
                        val lat0 = lat * Math.PI / 180.0
                        val dx = 6371000.0 * ((lon - plon) * Math.PI / 180.0) * kotlin.math.cos(lat0)
                        val dy = 6371000.0 * ((lat - plat) * Math.PI / 180.0)
                        if (kotlin.math.hypot(dx, dy) > 1.0)
                            ((kotlin.math.atan2(dy, dx) * 180.0 / Math.PI) % 360.0 + 360.0) % 360.0
                        else Double.NaN
                    } ?: Double.NaN
                    lastFusePos = pos

                    val mr = osmMap?.match(lat, lon, heading)
                    val mapLimit = mr?.mapLimit ?: -1
                    val segId = mr?.segId ?: -1L
                    lastMatchedSegId = segId
                    val fused = fuser.fuse(
                        t, mapLimit, pendingSignLimit, pendingSignConf, segId,
                        roadworksInView, sawSignCandidate = pendingSawSignCandidate)
                    pendingSignLimit = -1; pendingSignConf = 0.0   // consume the read
                    pendingSawSignCandidate = false
                    // Display latch: always show a current limit and keep showing
                    // it until a DIFFERENT valid restriction replaces it. The
                    // fuser already prioritises a live camera sign over the cache
                    // and the map (camera overrides map), so we simply latch its
                    // output: update only when it yields a valid limit that
                    // differs from what is currently shown. A momentary NONE (e.g.
                    // an unmapped gap with no sign) does not clear the display.
                    if (fused.limitKmh > 0 && fused.limitKmh != shownLimitKmh) {
                        shownLimitKmh = fused.limitKmh
                        scorer.onSpeedLimitSeen(fused.limitKmh)
                        DLog.i(TAG, "limit now ${fused.limitKmh} from ${fused.source}" +
                            (if (fused.disagree) " (camera disagrees with map)" else ""))
                    } else if (fused.limitKmh > 0) {
                        // same value re-confirmed; keep scorer in sync, no log spam
                        scorer.onSpeedLimitSeen(fused.limitKmh)
                    }
                }

                scorer.onSpeed(v, src, t)?.let { raw ->
                    crossChecker.gpsSpeed = if (speed.healthy) speed.speedKmh.value else null
                    crossChecker.visualSpeed = visualSpeedKmh
                    crossChecker.adjudicate(raw, CrossChecker.Ctx())?.let { ev ->
                        alerter.alert(ev); scorer.onEvent(ev, t)
                        evidence.record(CameraRole.FRONT, ev, latestRoadFrame, t)
                    }
                }
            }
        }
    }

    /** Call from the Activity once runtime permissions are granted. */
    fun onPermissionsGranted() { speed.start(); yawRate.start() }

    /** Activity links its camera manager so the service can throttle road
     *  analysis by motion. */
    var cameraManager: com.rfsat.dms.capture.PhoneCameraManager? = null

    fun setAudioAlerts(on: Boolean) { alerter.audioEnabled = on }
    fun setTtsAlerts(on: Boolean) { alerter.ttsEnabled = on }
    /** N: how many failed re-confirmations evict a cached sign (sign-removed). */
    fun setCacheEvictMisses(n: Int) {
        val v = n.coerceIn(1, 10)
        signCache.evictMisses = v
        getSharedPreferences("dbm", MODE_PRIVATE).edit().putInt("cache_evict_misses", v).apply()
    }
    fun setStoppingDistanceFactor(pct: Int) {
        getSharedPreferences("dbm", MODE_PRIVATE).edit().putInt("stop_dist_pct", pct).apply()
        following.factor = pct / 100f
    }

    /** Mount calibration for lane detection (horizon/tilt and centring),
     *  values in roughly -0.2..+0.2 of frame dimension. */
    fun setLaneCalibration(horizon: Float, center: Float, forwardTilt: Float = 0f) {
        getSharedPreferences("dbm", MODE_PRIVATE).edit()
            .putFloat("lane_horizon", horizon).putFloat("lane_center", center)
            .putFloat("lane_forward_tilt", forwardTilt).apply()
        lanes.horizonOffset = horizon
        lanes.centerOffset = center
        lanes.forwardTilt = forwardTilt
    }

    /** Seconds without a mirror glance before a warning (0 = disabled). */
    fun setMirrorIntervals(rearviewSec: Int, sideSec: Int) {
        getSharedPreferences("dbm", MODE_PRIVATE).edit()
            .putInt("mirror_rearview_sec", rearviewSec)
            .putInt("mirror_side_sec", sideSec).apply()
        driver?.rearviewIntervalMs = rearviewSec * 1000L
        driver?.sideMirrorIntervalMs = sideSec * 1000L
    }
    fun setWeight(type: com.rfsat.dms.RiskType, w: Int) {
        getSharedPreferences("dbm", MODE_PRIVATE).edit().putInt("weight_${type.name}", w).apply()
        scorer.setWeight(type, w)
    }
    fun resetCounters() {
        scorer.resetTrip()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            com.rfsat.dms.data.DmsDatabase.get(this@MonitorService).events().clearAll()
        }
        DLog.i(TAG, "all counters reset")
    }

    fun resetCalibration() {
        driver?.resetCalibration()
        following.focalFactor = FollowingDistanceMonitor.FOCAL_FACTOR
        DLog.i(TAG, "calibration reset")
    }

    fun setVideoRecording(on: Boolean) {
        getSharedPreferences("dbm", MODE_PRIVATE).edit().putBoolean("record_video", on).apply()
        recordVideo = on
        val dir = java.io.File(filesDir, "recordings")
        if (on) {
            if (recDriver == null) recDriver = OverlayVideoRecorder(dir, "driver", 640, 480)
            if (recRoad == null) recRoad = OverlayVideoRecorder(dir, "road", 640, 480)
            recDriver?.start(); recRoad?.start()
        } else { recDriver?.stop(); recRoad?.stop() }
        DLog.i(TAG, "video recording = $on")
    }

    /** Detector run state, observed by the UI controls. */
    // Analysis is OFF until the user presses Start. This avoids false warnings
    // while the vehicle is still stationary (e.g. parked, settling) before the
    // driver is actually under way.
    val analysing = MutableStateFlow(false)

    fun pauseAnalysis() { analysing.value = false; DLog.i(TAG, "analysis paused") }
    fun resumeAnalysis() {
        analysing.value = true
        driver?.resetMirrorTimers()   // start the mirror countdown from now
        governor.reset(); signApproach.reset()
        DLog.i(TAG, "analysis resumed")
    }

    private val plateRegex = Regex("[A-Z0-9]{2,4}[ -]?[A-Z0-9]{1,4}[ -]?[A-Z0-9]{1,4}")
    private val plateRecognizer by lazy {
        com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    // Plate-read state for the CURRENT lead vehicle. We read a plate at most
    // once per vehicle while it is closer than the safe distance, and only
    // re-read if the prior read was low-confidence or the lead vehicle changed.
    private var plateLeadCx = -1f          // last lead centre-x (identity proxy)
    private var plateLeadW = -1f           // last lead width (identity proxy)
    private var platePlate: String? = null // best plate read for this lead
    private var plateConf = 0f             // confidence of that read
    @Volatile private var plateReadInFlight = false

    /** Decide whether the current lead is the SAME vehicle as the last plate
     *  read, using box position/size continuity (track IDs are not propagated
     *  into Detection). A large jump in either implies a different vehicle. */
    private fun sameLeadVehicle(cx: Float, wNorm: Float): Boolean {
        if (plateLeadCx < 0f) return false
        val dCx = abs(cx - plateLeadCx)
        val dW = abs(wNorm - plateLeadW) / plateLeadW.coerceAtLeast(0.01f)
        return dCx < PLATE_SAME_CX && dW < PLATE_SAME_W
    }

    /**
     * Plate reading, separate from hazard events: called every frame a lead is
     * present. Reads only when the lead is closer than the safe distance, once
     * per vehicle, re-reading only on a low-confidence prior read or a vehicle
     * change. This keeps OCR rare (cheap) and at the closest/clearest range.
     */
    private fun maybeReadLeadPlate(
        frame: Bitmap, lead: Detection, distM: Float?, stopM: Float?,
    ) {
        if (!capturePlate || distM == null || stopM == null) return
        if (distM >= stopM) return                      // only when closer than safe
        if (plateReadInFlight) return
        val cx = (lead.left + lead.right) / 2f
        val wNorm = lead.right - lead.left
        val same = sameLeadVehicle(cx, wNorm)
        // Skip if same vehicle already read with good confidence.
        if (same && platePlate != null && plateConf >= PLATE_GOOD_CONF) {
            plateLeadCx = cx; plateLeadW = wNorm; return
        }
        if (!same) { platePlate = null; plateConf = 0f }   // new vehicle: reset
        plateLeadCx = cx; plateLeadW = wNorm

        val w = frame.width; val h = frame.height
        val l = (lead.left * w).toInt().coerceIn(0, w - 1)
        val t = (lead.top * h).toInt().coerceIn(0, h - 1)
        val r = (lead.right * w).toInt().coerceIn(l + 1, w)
        val b = (lead.bottom * h).toInt().coerceIn(t + 1, h)
        val crop = runCatching { Bitmap.createBitmap(frame, l, t, r - l, b - t) }
            .getOrNull() ?: return
        plateReadInFlight = true
        scope.launch {
            val (plate, conf) = runCatching {
                val res = plateRecognizer.process(
                    com.google.mlkit.vision.common.InputImage.fromBitmap(crop, 0)).await()
                val m = plateRegex.find(res.text.uppercase().replace("\n", " "))
                // Confidence proxy: a matched plate-like token of plausible
                // length read at close range.
                val txt = m?.value?.trim()
                val c = when {
                    txt == null -> 0f
                    txt.replace(Regex("[ -]"), "").length in 5..8 -> 0.8f
                    else -> 0.4f
                }
                txt to c
            }.getOrDefault(null to 0f)
            if (plate != null && conf > plateConf) { platePlate = plate; plateConf = conf }
            DLog.i(TAG, "lead plate read: ${plate ?: "none"} (conf $conf)")
            crop.recycle()
            plateReadInFlight = false
        }
    }

    /** Capture an evidence image of the lead vehicle during a serious hazard,
     *  annotating with the most recent plate read for this lead if available. */
    private fun captureLeadEvidence(
        frame: Bitmap, leadBox: Detection, ev: RiskEventCandidate, tMs: Long,
    ) {
        val w = frame.width; val h = frame.height
        val l = (leadBox.left * w).toInt().coerceIn(0, w - 1)
        val t = (leadBox.top * h).toInt().coerceIn(0, h - 1)
        val r = (leadBox.right * w).toInt().coerceIn(l + 1, w)
        val b = (leadBox.bottom * h).toInt().coerceIn(t + 1, h)
        val crop = runCatching { Bitmap.createBitmap(frame, l, t, r - l, b - t) }.getOrNull()
        // Annotate with the plate already read for this lead (if any), rather
        // than triggering a fresh OCR — reading is handled by maybeReadLeadPlate.
        val detail = when {
            !capturePlate -> ev.detail
            platePlate != null -> "${ev.detail} [plate ~ $platePlate]"
            else -> "${ev.detail} [plate unread]"
        }
        evidence.record(CameraRole.FRONT, ev.copy(detail = detail), crop ?: frame, tMs)
        DLog.i(TAG, "lead hazard evidence: $detail")
        crop?.recycle()
    }

    private val loggedAspect = mutableSetOf<CameraRole>()

    fun submitFrame(role: CameraRole, frame: Bitmap, tMs: Long) {
        if (!analysing.value) { frame.recycle(); return }   // paused: drop frames
        val frameAspect = if (frame.height > 0)
            frame.width.toFloat() / frame.height else 16f / 9f
        // One-time per-role diagnostic: the analysed frame's real dimensions and
        // aspect. If box overlays appear shifted horizontally (toward centre near
        // the edges), compare this aspect against the PreviewView's displayed
        // aspect — a mismatch there is the usual cause.
        if (role !in loggedAspect) {
            loggedAspect.add(role)
            DLog.i(TAG, "frame dims for $role: ${frame.width}x${frame.height} " +
                "(aspect ${"%.3f".format(frameAspect)})")
        }
        scope.launch {
            val result = runCatching {
                when (role) {
                    CameraRole.DRIVER ->
                        if (detectDriverState) driver?.analyze(frame, tMs) ?: AnalysisResult()
                        else AnalysisResult()
                    else -> roadPermit.withPermit { analyzeRoad(frame, tMs) }
                }
            }.onFailure { DLog.e(TAG, "analysis failed for $role", it) }
             .getOrDefault(AnalysisResult())
            // Tag the result with the real frame aspect ratio so the overlay
            // aligns boxes correctly regardless of the delivered resolution.
            val aspected = result.copy(frameAspect = frameAspect)
            results[role]!!.value = aspected
            if (role != CameraRole.DRIVER) {
                val nowMs = System.currentTimeMillis()
                val SPEED = com.rfsat.dms.detect.SignDetector.SPEED_LIMIT_ID
                // Refresh last-seen time for NON-speed-limit signs visible this
                // frame (these get the 3 s hold). Log each newly-seen sign once.
                result.signs.forEach { sg ->
                    if (sg.classId != SPEED) signLastSeen[sg.name] = sg to nowMs
                    if (sg.name != lastLoggedSign) {
                        lastLoggedSign = sg.name
                        DLog.i(TAG, "sign recognised: ${sg.name} (${sg.category}, " +
                            "${(sg.score * 100).toInt()}%)")
                    }
                }
                // Drop held non-speed signs past the hold window.
                val iter = signLastSeen.entries.iterator()
                while (iter.hasNext()) {
                    if (nowMs - iter.next().value.second > SIGN_HOLD_MS) iter.remove()
                }
                // Published list = speed-limit signs seen THIS frame (shown only
                // while actually visible) + still-held non-speed signs, de-duped
                // by name so the same sign cannot appear multiple times (e.g. when
                // both the full-frame and centre-crop passes report it).
                val speedNow = result.signs.filter { it.classId == SPEED }
                    .distinctBy { it.name }
                val held = signLastSeen.values.map { it.first }.distinctBy { it.name }
                val combined = (speedNow + held).distinctBy { it.name }
                if (combined != recognisedSigns.value) recognisedSigns.value = combined
            }
            if (recordVideo) {
                (if (role == CameraRole.DRIVER) recDriver else recRoad)
                    ?.encode(frame, result, tMs)
            }
            // The camera's read becomes the LIVE-sign input to the fusion (run
            // in the 1 Hz GPS loop), rather than feeding the scorer directly, so
            // it is combined with the map and remembered-sign cache.
            result.speedLimitSeen?.let {
                pendingSignLimit = it
                pendingSignConf = 0.8           // a committed OCR read is high-confidence
            }
            roadworksInView = result.signs.any { s -> s.classId == 17 }  // warn_roadworks
            // Cross-detector consensus: corroborate or suppress using
            // independent signals before an event fires.
            crossChecker.gpsSpeed = if (speed.healthy) speed.speedKmh.value else null
            crossChecker.visualSpeed = visualSpeedKmh
            crossChecker.yawRateDps = yawRate.yawRateDps
            // Illegal-turn detection: integrate yaw, fire if a prohibited turn
            // completes shortly after a relevant sign. Road frames only (signs
            // and the road context live there).
            val turnEvent = if (role != CameraRole.DRIVER)
                turns.update(yawRate.yawRateDps,
                    if (speed.healthy) speed.speedKmh.value else (visualSpeedKmh ?: 0), tMs)
            else null
            val ctx = CrossChecker.Ctx(
                leadAreaGrowthPerSec = lastLeadGrowth,
                trackAgeFrames = CrossChecker.MIN_TRACK_AGE) // road objects already tracked
            (result.events + listOfNotNull(turnEvent)).forEach { raw ->
                if (raw.type == com.rfsat.dms.RiskType.YAWNING)
                    crossChecker.recentYawnMs = tMs
                val ev = crossChecker.adjudicate(raw, ctx) ?: return@forEach
                alerter.alert(ev)
                scorer.onEvent(ev, tMs)
                evidence.record(role, ev, frame, tMs)
            }
            frame.recycle()
        }
    }

    private suspend fun analyzeRoad(frame: Bitmap, tMs: Long): AnalysisResult {
        // Retain a copy of the newest road frame for speed-violation evidence.
        latestRoadFrame?.recycle()
        latestRoadFrame = frame.copy(Bitmap.Config.ARGB_8888, false)

        // ---- feed context-gated throttling governor from cheap, always-on signals ----
        governor.speedMs = (if (speed.healthy) speed.speedKmh.value else 0) / 3.6f
        // Real thermal backoff: status listener + headroom forecast (logged).
        thermal?.pollHeadroom(tMs)
        governor.thermalMult = thermal?.multiplier ?: 1f
        // signsExpectedAhead / nearJunction come from the map: if the current
        // segment is known (we are in a mapped area), treat signs as likely. This
        // uses the segment matched in the GPS fusion loop.
        governor.signsExpectedAhead = lastMatchedSegId >= 0L
        governor.nearJunction = lastMatchedSegId >= 0L
        // Periodically log the effective intervals so the throttling (incl. thermal
        // backoff) can be verified on a drive.
        if (tMs - lastGovernorLogMs > 10_000) {
            lastGovernorLogMs = tMs
            DLog.i(TAG, "governor: ${governor.debugState()}")
        }

        // Visual speed: estimates when GNSS is down, auto-calibrates when it is up.
        visualSpeedKmh = visualSpeed.onFrame(
            frame, tMs,
            gpsSpeedKmh = if (speed.healthy) speed.speedKmh.value else null,
            gpsHealthy = speed.healthy)
        val spd = if (speed.healthy) speed.speedKmh.value else (visualSpeedKmh ?: 0)

        // Object detection (toggleable).
        val obj = if (detectRoadObjects) road?.analyze(frame, tMs) else null
        // Cheap presence gate for the expensive following-distance pipeline.
        governor.leadPresent = (obj?.detections ?: emptyList()).any {
            it.labelText in FollowingDistanceMonitor.VEHICLES &&
                (it.left + it.right) / 2f in 0.35f..0.65f
        }

        // Lane markings (toggleable), with crossing/shoulder sub-filters.
        var laneLines = emptyList<com.rfsat.dms.LaneLine>()
        var laneEvents = emptyList<RiskEventCandidate>()
        if (detectLaneMarkings) {
            val (ll, le) = lanes.analyze(frame, tMs)
            laneLines = ll
            laneEvents = le.filter { ev ->
                when (ev.type) {
                    RiskType.SOLID_LINE_CROSSING, RiskType.DOUBLE_LINE_CROSSING,
                    RiskType.LANE_DRIFT -> detectLaneCrossing
                    RiskType.HARD_SHOULDER_DRIVING -> detectHardShoulder
                    else -> true
                }
            }
        }

        // Following distance (toggleable) + focal self-calibration.
        var followEvents = emptyList<RiskEventCandidate>()
        if (detectFollowingDistance && obj != null) {
            val lead = obj.detections
                .filter { it.labelText in FollowingDistanceMonitor.VEHICLES &&
                          (it.left + it.right) / 2f in 0.35f..0.65f }
                .maxByOrNull { it.right - it.left }
            val wNorm = lead?.let { it.right - it.left } ?: 0f
            if (wNorm > 0.02f && prevLeadWNorm > 0.02f && prevLeadTms > 0) {
                val dt = (tMs - prevLeadTms) / 1000f
                following.calibrateFocal(prevLeadWNorm, wNorm, dt, spd)
                if (dt > 0.05f && prevLeadWNorm > 0f)
                    lastLeadGrowth = (wNorm * wNorm - prevLeadWNorm * prevLeadWNorm) /
                        (prevLeadWNorm * prevLeadWNorm) / dt
            }
            prevLeadWNorm = wNorm; prevLeadTms = tMs
            val (ev, distM) = following.check(obj.detections, spd, tMs)
            ev?.let { followEvents = listOf(it) }

            // Lead-vehicle hazards (sway + hard braking), gated on proximity.
            val stopM = if (spd >= FollowingDistanceMonitor.MIN_SPEED_KMH) {
                val v = spd / 3.6f
                v * FollowingDistanceMonitor.REACTION_S +
                    v * v / (2f * FollowingDistanceMonitor.DECEL_MS2)
            } else null
            val (hazEvents, hazLead) = following.checkLeadHazards(lead, distM, stopM, tMs, frame)
            // Plate reading runs whenever a lead is closer than safe distance —
            // independent of hazard events — reading once per vehicle.
            if (lead != null) maybeReadLeadPlate(frame, lead, distM, stopM)
            if (hazEvents.isNotEmpty()) {
                followEvents = followEvents + hazEvents
                // Capture an evidence image of the lead vehicle making the risky
                // manoeuvre, and (only if the user has enabled it) attempt to
                // read its plate. Stored locally only; never transmitted.
                hazLead?.let { leadBox ->
                    captureLeadEvidence(frame, leadBox, hazEvents.first(), tMs)
                }
            }
        }

        // Traffic lights (toggleable).
        var lightDet: com.rfsat.dms.Detection? = null
        var lightEvents = emptyList<RiskEventCandidate>()
        if (detectTrafficLights) {
            val vboxes = (obj?.detections ?: emptyList())
                .filter { it.labelText in setOf("car","truck","bus","motorcycle") }
            val (d, ev) = lights.analyze(frame, spd, tMs, vboxes)
            lightDet = d
            ev?.let { lightEvents = listOf(it) }
        }

        // Signs (toggleable): two-stage classifier on candidate boxes + OCR.
        var limit: Int? = null
        var signDets = emptyList<com.rfsat.dms.Detection>()
        var recognisedSigns = emptyList<com.rfsat.dms.RecognisedSign>()
        // Sign detection (incl. the EU YOLO detector) is throttled to limit
        // compute/heat. Raised from every-3rd to every-2nd road frame: the
        // 18-June-2 drive showed speed-limit signs are legible for only ~1-2
        // frames as they pass, and the coarser sampling was missing that
        // window (42 of 51 sign sightings were "too small" by the time a frame
        // was sampled). Every-2nd is a modest cost rise for materially more
        // chances to catch the readable moment.
        if (detectSigns && governor.shouldRun(ProcessingGovernor.Role.SIGN)) {
            // Upstream sign hints (YOLO "stop sign"); SignAnalyzer adds its own
            // colour/shape region proposals so all sign types are covered.
            val cand = (obj?.detections ?: emptyList())
                .filter { it.labelText == "stop sign" }
            val out = signs?.analyze(frame, cand,
                speedMs = (if (speed.healthy) speed.speedKmh.value else 0) / 3.6f,
                approach = signApproach)
            if (out != null) {
                signDets = out.detections; limit = out.speedLimitSeen
                recognisedSigns = out.signs
                // If a speed-limit sign was detected at all this step, that is a
                // genuine re-read opportunity — record it so the cache can count a
                // miss when the read does not confirm the remembered value.
                if (out.signs.any { it.classId == com.rfsat.dms.detect.SignDetector.SPEED_LIMIT_ID })
                    pendingSawSignCandidate = true
                // Drive turn-restriction logic only from EU-detector signs, whose
                // class IDs match the SignDetector constants TurnMonitor uses.
                if (out.fromEuDetector) out.signs.forEach { turns.onSign(it.classId, tMs) }
                // Diagnostic logging of sign-shaped regions GTSRB could not
                // recognise (e.g. EU no-turn signs), rate-limited.
                if (out.unrecognised.isNotEmpty() &&
                    tMs - lastUnrecognisedLogMs > 4000) {
                    lastUnrecognisedLogMs = tMs
                    out.unrecognised.take(2).forEach {
                        DLog.i(TAG, "unrecognised sign region: $it")
                    }
                }
            }
        }

        return AnalysisResult(
            detections = (obj?.detections ?: emptyList()) + signDets +
                listOfNotNull(lightDet),
            laneLines = laneLines,
            events = (obj?.events ?: emptyList()) + laneEvents + followEvents + lightEvents,
            speedLimitSeen = limit,
            signs = recognisedSigns,
            roiTopFrac = lanes.lastRoiFrac,
            laneForwardTilt = lanes.forwardTilt,
        )
    }

    private fun startForegroundWithNotification() {
        val chId = "dms_monitor"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(chId, "DBM monitoring", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, chId)
            .setContentTitle("DBM monitoring active")
            .setContentText("Driver and road monitoring in progress")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
        when {
            Build.VERSION.SDK_INT >= 30 -> startForeground(1, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            Build.VERSION.SDK_INT >= 29 -> startForeground(1, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else -> startForeground(1, n)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        speed.stop(); yawRate.stop(); thermal?.stop()
        recDriver?.stop(); recRoad?.stop()
        driver?.close(); road?.close(); signs?.close(); lights.close(); alerter.release()
        osmMap?.close()
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        DLog.i(TAG, "onDestroy")
        super.onDestroy()
    }
}
