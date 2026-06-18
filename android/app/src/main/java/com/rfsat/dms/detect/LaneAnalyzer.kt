package com.rfsat.dms.detect

import android.graphics.Bitmap
import com.rfsat.dms.LaneLine
import com.rfsat.dms.RiskEventCandidate
import com.rfsat.dms.RiskType
import com.rfsat.dms.Severity
import kotlin.math.abs

/**
 * Lane-marking detection and compliance — pure-Kotlin baseline (no OpenCV
 * dependency, keeps APK small). Pipeline per road frame:
 *
 *  1. ROI = lower 45 % of the frame (road surface).
 *  2. Luma conversion + horizontal-gradient threshold -> edge points of
 *     bright paint stripes on dark tarmac.
 *  3. Edge points split left/right of frame centre; least-squares line fit
 *     per side in (y -> x) form.
 *  4. Line TYPE classification:
 *       - coverage ratio along the fitted line: > 0.7 solid, else dashed;
 *       - a second parallel fit 0.5–2 % of frame width away -> double solid.
 *  5. Compliance state machines:
 *       - LANE_DRIFT / *_LINE_CROSSING: a fitted line's bottom intercept
 *         migrating into the vehicle corridor (|x-0.5| < CORRIDOR) and
 *         persisting -> crossing of that line type.
 *       - HARD_SHOULDER_DRIVING: vehicle corridor positioned right of a
 *         solid line that has the road edge beyond it (heuristic: solid
 *         line on the LEFT of corridor + no line on the right).
 *       - ILLEGAL_TURN hook: fed by a yaw-rate estimate (phone gyroscope)
 *         combined with double-line crossing — see TurnMonitor TODO.
 *
 * This is deliberately a tunable classical baseline; swapping in a TFLite
 * lane-segmentation model (e.g. UFLD) later only needs to emit the same
 * LaneLine list.
 */
class LaneAnalyzer {

    /** Mount calibration. The lane logic assumes the camera looks straight
     *  ahead and level. A tilted or offset phone mount shifts where the road
     *  sits in the frame, degrading lane fitting. These offsets let the user
     *  correct for the mount:
     *   - horizonOffset: shifts the road region-of-interest up/down. Positive
     *     moves the ROI top DOWN (camera tilted up / horizon higher in frame);
     *     negative moves it UP. Range about -0.2..+0.2 of frame height.
     *   - centerOffset: shifts the expected road centre left/right from 0.5,
     *     for a mount that is not centred. Range about -0.2..+0.2. */
    @Volatile var horizonOffset = 0f
    @Volatile var centerOffset = 0f

    private var leftCrossSinceMs = 0L
    private var rightCrossSinceMs = 0L
    private var shoulderSinceMs = 0L

    fun analyze(frame: Bitmap, tMs: Long): Pair<List<LaneLine>, List<RiskEventCandidate>> {
        val w = frame.width; val h = frame.height
        // ROI top with mount calibration: base lower 45%, shifted by the
        // horizon offset, clamped to a sane band.
        val roiFrac = (0.55f + horizonOffset).coerceIn(0.35f, 0.75f)
        val roiTop = (h * roiFrac).toInt()
        val px = IntArray(w * (h - roiTop))
        frame.getPixels(px, 0, w, 0, roiTop, w, h - roiTop)

        // --- edge points from horizontal luma gradient ---
        data class Pt(val x: Int, val y: Int)
        val leftPts = ArrayList<Pt>(512); val rightPts = ArrayList<Pt>(512)
        val rows = h - roiTop
        var y = 0
        while (y < rows) {
            var x = 2
            while (x < w - 2) {
                val l = luma(px[y * w + x - 2]); val r = luma(px[y * w + x + 2])
                if (abs(l - r) > GRAD_THRESH && maxOf(l, r) > BRIGHT_MIN) {
                    if (x < w / 2) leftPts.add(Pt(x, y)) else rightPts.add(Pt(x, y))
                }
                x += 3
            }
            y += 2
        }

        val lanes = mutableListOf<LaneLine>()
        val events = mutableListOf<RiskEventCandidate>()

        fun fitSide(pts: List<Pt>): Triple<Float, Float, Float>? { // a, b of x=a*y+b, coverage
            if (pts.size < MIN_PTS) return null
            val n = pts.size.toFloat()
            val sy = pts.sumOf { it.y.toDouble() }; val sx = pts.sumOf { it.x.toDouble() }
            val syy = pts.sumOf { it.y.toDouble() * it.y }; val sxy = pts.sumOf { it.x.toDouble() * it.y }
            val denom = n * syy - sy * sy
            if (abs(denom) < 1e-3) return null
            val a = ((n * sxy - sx * sy) / denom).toFloat()
            val b = ((sx - a * sy) / n).toFloat()
            // coverage: fraction of ROI rows having a point near the fit
            val rowsHit = pts.filter { abs(it.x - (a * it.y + b)) < w * 0.02f }
                .map { it.y / 8 }.toSet().size
            val coverage = rowsHit / (rows / 8f)
            return Triple(a, b, coverage.coerceIn(0f, 1f))
        }

        fun classify(pts: List<Pt>, fit: Triple<Float, Float, Float>): LaneLine.Kind {
            val (a, b, cov) = fit
            if (cov <= SOLID_COVERAGE) return LaneLine.Kind.DASHED
            // double: significant population of points offset ~1-2 % width from main fit
            val off = pts.count {
                val d = it.x - (a * it.y + b)
                abs(d) > w * 0.012f && abs(d) < w * 0.035f
            }
            return if (off > pts.size * 0.25f) LaneLine.Kind.DOUBLE_SOLID
                   else LaneLine.Kind.SOLID
        }

        fun emit(pts: List<Pt>, isLeft: Boolean): LaneLine? {
            val fit = fitSide(pts) ?: return null
            val (a, b, cov) = fit
            if (cov < MIN_COVERAGE) return null   // too few rows -> unreliable fit
            val xb = (a * rows + b) / w           // bottom intercept, normalized
            val xt = b / w                         // top of ROI intercept
            // Perspective sanity: real lane lines converge toward a vanishing
            // point, so the TOP of a line sits closer to the frame centre than
            // its BOTTOM. A fit where the top is further from centre than the
            // bottom (lines "opening outward" with distance) is physically wrong
            // and is rejected rather than drawn.
            val centre = 0.5f + centerOffset
            val bottomDist = abs(xb - centre)
            val topDist = abs(xt - centre)
            if (topDist > bottomDist + 0.04f) return null      // diverging -> reject
            // The bottom of the line should also be on the expected side.
            if (isLeft && xb > 0.62f) return null
            if (!isLeft && xb < 0.38f) return null
            return LaneLine(xb.coerceIn(0f, 1f), xt.coerceIn(0f, 1f), classify(pts, fit))
        }

        val left = emit(leftPts, true);  left?.let { lanes += it }
        val right = emit(rightPts, false); right?.let { lanes += it }

        // --- crossing logic: a line's bottom intercept inside vehicle corridor ---
        fun crossing(line: LaneLine?, sinceSet: (Long) -> Unit, since: Long): Boolean {
            if (line == null || abs(line.xBottom - (0.5f + centerOffset)) > CORRIDOR) { sinceSet(0); return false }
            if (since == 0L) { sinceSet(tMs); return false }
            return tMs - since > CROSS_PERSIST_MS
        }
        if (crossing(left, { leftCrossSinceMs = it }, leftCrossSinceMs))
            events += crossingEvent(left!!)
        if (crossing(right, { rightCrossSinceMs = it }, rightCrossSinceMs))
            events += crossingEvent(right!!)

        // --- hard shoulder heuristic: solid line left of corridor, nothing right ---
        val solidLeftOfUs = left != null && left.kind != LaneLine.Kind.DASHED &&
                left.xBottom < (0.5f + centerOffset) - CORRIDOR
        if (solidLeftOfUs && right == null) {
            if (shoulderSinceMs == 0L) shoulderSinceMs = tMs
            if (tMs - shoulderSinceMs > SHOULDER_PERSIST_MS) {
                events += RiskEventCandidate(RiskType.HARD_SHOULDER_DRIVING,
                    Severity.WARNING, 0.5f, "solid edge line left of vehicle path")
                shoulderSinceMs = tMs
            }
        } else shoulderSinceMs = 0L

        return lanes to events
    }

    private fun crossingEvent(l: LaneLine) = when (l.kind) {
        LaneLine.Kind.DOUBLE_SOLID -> RiskEventCandidate(
            RiskType.DOUBLE_LINE_CROSSING, Severity.CRITICAL, 0.7f, "double solid line")
        LaneLine.Kind.SOLID -> RiskEventCandidate(
            RiskType.SOLID_LINE_CROSSING, Severity.WARNING, 0.7f, "solid line")
        LaneLine.Kind.DASHED -> RiskEventCandidate(
            RiskType.LANE_DRIFT, Severity.INFO, 0.6f, "dashed line crossed")
    }

    private fun luma(c: Int): Int =
        (77 * (c shr 16 and 0xFF) + 150 * (c shr 8 and 0xFF) + 29 * (c and 0xFF)) shr 8

    companion object {
        const val GRAD_THRESH = 38
        const val BRIGHT_MIN = 120
        const val MIN_PTS = 60
        const val MIN_COVERAGE = 0.35f
        const val SOLID_COVERAGE = 0.7f
        const val CORRIDOR = 0.12f          // half-width of own-vehicle corridor
        const val CROSS_PERSIST_MS = 700L
        const val SHOULDER_PERSIST_MS = 4000L
    }
}
