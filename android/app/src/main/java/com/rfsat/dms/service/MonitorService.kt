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

    companion object { private const val TAG = "MonitorService" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val roadPermit = Semaphore(1)

    private var driver: DriverAnalyzer? = null
    private var road: RoadAnalyzer? = null
    private lateinit var lanes: LaneAnalyzer
    private lateinit var signs: SignAnalyzer
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

    private var roadFrameCount = 0

    /** Selective detection-element switches (persisted in "dbm" prefs). */
    @Volatile var detectSigns = true; private set
    @Volatile var detectLaneMarkings = true; private set
    @Volatile var detectLaneCrossing = true; private set      // single/double line events
    @Volatile var detectHardShoulder = true; private set
    @Volatile var detectRoadObjects = true; private set
    @Volatile var detectFollowingDistance = true; private set
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
        "det_lights" -> detectTrafficLights = on
        "det_driver" -> detectDriverState = on
        else -> Unit
    }

    val recognisedSigns = MutableStateFlow<List<com.rfsat.dms.RecognisedSign>>(emptyList())
    private var lastLoggedSign: String? = null
    private var lastUnrecognisedLogMs = 0L

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
        driver = runCatching { DriverAnalyzer(this) }
            .onFailure { DLog.e(TAG, "DriverAnalyzer init FAILED (face_landmarker.task in assets?)", it) }
            .onSuccess { DLog.i(TAG, "DriverAnalyzer ready") }
            .getOrNull()
        road = runCatching { RoadAnalyzer(this, CameraRole.FRONT) }
            .onFailure { DLog.e(TAG, "RoadAnalyzer init FAILED (efficientdet_lite0.tflite in assets?)", it) }
            .onSuccess { DLog.i(TAG, "RoadAnalyzer ready") }
            .getOrNull()
        lanes = LaneAnalyzer()
        signs = SignAnalyzer(this)
        evidence = EvidenceStore(this)
        alerter = Alerter(this)
        getSharedPreferences("dbm", MODE_PRIVATE).let { p ->
            alerter.audioEnabled = p.getBoolean("alerts_audio", true)
            alerter.ttsEnabled = p.getBoolean("alerts_tts", true)
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
        yawRate = YawRateMonitor(this)
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
    fun setStoppingDistanceFactor(pct: Int) {
        getSharedPreferences("dbm", MODE_PRIVATE).edit().putInt("stop_dist_pct", pct).apply()
        following.factor = pct / 100f
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

    fun submitFrame(role: CameraRole, frame: Bitmap, tMs: Long) {
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
            results[role]!!.value = result
            if (role != CameraRole.DRIVER && result.signs.isNotEmpty()) {
                recognisedSigns.value = result.signs
                // Log each newly-seen sign once (dedup within a short window) so
                // recognised signs are traceable in the diagnostic log.
                result.signs.forEach { sg ->
                    turns.onSign(sg.classId, tMs)
                    if (sg.name != lastLoggedSign) {
                        lastLoggedSign = sg.name
                        DLog.i(TAG, "sign recognised: ${sg.name} (${sg.category}, " +
                            "${(sg.score * 100).toInt()}%)")
                    }
                }
            }
            if (recordVideo) {
                (if (role == CameraRole.DRIVER) recDriver else recRoad)
                    ?.encode(frame, result, tMs)
            }
            result.speedLimitSeen?.let { scorer.onSpeedLimitSeen(it) }
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

        // Visual speed: estimates when GNSS is down, auto-calibrates when it is up.
        visualSpeedKmh = visualSpeed.onFrame(
            frame, tMs,
            gpsSpeedKmh = if (speed.healthy) speed.speedKmh.value else null,
            gpsHealthy = speed.healthy)
        val spd = if (speed.healthy) speed.speedKmh.value else (visualSpeedKmh ?: 0)

        // Object detection (toggleable).
        val obj = if (detectRoadObjects) road?.analyze(frame, tMs) else null

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
            val (ev, _) = following.check(obj.detections, spd, tMs)
            ev?.let { followEvents = listOf(it) }
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
        if (detectSigns && roadFrameCount++ % 3 == 0) {
            // Upstream sign hints (YOLO "stop sign"); SignAnalyzer adds its own
            // colour/shape region proposals so all sign types are covered.
            val cand = (obj?.detections ?: emptyList())
                .filter { it.labelText == "stop sign" }
            val out = signs.analyze(frame, cand)
            signDets = out.detections; limit = out.speedLimitSeen
            recognisedSigns = out.signs
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

        return AnalysisResult(
            detections = (obj?.detections ?: emptyList()) + signDets +
                listOfNotNull(lightDet),
            laneLines = laneLines,
            events = (obj?.events ?: emptyList()) + laneEvents + followEvents + lightEvents,
            speedLimitSeen = limit,
            signs = recognisedSigns,
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
        speed.stop(); yawRate.stop()
        recDriver?.stop(); recRoad?.stop()
        driver?.close(); road?.close(); signs.close(); lights.close(); alerter.release()
        DLog.i(TAG, "onDestroy")
        super.onDestroy()
    }
}
