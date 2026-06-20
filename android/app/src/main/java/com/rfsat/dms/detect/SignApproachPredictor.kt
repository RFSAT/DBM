package com.rfsat.dms.detect

import com.rfsat.dms.util.DLog

/**
 * Decides WHEN to spend an OCR call on an approaching speed-limit sign, using the
 * sign's apparent growth (box height across frames) together with GPS speed.
 *
 * Why: at speed a sign is only legible for a brief window, and OCR is relatively
 * expensive (and warms the device). Running it every frame a sign is visible
 * wastes effort on frames where the sign is still too small, and on frames after
 * it has peaked. This predictor aims OCR at the moment the sign is largest /
 * about to be largest within the readable window.
 *
 * It does NOT block the map: the displayed limit always comes from the fusion
 * (map first). This only schedules the camera's corrective OCR.
 *
 * Heuristic, deliberately simple:
 *  - Track the most recent box heights (normalised, 0..1 of frame height) and
 *    their timestamps for the currently-approaching sign.
 *  - Estimate growth rate (d height / d time). Combined with GPS speed this tells
 *    us whether the sign is still approaching (growing) or receding (shrinking).
 *  - Fire OCR when the sign is in the readable band AND either (a) it is at/near
 *    its peak (growth flattening or turning negative), or (b) it is already large
 *    enough that waiting risks losing it (close pass).
 *  - Suppress OCR while the sign is small and still growing fast (it will be
 *    bigger and clearer in a forthcoming frame) — unless GPS speed is high enough
 *    that "a forthcoming frame" may be too late, in which case take the read now.
 */
class SignApproachPredictor {

    private data class Sample(val h: Float, val tMs: Long)

    private val history = ArrayDeque<Sample>()
    private var lastFireMs = Long.MIN_VALUE

    companion object {
        private const val TAG = "SignApproach"
        private const val MAX_HISTORY = 6
        // readable band (normalised box height). Below MIN it is not worth OCR;
        // at/above GOOD it is comfortably readable.
        const val READABLE_MIN = 0.028f
        const val READABLE_GOOD = 0.060f
        // growth (per second) below which we treat the sign as "peaked".
        const val FLATTEN_RATE = 0.010f
        // GPS speed (m/s) above which we stop waiting for a bigger frame and read
        // as soon as the sign is minimally readable (sign will be gone soon).
        const val HIGH_SPEED_MS = 22f       // ~80 km/h
        // don't fire OCR more often than this (ms) for the same approach.
        const val MIN_FIRE_GAP_MS = 250L
    }

    /** Call when no speed-limit sign is in view, to reset the approach state. */
    fun reset() { history.clear() }

    /**
     * Decide whether to run OCR this frame for a detected speed-limit sign.
     * @param boxH   normalised box height (0..1) of the detection this frame
     * @param speedMs current GPS speed in m/s (<=0 if unknown)
     * @param nowMs  monotonic time
     */
    fun shouldOcr(boxH: Float, speedMs: Float, nowMs: Long): Boolean {
        history.addLast(Sample(boxH, nowMs))
        while (history.size > MAX_HISTORY) history.removeFirst()

        if (boxH < READABLE_MIN) return false           // never worth it this small
        if (nowMs - lastFireMs < MIN_FIRE_GAP_MS) return false

        val rate = growthRatePerSec()
        val highSpeed = speedMs >= HIGH_SPEED_MS
        val peaked = rate <= FLATTEN_RATE               // flattening or shrinking
        val comfortablyReadable = boxH >= READABLE_GOOD
        // Fallback: a sign that is readable but small and never clearly peaks
        // (a distant sign held across several frames) must still get a read — not
        // firing at all would be worse than the old always-try behaviour.
        val heldLongEnough = history.size >= MAX_HISTORY - 1

        val fire = when {
            // Big enough that the read is reliable — take it now.
            comfortablyReadable -> true
            // Past the peak (sign now receding): this is our last good chance.
            peaked -> true
            // Fast approach: a "bigger later frame" may never arrive before we
            // pass the sign — read as soon as it is minimally legible.
            highSpeed -> true
            // Readable but small and we've waited several frames without a better
            // one — take the read rather than miss the sign entirely.
            heldLongEnough -> true
            // Otherwise: still small and growing at moderate speed — wait for a
            // bigger, clearer frame.
            else -> false
        }
        if (fire) {
            lastFireMs = nowMs
            DLog.i(TAG, "OCR fire: box=%.3f rate=%.3f/s speed=%.1f peaked=%b high=%b".format(
                boxH, rate, speedMs, peaked, highSpeed))
        }
        return fire
    }

    /** Estimated growth of normalised box height per second over recent samples. */
    private fun growthRatePerSec(): Float {
        if (history.size < 2) return Float.MAX_VALUE     // unknown -> treat as growing
        val first = history.first(); val last = history.last()
        val dt = (last.tMs - first.tMs) / 1000f
        if (dt <= 0f) return Float.MAX_VALUE
        return (last.h - first.h) / dt
    }
}
