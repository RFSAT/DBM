package com.rfsat.dms.fusion

/**
 * Per-segment remembered-sign cache, ported from the validated MATLAB prototype
 * (initSignCache / cacheObserve / cacheLookup). Stores speed limits observed
 * from confidently read signs, keyed by road segment, so a sign read once can
 * cover that segment on later passes — even where OSM is wrong or untagged.
 *
 * Safeguards (all from the prototype): segment-keyed (not point), confirm-before-
 * trust (>= MIN_OBS observations), age decay/expiry, and temporary (roadworks)
 * limits given a short expiry so they never masquerade as permanent.
 *
 * In-memory for now; persists to disk via toSerializable()/fromSerializable()
 * so a future Room/file store can save it. It is LOCAL only — never transmitted
 * (it is a location history).
 */
class SignLimitCache {

    private data class Entry(
        var limit: Int,
        var count: Int,
        var firstSeenMs: Long,
        var lastSeenMs: Long,
        var temporary: Boolean,
    )

    private val store = HashMap<Long, Entry>()

    companion object {
        const val MIN_OBS = 2                      // confirm-before-trust
        const val MERGE_TOL = 3                     // km/h
        const val MAX_AGE_MS = 365L * 24 * 3600 * 1000   // ~1 year (permanent)
        const val TEMP_MAX_AGE_MS = 3600L * 1000         // 1 hour (roadworks)
        const val BASE_CONF = 0.7
    }

    /** Record a confidently observed sign for a segment. */
    fun observe(segId: Long, limit: Int, nowMs: Long, temporary: Boolean) {
        if (segId < 0 || limit <= 0) return
        val e = store[segId]
        if (e == null) {
            store[segId] = Entry(limit, 1, nowMs, nowMs, temporary)
        } else if (kotlin.math.abs(e.limit - limit) <= MERGE_TOL) {
            e.count += 1; e.lastSeenMs = nowMs
            e.temporary = e.temporary && temporary    // stays permanent if ever seen so
        } else {
            // A different limit than remembered: likely a real change (or the old
            // entry was wrong). Reset and require re-confirmation.
            e.limit = limit; e.count = 1
            e.firstSeenMs = nowMs; e.lastSeenMs = nowMs; e.temporary = temporary
        }
    }

    /** Look up a remembered limit for a segment. Returns null if unknown,
     *  unconfirmed, or expired; otherwise the limit with a decayed confidence. */
    fun lookup(segId: Long, nowMs: Long): CacheHit? {
        if (segId < 0) return null
        val e = store[segId] ?: return null
        val age = nowMs - e.lastSeenMs
        val maxAge = if (e.temporary) TEMP_MAX_AGE_MS else MAX_AGE_MS
        if (age > maxAge) return null
        if (e.count < MIN_OBS) return null
        val obsFactor = min(1.0, e.count.toDouble() / (MIN_OBS * 2))
        val ageFactor = (1.0 - age.toDouble() / maxAge).coerceAtLeast(0.0)
        return CacheHit(e.limit, BASE_CONF * obsFactor * ageFactor)
    }

    data class CacheHit(val limit: Int, val confidence: Double)

    private fun min(a: Double, b: Double) = if (a < b) a else b
}
