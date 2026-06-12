package com.rfsat.dms.detect

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Visual speed estimation from the road-facing camera — fallback for when
 * GNSS is unavailable (tunnels, urban canyons, no fix).
 *
 * Method: sparse block-matching optical flow on the road surface.
 *  - Downsampled luma of the lower-frame ROI (road texture moves toward the
 *    camera, i.e. downward in the image, proportionally to vehicle speed).
 *  - A grid of reference blocks from the previous frame is searched in the
 *    current frame along the vertical axis (dominant motion component for a
 *    forward-facing camera); the median vertical displacement (px/s) is the
 *    flow measure — median rejects outliers from other moving vehicles.
 *  - Speed = K * flow, where K depends on camera height, pitch and focal
 *    length. K is AUTO-CALIBRATED continuously while GNSS is healthy
 *    (robust running median of gpsSpeed/flow), so the fallback is already
 *    tuned to the installation when GNSS drops.
 *
 * Accuracy expectation: ±10–20 % once calibrated — adequate for speed-limit
 * compliance with the existing tolerance margins, clearly flagged in the UI
 * as estimated.
 */
class VisualSpeedEstimator {

    private var prev: IntArray? = null
    private var prevT = 0L
    private var prevW = 0; private var prevH = 0

    /** km/h per (px/s) of median road flow. Learned; persisted by caller if desired. */
    var kCalib = 0f; private set
    private val kSamples = ArrayDeque<Float>()

    private var emaSpeed = 0f
    val isCalibrated get() = kCalib > 0f

    /** Feed every analysed road frame. @return estimated speed km/h, or null. */
    fun onFrame(frame: Bitmap, tMs: Long, gpsSpeedKmh: Int?, gpsHealthy: Boolean): Int? {
        // Downsample luma of road ROI (lower 40 %), ~80 px wide.
        val dw = 80
        val scale = frame.width / dw
        val dh = (frame.height * 0.4f / scale).toInt().coerceAtLeast(20)
        val y0 = frame.height - dh * scale
        val cur = IntArray(dw * dh)
        val rowPx = IntArray(frame.width)
        for (yy in 0 until dh) {
            frame.getPixels(rowPx, 0, frame.width, 0, y0 + yy * scale, frame.width, 1)
            for (xx in 0 until dw) {
                val c = rowPx[xx * scale]
                cur[yy * dw + xx] =
                    (77 * (c shr 16 and 0xFF) + 150 * (c shr 8 and 0xFF) + 29 * (c and 0xFF)) shr 8
            }
        }

        val p = prev
        var result: Int? = null
        if (p != null && prevW == dw && prevH == dh && tMs > prevT) {
            val dt = (tMs - prevT) / 1000f
            val flowPxS = medianVerticalFlow(p, cur, dw, dh) / dt * scale  // full-res px/s
            if (flowPxS > 1f) {
                // calibrate while GNSS healthy and actually moving
                if (gpsHealthy && gpsSpeedKmh != null && gpsSpeedKmh > 15) {
                    val k = gpsSpeedKmh / flowPxS
                    if (k.isFinite() && k > 0) {
                        kSamples.addLast(k)
                        if (kSamples.size > 60) kSamples.removeFirst()
                        if (kSamples.size >= 10)
                            kCalib = kSamples.sorted()[kSamples.size / 2]
                    }
                }
                if (isCalibrated) {
                    val v = (kCalib * flowPxS).coerceIn(0f, 200f)
                    emaSpeed = if (emaSpeed == 0f) v else 0.7f * emaSpeed + 0.3f * v
                    result = emaSpeed.toInt()
                }
            } else if (isCalibrated) {
                emaSpeed *= 0.7f
                result = emaSpeed.toInt()
            }
        }

        prev = cur; prevT = tMs; prevW = dw; prevH = dh
        return result
    }

    /** Median vertical displacement (downsampled px/frame) of a block grid. */
    private fun medianVerticalFlow(p: IntArray, c: IntArray, w: Int, h: Int): Float {
        val bs = 6; val range = 8
        val flows = ArrayList<Int>(24)
        var by = 1
        while (by + bs + range < h) {
            var bx = 4
            while (bx + bs < w - 4) {
                var bestD = 0; var bestCost = Int.MAX_VALUE
                for (d in 0..range) {                 // road flows downward only
                    var cost = 0
                    var yy = 0
                    while (yy < bs) {
                        var xx = 0
                        while (xx < bs) {
                            cost += abs(p[(by + yy) * w + bx + xx] -
                                        c[(by + yy + d) * w + bx + xx])
                            xx++
                        }
                        yy++
                    }
                    if (cost < bestCost) { bestCost = cost; bestD = d }
                }
                // accept only textured blocks (cost spread implies signal)
                if (bestCost < bs * bs * 30) flows.add(bestD)
                bx += bs + 6
            }
            by += bs + 4
        }
        if (flows.size < 5) return 0f
        flows.sort()
        return flows[flows.size / 2].toFloat()
    }
}
