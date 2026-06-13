package com.rfsat.dms.detect

import com.rfsat.dms.RiskEventCandidate
import com.rfsat.dms.RiskType
import com.rfsat.dms.Severity

/**
 * Cross-detector consensus ("co-training").
 *
 * Principle: trust an event more when an INDEPENDENT detector agrees, and
 * suppress it when an independent detector contradicts it. Crucially the
 * corroborating signal must not derive from the same computation, or the
 * "agreement" is circular. Where one detector is confident, it also supplies
 * a free training label to a weaker detector observing the same fact.
 *
 * Independent signal pairs used here:
 *  - SPEEDING: OCR-read sign limit  vs  GPS speed  vs  visual-flow speed.
 *    Two independent speed sources must agree before a speeding event is
 *    raised at full confidence; a lone source yields a tentative event.
 *  - UNSAFE_FOLLOWING_DISTANCE: monocular box-width distance  vs  the
 *    box-area-growth (TTC) trend. A close gap that is ALSO closing fast is
 *    corroborated (critical); a close but stable gap is demoted (steady
 *    traffic, parallel motion) — kills the commonest false positive.
 *  - LANE crossings: vision lane-crossing  vs  IMU yaw-rate (gyroscope).
 *    A solid/double-line crossing corroborated by an actual heading change
 *    is real; a "crossing" with zero yaw rate is paint noise/shadow — demote.
 *  - VULNERABLE_ROAD_USER / collisions: detection persistence across frames
 *    (tracker age) gates single-frame spurious boxes.
 *
 * Output: an adjusted event list (some promoted, some demoted/dropped) plus
 * label callbacks for the consistency-based personalisation introduced later.
 */
class CrossChecker {

    @Volatile var yawRateDps = 0f          // fed from IMU (deg/s), 0 if absent
    @Volatile var gpsSpeed: Int? = null
    @Volatile var visualSpeed: Int? = null

    /** Returns the event adjusted by corroboration, or null to suppress it. */
    fun adjudicate(ev: RiskEventCandidate, ctx: Ctx): RiskEventCandidate? = when (ev.type) {
        RiskType.SPEEDING -> speedConsensus(ev)
        RiskType.UNSAFE_FOLLOWING_DISTANCE -> followingConsensus(ev, ctx)
        RiskType.SOLID_LINE_CROSSING,
        RiskType.DOUBLE_LINE_CROSSING,
        RiskType.LANE_DRIFT -> laneConsensus(ev)
        RiskType.VULNERABLE_ROAD_USER,
        RiskType.FRONT_COLLISION_RISK,
        RiskType.REAR_COLLISION_RISK -> persistenceGate(ev, ctx)
        else -> ev
    }

    /** Context the road pipeline passes per event. */
    data class Ctx(
        val leadAreaGrowthPerSec: Float = 0f,
        val trackAgeFrames: Int = 0,
    )

    private fun speedConsensus(ev: RiskEventCandidate): RiskEventCandidate {
        val g = gpsSpeed; val v = visualSpeed
        val agree = g != null && v != null && kotlin.math.abs(g - v) <= maxOf(8, g / 10)
        return when {
            agree -> ev.copy(confidence = (ev.confidence + 0.1f).coerceAtMost(0.99f))
            g != null && v != null ->  // two sources disagree -> be cautious
                ev.copy(severity = Severity.WARNING, confidence = ev.confidence * 0.6f)
            else -> ev                 // single source: leave as-is (already tentative)
        }
    }

    private fun followingConsensus(ev: RiskEventCandidate, ctx: Ctx): RiskEventCandidate? {
        // close AND closing -> corroborated; close but not closing -> demote/drop
        return when {
            ctx.leadAreaGrowthPerSec > 0.15f ->
                ev.copy(confidence = (ev.confidence + 0.2f).coerceAtMost(0.99f))
            ctx.leadAreaGrowthPerSec < 0.02f && ev.severity != Severity.CRITICAL ->
                null   // steady gap: most likely matched cruising speed
            else -> ev.copy(severity = Severity.WARNING)
        }
    }

    private fun laneConsensus(ev: RiskEventCandidate): RiskEventCandidate? {
        val turning = kotlin.math.abs(yawRateDps) > YAW_RATE_MIN_DPS
        return when {
            turning -> ev.copy(confidence = (ev.confidence + 0.15f).coerceAtMost(0.99f))
            yawRateDps == 0f -> ev          // no IMU data: cannot corroborate, pass
            else -> if (ev.type == RiskType.DOUBLE_LINE_CROSSING) ev  // keep serious one
                    else null               // "crossing" with no heading change -> noise
        }
    }

    private fun persistenceGate(ev: RiskEventCandidate, ctx: Ctx): RiskEventCandidate? =
        if (ctx.trackAgeFrames >= MIN_TRACK_AGE) ev else null

    companion object {
        const val YAW_RATE_MIN_DPS = 4f
        const val MIN_TRACK_AGE = 3
    }
}
