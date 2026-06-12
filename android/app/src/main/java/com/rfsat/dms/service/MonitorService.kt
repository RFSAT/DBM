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
import com.rfsat.dms.detect.ComplianceScorer
import com.rfsat.dms.detect.DriverAnalyzer
import com.rfsat.dms.detect.LaneAnalyzer
import com.rfsat.dms.detect.RoadAnalyzer
import com.rfsat.dms.detect.SignAnalyzer
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
    private val visualSpeed = VisualSpeedEstimator()
    @Volatile private var visualSpeedKmh: Int? = null
    /** Copy of the most recent road frame, attached to speed violations. */
    @Volatile private var latestRoadFrame: Bitmap? = null
    private lateinit var evidence: EvidenceStore
    private lateinit var alerter: Alerter
    lateinit var speed: SpeedMonitor; private set
    val scorer = ComplianceScorer()

    private var roadFrameCount = 0

    /** Selective detection-element switches (persisted in "dbm" prefs). */
    @Volatile var detectSigns = true; private set
    @Volatile var detectLaneMarkings = true; private set
    @Volatile var detectLaneCrossing = true; private set      // single/double line events
    @Volatile var detectHardShoulder = true; private set
    @Volatile var detectRoadObjects = true; private set
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
        "det_driver" -> detectDriverState = on
        else -> Unit
    }

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
        signs = SignAnalyzer()
        evidence = EvidenceStore(this)
        alerter = Alerter(this)
        getSharedPreferences("dbm", MODE_PRIVATE).let { p ->
            alerter.audioEnabled = p.getBoolean("alerts_audio", true)
            alerter.ttsEnabled = p.getBoolean("alerts_tts", true)
            listOf("det_signs", "det_lanes", "det_lane_cross", "det_shoulder",
                   "det_objects", "det_driver")
                .forEach { applyElement(it, p.getBoolean(it, true)) }
        }
        speed = SpeedMonitor(this)
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
                scorer.onSpeed(v, src, t)?.let { ev ->
                    alerter.alert(ev); scorer.onEvent(ev, t)
                    evidence.record(CameraRole.FRONT, ev, latestRoadFrame, t)
                }
            }
        }
    }

    /** Call from the Activity once runtime permissions are granted. */
    fun onPermissionsGranted() = speed.start()

    fun setAudioAlerts(on: Boolean) { alerter.audioEnabled = on }
    fun setTtsAlerts(on: Boolean) { alerter.ttsEnabled = on }

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
            result.speedLimitSeen?.let { scorer.onSpeedLimitSeen(it) }
            result.events.forEach { ev ->
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
        val road = road ?: return run {
            val (laneLines, laneEvents) = lanes.analyze(frame, tMs)
            AnalysisResult(laneLines = laneLines, events = laneEvents)
        }
        // Visual speed: estimates when GNSS is down, auto-calibrates when it is up.
        visualSpeedKmh = visualSpeed.onFrame(
            frame, tMs,
            gpsSpeedKmh = if (speed.healthy) speed.speedKmh.value else null,
            gpsHealthy = speed.healthy)
        val obj = road.analyze(frame, tMs)
        val (laneLines, laneEvents) = lanes.analyze(frame, tMs)
        var limit: Int? = null
        var signDets = emptyList<com.rfsat.dms.Detection>()
        if (roadFrameCount++ % 3 == 0) {
            val (d, l) = signs.analyze(frame)
            signDets = d; limit = l
        }
        return AnalysisResult(
            detections = obj.detections + signDets,
            laneLines = laneLines,
            events = obj.events + laneEvents,
            speedLimitSeen = limit,
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
        speed.stop()
        driver?.close(); road?.close(); alerter.release()
        DLog.i(TAG, "onDestroy")
        super.onDestroy()
    }
}
