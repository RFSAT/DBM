package com.rfsat.dms.detect

import com.rfsat.dms.RiskEventCandidate
import com.rfsat.dms.RiskType
import com.rfsat.dms.Severity
import kotlin.math.abs

/**
 * Illegal-turn detection (ILLEGAL_TURN).
 *
 * A turn manoeuvre is inferred from a sustained gyroscope yaw-rate: integrating
 * yaw over the manoeuvre gives the heading change. A turn is "illegal" when it
 * occurs shortly after passing a sign that prohibits it at that point. GTSRB
 * does not contain explicit no-left/no-right signs, so the detectable cases are
 * the unambiguous ones from the classes the model does recognise:
 *
 *   - "No entry" (17) / "No vehicles" (15): any turn INTO the restricted way.
 *   - "Ahead only" (35): a left or right turn where only straight is permitted.
 *
 * The monitor records the most recent relevant sign with a timestamp; if a turn
 * (heading change beyond TURN_DEG) completes within SIGN_WINDOW_MS of such a
 * sign while the vehicle is moving, an event is raised. Confidence is moderate:
 * without map/junction context this is an assistance-grade cue, corroborated by
 * the independent yaw signal and rate-limited.
 *
 * This is a classical, model-free detector. A future dedicated sign set (e.g.
 * Mapillary, which has no-turn classes) would let it cover explicit no-turn
 * signs behind the same interface.
 */
class TurnMonitor {

    private enum class Restriction { NONE, NO_ENTRY, AHEAD_ONLY, NO_LEFT, NO_RIGHT, NO_U }

    private var restriction = Restriction.NONE
    private var restrictionMs = 0L

    // Turn integration state.
    private var turning = false
    private var headingAccumDeg = 0f
    private var turnStartMs = 0L
    private var lastEventMs = 0L

    /** Record a recognised sign that may make a subsequent turn illegal.
     *  Accepts GTSRB ids (legacy) and the EU SignDetector ids. */
    fun onSign(classId: Int, tMs: Long) {
        // EU SignDetector class ids (Mapillary model).
        val r = when (classId) {
            SignDetector.NO_LEFT_TURN -> Restriction.NO_LEFT
            SignDetector.NO_RIGHT_TURN -> Restriction.NO_RIGHT
            SignDetector.NO_U_TURN -> Restriction.NO_U
            SignDetector.NO_STRAIGHT -> Restriction.NONE   // straight handled elsewhere
            SignDetector.AHEAD_ONLY -> Restriction.AHEAD_ONLY
            5 -> Restriction.NO_ENTRY          // EU "no entry" id 5
            else -> return
        }
        if (r != Restriction.NONE) { restriction = r; restrictionMs = tMs }
    }

    /**
     * Feed the current yaw-rate (deg/s) and speed each frame.
     * @return an ILLEGAL_TURN event when a prohibited turn completes, else null.
     */
    fun update(yawRateDps: Float, speedKmh: Int, tMs: Long): RiskEventCandidate? {
        // Integrate a turn while yaw-rate stays above the noise floor.
        if (abs(yawRateDps) > TURN_RATE_FLOOR && speedKmh >= MIN_SPEED_KMH) {
            if (!turning) { turning = true; headingAccumDeg = 0f; turnStartMs = tMs }
            // dt approximated by frame cadence; integrate.
            headingAccumDeg += yawRateDps * FRAME_DT_S
        } else if (turning) {
            // Turn ended — evaluate it.
            val ev = evaluate(headingAccumDeg, tMs, speedKmh)
            turning = false; headingAccumDeg = 0f
            return ev
        }
        // Abandon a turn that runs too long (likely a curve, not a junction).
        if (turning && tMs - turnStartMs > MAX_TURN_MS) { turning = false; headingAccumDeg = 0f }
        return null
    }

    private fun evaluate(headingDeg: Float, tMs: Long, speedKmh: Int): RiskEventCandidate? {
        if (abs(headingDeg) < TURN_DEG) return null
        val recentSign = restriction != Restriction.NONE &&
                tMs - restrictionMs < SIGN_WINDOW_MS
        if (!recentSign) return null
        if (tMs - lastEventMs < EVENT_INTERVAL_MS) return null

        val dir = if (headingDeg > 0) "right" else "left"
        val turnedLeft = headingDeg < 0
        val turnedRight = headingDeg > 0
        val illegal = when (restriction) {
            Restriction.NO_ENTRY -> true                 // any turn into a restricted way
            Restriction.AHEAD_ONLY -> true               // straight-only: any turn breaches
            Restriction.NO_LEFT -> turnedLeft            // directional: only the banned way
            Restriction.NO_RIGHT -> turnedRight
            Restriction.NO_U -> abs(headingDeg) > 140f    // U-turn = near-reversal
            Restriction.NONE -> false
        }
        if (!illegal) {
            // Wrong-direction turn under a directional ban is legal -> clear it.
            if (restriction == Restriction.NO_LEFT || restriction == Restriction.NO_RIGHT)
                restriction = Restriction.NONE
            return null
        }

        lastEventMs = tMs
        val reason = when (restriction) {
            Restriction.NO_ENTRY -> "turn into a no-entry way"
            Restriction.AHEAD_ONLY -> "$dir turn where ahead-only applies"
            Restriction.NO_LEFT -> "left turn where no-left-turn applies"
            Restriction.NO_RIGHT -> "right turn where no-right-turn applies"
            Restriction.NO_U -> "U-turn where no-U-turn applies"
            else -> "prohibited turn"
        }
        // Consume the restriction so it doesn't fire twice.
        restriction = Restriction.NONE
        return RiskEventCandidate(RiskType.ILLEGAL_TURN, Severity.WARNING, 0.55f, reason)
    }

    companion object {
        const val TURN_RATE_FLOOR = 12f     // deg/s — above gentle lane-keeping
        const val TURN_DEG = 55f            // accumulated heading change for a "turn"
        const val MIN_SPEED_KMH = 5
        const val FRAME_DT_S = 0.16f        // ~6 fps road cadence
        const val MAX_TURN_MS = 6000L
        const val SIGN_WINDOW_MS = 12_000L  // sign must be recent
        const val EVENT_INTERVAL_MS = 10_000L
    }
}
