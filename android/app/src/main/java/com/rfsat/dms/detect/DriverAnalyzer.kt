package com.rfsat.dms.detect

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.rfsat.dms.AnalysisResult
import com.rfsat.dms.Detection
import com.rfsat.dms.RiskEventCandidate
import com.rfsat.dms.RiskType
import com.rfsat.dms.Severity
import kotlin.math.abs
import kotlin.math.hypot

/** Format a millisecond duration for display: seconds with one decimal. */
internal fun fmtSecs(ms: Long): String = "%.1f s".format(ms / 1000f)

/**
 * Driver-facing camera analysis.
 *
 * Per frame: MediaPipe Face Landmarker (478 pts) -> eye aspect ratio (EAR),
 * PERCLOS over a sliding window, and head yaw/pitch from landmark geometry.
 * Temporal state machines convert per-frame measures into risk events:
 *  - eyes closed continuously > EYES_CLOSED_CRITICAL_MS  -> MICROSLEEP
 *  - PERCLOS > 0.4 over 60 s                              -> MICROSLEEP (drowsy)
 *  - |yaw| > 30 deg for > 2 s                             -> EYES_OFF_ROAD
 *  - no yaw excursion toward mirrors for > 120 s          -> NO_MIRROR_CHECK
 *
 * Phone-use / hands-on-wheel / seatbelt are classifier hooks (see
 * CabinClassifier stub) — they require a small fine-tuned TFLite model
 * trained on e.g. the StateFarm / DMD datasets.
 *
 * Requires model asset: app/src/main/assets/face_landmarker.task
 * https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task
 */
class DriverAnalyzer(context: Context) {

    private val landmarker: FaceLandmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()
            )
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .build()
    )

    // --- self-calibration (parameter-level adaptation against the driver) ---
    private val earOpenSamples = ArrayDeque<Float>()
    private var earClosedThresh = EAR_CLOSED
    private val yawNeutralSamples = ArrayDeque<Float>()
    private var yawNeutral = 0f
    var calibrated = false; private set

    // --- temporal state ---
    private var eyesClosedSinceMs = 0L
    private var yawOffSinceMs = 0L
    private var lastMirrorCheckMs = System.currentTimeMillis()
    private val perclosWindow = ArrayDeque<Pair<Long, Boolean>>() // (t, closed)

    fun analyze(frame: Bitmap, tMs: Long): AnalysisResult {
        val res = landmarker.detectForVideo(BitmapImageBuilder(frame).build(), tMs)
        if (res.faceLandmarks().isEmpty()) {
            resetTransient()
            return AnalysisResult()
        }
        val lm = res.faceLandmarks()[0]
        fun p(i: Int) = lm[i]

        // Eye aspect ratio from MediaPipe FaceMesh indices.
        fun ear(top1: Int, top2: Int, bot1: Int, bot2: Int, left: Int, right: Int): Float {
            val v1 = hypot((p(top1).x() - p(bot1).x()), (p(top1).y() - p(bot1).y()))
            val v2 = hypot((p(top2).x() - p(bot2).x()), (p(top2).y() - p(bot2).y()))
            val h = hypot((p(left).x() - p(right).x()), (p(left).y() - p(right).y()))
            return ((v1 + v2) / (2f * h))
        }
        val earL = ear(159, 158, 145, 153, 33, 133)
        val earR = ear(386, 385, 374, 380, 362, 263)
        val earAvg = (earL + earR) / 2f
        if (earAvg > EAR_CLOSED) {
            earOpenSamples.addLast(earAvg)
            if (earOpenSamples.size > 300) earOpenSamples.removeFirst()
            if (earOpenSamples.size >= 60) {
                val sorted = earOpenSamples.sorted()
                earClosedThresh = (sorted[sorted.size / 2] * 0.55f).coerceIn(0.12f, 0.22f)
                calibrated = true
            }
        }
        val closed = earAvg < earClosedThresh

        // Coarse head yaw proxy: nose tip (1) offset between cheek points (234, 454).
        val noseX = p(1).x()
        val faceL = p(234).x(); val faceR = p(454).x()
        val yawNorm = ((noseX - faceL) / (faceR - faceL) - 0.5f) * 2f  // -1..1
        val yawRaw = yawNorm * 60f
        if (!closed) {
            yawNeutralSamples.addLast(yawRaw)
            if (yawNeutralSamples.size > 300) yawNeutralSamples.removeFirst()
            if (yawNeutralSamples.size >= 60) {
                val sorted = yawNeutralSamples.sorted()
                yawNeutral = sorted[sorted.size / 2]
            }
        }
        val yawDeg = yawRaw - yawNeutral

        val events = mutableListOf<RiskEventCandidate>()

        // -- microsleep: continuous closure --
        if (closed) {
            if (eyesClosedSinceMs == 0L) eyesClosedSinceMs = tMs
            val dur = tMs - eyesClosedSinceMs
            if (dur > EYES_CLOSED_CRITICAL_MS) events += RiskEventCandidate(
                RiskType.MICROSLEEP, Severity.CRITICAL, 0.95f, "eyes closed ${fmtSecs(dur)}")
            else if (dur > EYES_CLOSED_WARN_MS) events += RiskEventCandidate(
                RiskType.MICROSLEEP, Severity.WARNING, 0.8f, "eyes closed ${fmtSecs(dur)}")
        } else eyesClosedSinceMs = 0L

        // -- PERCLOS over 60 s --
        perclosWindow.addLast(tMs to closed)
        while (perclosWindow.isNotEmpty() && tMs - perclosWindow.first().first > 60_000) {
            perclosWindow.removeFirst()
        }
        if (perclosWindow.size > 50) {
            val perclos = perclosWindow.count { it.second }.toFloat() / perclosWindow.size
            if (perclos > 0.4f) events += RiskEventCandidate(
                RiskType.MICROSLEEP, Severity.WARNING, perclos, "PERCLOS=%.2f".format(perclos))
        }

        // -- gaze off road --
        if (abs(yawDeg) > YAW_OFF_ROAD_DEG && !closed) {
            if (yawOffSinceMs == 0L) yawOffSinceMs = tMs
            if (tMs - yawOffSinceMs > 2000) events += RiskEventCandidate(
                RiskType.EYES_OFF_ROAD, Severity.WARNING, 0.7f, "yaw %.0f deg".format(yawDeg))
        } else yawOffSinceMs = 0L

        // -- mirror checks: brief yaw excursions count as checks --
        if (abs(yawDeg) in YAW_MIRROR_MIN_DEG..YAW_MIRROR_MAX_DEG) lastMirrorCheckMs = tMs
        if (tMs - lastMirrorCheckMs > MIRROR_INTERVAL_MS) {
            events += RiskEventCandidate(
                RiskType.NO_MIRROR_CHECK, Severity.INFO, 0.6f,
                "no mirror glance for ${fmtSecs(tMs - lastMirrorCheckMs)}")
            lastMirrorCheckMs = tMs // re-arm
        }

        // Face box overlay
        val xs = lm.map { it.x() }; val ys = lm.map { it.y() }
        val det = Detection(
            if (closed) "EYES CLOSED" else "yaw %.0f°".format(yawDeg),
            1f, xs.min(), ys.min(), xs.max(), ys.max(),
            risky = events.any { it.severity != Severity.INFO })

        return AnalysisResult(detections = listOf(det), events = events)
    }

    private fun resetTransient() { eyesClosedSinceMs = 0L; yawOffSinceMs = 0L }

    fun resetCalibration() {
        earOpenSamples.clear(); yawNeutralSamples.clear()
        earClosedThresh = EAR_CLOSED; yawNeutral = 0f; calibrated = false
    }

    fun close() = landmarker.close()

    companion object {
        const val EAR_CLOSED = 0.18f
        const val EYES_CLOSED_WARN_MS = 800L
        const val EYES_CLOSED_CRITICAL_MS = 1500L
        const val YAW_OFF_ROAD_DEG = 30f
        const val YAW_MIRROR_MIN_DEG = 15f
        const val YAW_MIRROR_MAX_DEG = 45f
        const val MIRROR_INTERVAL_MS = 120_000L
    }
}
