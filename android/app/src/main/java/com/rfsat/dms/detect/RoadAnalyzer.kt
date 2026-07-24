package com.rfsat.dms.detect

import android.content.Context
import android.graphics.Bitmap
import com.rfsat.dms.AnalysisResult
import com.rfsat.dms.CameraRole
import com.rfsat.dms.Detection
import com.rfsat.dms.RiskEventCandidate
import com.rfsat.dms.RiskType
import com.rfsat.dms.Severity

/**
 * Front/rear camera analysis: COCO object detection (YOLO26-nano)
 * + lightweight IoU tracker + monocular time-to-collision proxy.
 *
 * Risk logic:
 *  - relative box-area growth rate r = (A_t - A_t0)/A_t0 per second; objects
 *    with r > GROWTH_CRITICAL while horizontally centred -> collision risk.
 *  - vulnerable road users (person/bicycle/motorcycle) inside the central
 *    risk zone of the FRONT view -> VULNERABLE_ROAD_USER.
 *
 * Requires model asset: app/src/main/assets/yolo26n.tflite (committed).
 * (e-scooters: fine-tune later; they typically classify as person+bicycle)
 */
class RoadAnalyzer(context: Context, private val role: CameraRole) {

    // Object detector: YOLO26-nano (yolo26n.tflite), raw [1,84,8400] output
    // decoded by YoloDetector. The old EfficientDet-Lite0 fallback (TFLite Task
    // ObjectDetector) was removed in v1.20.15: yolo26n.tflite is committed so the
    // fallback was unreachable, and its library (tensorflow-lite-task-vision
    // 0.4.4, the last release of an abandoned line) ships a native .so that is
    // NOT 16 KB page-aligned and cannot be fixed — it blocked Play compliance.
    private val yolo: YoloDetector? =
        runCatching { YoloDetector(context) }.getOrNull()

    private val tracker = ByteTrackTracker()

    fun analyze(frame: Bitmap, tMs: Long): AnalysisResult {
        // If the detector failed to initialise, degrade to "no detections"
        // rather than crashing; the rest of the pipeline handles an empty list.
        val raw = yolo?.detect(frame)?.filter { it.labelText in RELEVANT }
            ?: emptyList()

        val tracks = tracker.update(raw, tMs)
        val events = mutableListOf<RiskEventCandidate>()
        val out = tracks.map { tr ->
            val growth = tr.areaGrowthPerSec()
            val centred = (tr.last.left + tr.last.right) / 2f in 0.3f..0.7f
            val low = tr.last.bottom > 0.5f   // lower half = close
            val confirmed = tr.ageFrames >= 3   // persistence: reject one-frame boxes
            var risky = false

            if (confirmed && growth > GROWTH_CRITICAL && centred && low) {
                risky = true
                events += RiskEventCandidate(
                    if (role == CameraRole.REAR) RiskType.REAR_COLLISION_RISK
                    else RiskType.FRONT_COLLISION_RISK,
                    Severity.CRITICAL, tr.last.score,
                    "${tr.last.labelText} approaching, growth %.1f/s".format(growth))
            } else if (confirmed && growth > GROWTH_WARN && centred) {
                risky = true
                events += RiskEventCandidate(
                    if (role == CameraRole.REAR) RiskType.REAR_COLLISION_RISK
                    else RiskType.FRONT_COLLISION_RISK,
                    Severity.WARNING, tr.last.score,
                    "${tr.last.labelText} closing, growth %.1f/s".format(growth))
            }

            if (confirmed && role == CameraRole.FRONT && tr.last.labelText in VULNERABLE &&
                centred && tr.last.bottom > 0.6f) {
                risky = true
                events += RiskEventCandidate(
                    RiskType.VULNERABLE_ROAD_USER, Severity.WARNING,
                    tr.last.score, tr.last.labelText)
            }
            tr.last.copy(risky = risky)
        }
        return AnalysisResult(detections = out, events = events)
    }

    fun close() { yolo?.close() }

    companion object {
        val RELEVANT = setOf("car", "truck", "bus", "motorcycle", "bicycle", "person")
        val VULNERABLE = setOf("person", "bicycle", "motorcycle")
        const val GROWTH_WARN = 0.4f       // box area +40 %/s
        const val GROWTH_CRITICAL = 0.9f
    }
}
