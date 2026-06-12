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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val roadPermit = Semaphore(1)

    private lateinit var driver: DriverAnalyzer
    private lateinit var road: RoadAnalyzer
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
        driver = DriverAnalyzer(this)
        road = RoadAnalyzer(this, CameraRole.FRONT)
        lanes = LaneAnalyzer()
        signs = SignAnalyzer()
        evidence = EvidenceStore(this)
        alerter = Alerter(this)
        speed = SpeedMonitor(this)
        startForegroundWithNotification()

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

    fun submitFrame(role: CameraRole, frame: Bitmap, tMs: Long) {
        scope.launch {
            val result = when (role) {
                CameraRole.DRIVER -> driver.analyze(frame, tMs)
                else -> roadPermit.withPermit { analyzeRoad(frame, tMs) }
            }
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
        driver.close(); road.close(); alerter.release()
        super.onDestroy()
    }
}
