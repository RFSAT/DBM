package com.rfsat.dms.detect

import android.content.Context
import android.graphics.Bitmap
import com.rfsat.dms.AnalysisResult
import com.rfsat.dms.CameraRole
import com.rfsat.dms.Detection
import com.rfsat.dms.RiskEventCandidate
import com.rfsat.dms.RiskType
import com.rfsat.dms.Severity
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector

/**
 * Front/rear camera analysis: COCO object detection (EfficientDet-Lite0)
 * + lightweight IoU tracker + monocular time-to-collision proxy.
 *
 * Risk logic:
 *  - relative box-area growth rate r = (A_t - A_t0)/A_t0 per second; objects
 *    with r > GROWTH_CRITICAL while horizontally centred -> collision risk.
 *  - vulnerable road users (person/bicycle/motorcycle) inside the central
 *    risk zone of the FRONT view -> VULNERABLE_ROAD_USER.
 *
 * Requires model asset: app/src/main/assets/efficientdet_lite0.tflite
 * https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float16/latest/efficientdet_lite0.tflite
 * (e-scooters: fine-tune later; they typically classify as person+bicycle)
 */
class RoadAnalyzer(context: Context, private val role: CameraRole) {

    // Object detector — two supported paths:
    //  * YOLO26-nano (yolo26n.tflite): raw [1,84,8400] output, decoded by
    //    YoloDetector (the TFLite Task API cannot parse this format).
    //  * EfficientDet-Lite0 (fallback): standard Task ObjectDetector.
    // The YOLO path is used whenever the asset is present.
    private val hasYolo =
        runCatching { context.assets.open("yolo26n.tflite").close() }.isSuccess
    private val yolo: YoloDetector? = if (hasYolo)
        runCatching { YoloDetector(context) }.getOrNull() else null
    private val detector: ObjectDetector? = if (yolo == null) {
        fun build(useNnapi: Boolean) = ObjectDetector.createFromFileAndOptions(
            context, "efficientdet_lite0.tflite",
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder().setNumThreads(2)
                        .apply { if (useNnapi) useNnapi() }.build())
                .setScoreThreshold(0.40f)
                .setMaxResults(10)
                .build())
        runCatching { build(true) }.getOrElse { build(false) }
    } else null

    private val tracker = ByteTrackTracker()

    fun analyze(frame: Bitmap, tMs: Long): AnalysisResult {
        val w = frame.width.toFloat(); val h = frame.height.toFloat()
        val raw = if (yolo != null) {
            yolo.detect(frame).filter { it.labelText in RELEVANT }
        } else {
            detector!!.detect(TensorImage.fromBitmap(frame))
                .mapNotNull { d ->
                    val c = d.categories.firstOrNull() ?: return@mapNotNull null
                    if (c.label !in RELEVANT) return@mapNotNull null
                    Detection(c.label, c.score,
                        d.boundingBox.left / w, d.boundingBox.top / h,
                        d.boundingBox.right / w, d.boundingBox.bottom / h)
                }
        }

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

    fun close() { detector?.close(); yolo?.close() }

    companion object {
        val RELEVANT = setOf("car", "truck", "bus", "motorcycle", "bicycle", "person")
        val VULNERABLE = setOf("person", "bicycle", "motorcycle")
        const val GROWTH_WARN = 0.4f       // box area +40 %/s
        const val GROWTH_CRITICAL = 0.9f
    }
}
