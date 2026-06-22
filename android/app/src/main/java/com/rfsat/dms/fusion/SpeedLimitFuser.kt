package com.rfsat.dms.fusion

import com.rfsat.dms.util.DLog

/** Outcome of a fusion step. */
data class FusedLimit(
    val limitKmh: Int,            // -1 if unknown
    val source: Source,
    val confidence: Double,
    val disagree: Boolean,
) {
    enum class Source { SIGN, CACHE, MAP, NONE }
}

/**
 * Three-source speed-limit fusion, ported from the validated MATLAB prototype
 * (fuseSpeedLimit.m). Priority ladder:
 *   1. LIVE  — a confidently recognised sign now (current truth; always wins,
 *              and is recorded to the cache)
 *   2. CACHE — a sign remembered on this segment from an earlier pass
 *   3. MAP   — the OSM baseline for the segment
 *   4. NONE  — unknown
 *
 * Stateful: hold one instance for the trip and call fuse() per GPS fix.
 */
class SpeedLimitFuser(private val cache: SignLimitCache) {

    // live-sign hold state
    private var signLimit = -1
    private var signConf = 0.0
    private var signTimeMs = Long.MIN_VALUE
    private var signSegId = -1L
    private var signActive = false

    companion object {
        private const val TAG = "SpeedLimitFuser"
        const val SIGN_MIN_CONF = 0.55
        // A held camera sign is retained until a new sign or a new road segment,
        // so it does not time out. Its confidence eases off over this window (for
        // the fusion confidence value only), but never below a floor.
        const val SIGN_CONF_DECAY_MS = 120_000.0
        const val SIGN_HELD_MIN_CONF = 0.50
        const val DISAGREE_TOL = 6
        const val AGREE_BONUS = 0.15
        const val MAP_CONFIDENCE = 0.6
    }

    /**
     * @param mapLimit  OSM limit for the matched segment, or -1 if none
     * @param signLimitNow  live sign reading this step, or -1 if none
     * @param signConfNow   confidence [0,1] of the live reading (0 if none)
     * @param segId  matched segment id, or -1 if unmatched
     * @param roadworksNear true if a roadworks sign is currently in view
     * @param sawSignCandidate true if a sign-sized speed-limit detection was
     *        processed this step (OCR had a genuine chance to read it). Used to
     *        decide a cache re-confirmation "miss": a real read opportunity that
     *        did not confirm the cached value counts toward eviction.
     */
    fun fuse(
        nowMs: Long, mapLimit: Int, signLimitNow: Int, signConfNow: Double,
        segId: Long, roadworksNear: Boolean, sawSignCandidate: Boolean = false,
    ): FusedLimit {
        val haveSign = signLimitNow > 0 && signConfNow >= SIGN_MIN_CONF

        // 1. LIVE confident sign — wins and is recorded.
        if (haveSign) {
            signLimit = signLimitNow; signConf = signConfNow
            signTimeMs = nowMs; signSegId = segId; signActive = true
            cache.observe(segId, signLimitNow, nowMs, roadworksNear)

            var conf = signConfNow
            var disagree = false
            if (mapLimit > 0 && kotlin.math.abs(mapLimit - signLimitNow) >= DISAGREE_TOL) {
                disagree = true
            } else if (mapLimit > 0) {
                conf = (signConfNow + AGREE_BONUS).coerceAtMost(1.0)
            }
            return FusedLimit(signLimitNow, FusedLimit.Source.SIGN, conf, disagree)
        }

        // 1b. Held live sign still valid?
        // 1b. Held live sign: a recognised sign governs until a NEW sign appears
        // (handled by the LIVE path above, which always wins) or until the
        // vehicle moves onto a DIFFERENT road segment. It does NOT time out — a
        // sign you passed stays in force along that stretch of road.
        if (signActive) {
            val movedSeg = segId >= 0 && signSegId >= 0 && segId != signSegId
            if (!movedSeg) {
                // Confidence eases off slowly with time but the limit is retained.
                val age = nowMs - signTimeMs
                val conf = (signConf * (1.0 - age.toDouble() / SIGN_CONF_DECAY_MS))
                    .coerceIn(SIGN_HELD_MIN_CONF, 1.0)
                return FusedLimit(signLimit, FusedLimit.Source.SIGN, conf, false)
            } else signActive = false
        }

        // 2. CACHE — remembered sign for this segment.
        cache.lookup(segId, nowMs)?.let { hit ->
            // Re-confirmation miss: we had a genuine read opportunity here (a
            // sign-sized detection was processed) but it did not produce a
            // confident reading that matched the cache. Count it; if the sign has
            // now gone missing enough times, evict and fall through to the map.
            if (sawSignCandidate) {
                val evicted = cache.missedConfirmation(segId)
                if (evicted) {
                    DLog.i(TAG, "cache evicted seg=$segId (sign gone); reverting to map")
                    if (mapLimit > 0)
                        return FusedLimit(mapLimit, FusedLimit.Source.MAP, MAP_CONFIDENCE, false)
                    return FusedLimit(-1, FusedLimit.Source.NONE, 0.0, false)
                }
            }
            val disagree = mapLimit > 0 && kotlin.math.abs(mapLimit - hit.limit) >= DISAGREE_TOL
            return FusedLimit(hit.limit, FusedLimit.Source.CACHE, hit.confidence, disagree)
        }

        // 3. MAP baseline.
        if (mapLimit > 0) {
            return FusedLimit(mapLimit, FusedLimit.Source.MAP, MAP_CONFIDENCE, false)
        }

        // 4. NONE.
        return FusedLimit(-1, FusedLimit.Source.NONE, 0.0, false)
    }
}
