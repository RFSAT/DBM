package com.rfsat.dms.service

import com.rfsat.dms.util.DLog

/**
 * Context-gated throttling (see docs/context-gated-throttling-sketch.md).
 *
 * Decides, per analysis role, whether that role should run on the current frame.
 * The principle: keep event-DISCOVERY detectors fast enough to catch their
 * events, but use cheap always-on signals (map/GPS context, presence, stability)
 * to decide which EXPENSIVE analyses are currently relevant — cutting heat on
 * long uneventful stretches without missing events.
 *
 * Each role has a base interval (run every Nth frame). The effective interval is
 *   base * thermalMult * contextMult
 * where contextMult is 1.0 (full rate) up to a larger value (idle) depending on
 * live context. DRIVER is never gated down.
 */
class ProcessingGovernor {

    enum class Role { DRIVER, SIGN, FOLLOWING, LIGHTS, LANE, OBJECT }

    // base interval in frames (1 = every frame)
    private val baseInterval = mapOf(
        Role.DRIVER to 1,
        Role.OBJECT to 1,        // cheap; gates the expensive following pipeline
        Role.SIGN to 2,
        Role.FOLLOWING to 1,
        Role.LIGHTS to 2,
        Role.LANE to 1,
    )

    // ---- live context inputs (set cheaply each frame from existing signals) ----
    @Volatile var thermalMult: Float = 1f          // existing thermal backoff (>=1)
    @Volatile var signsExpectedAhead: Boolean = true   // map/cache: signs likely near
    @Volatile var nearJunction: Boolean = true         // map: junction/urban context
    @Volatile var leadPresent: Boolean = false         // a lead vehicle is detected
    @Volatile var laneStable: Boolean = false          // lane dead-centre & steady
    @Volatile var speedMs: Float = 0f

    private val counters = HashMap<Role, Int>()

    companion object {
        private const val TAG = "Governor"
        // context multipliers when a role is NOT currently relevant
        const val IDLE_SIGN = 3f       // open road, nothing expected
        const val IDLE_LIGHTS = 4f     // not near a junction
        const val IDLE_LANE = 3f       // lane steady
        const val IDLE_FOLLOWING = 4f  // no lead vehicle
        // thermal level beyond which even the driver path slows slightly
        const val SEVERE_THERMAL = 3f
    }

    /** Context multiplier for a role: 1.0 = full rate, higher = throttled. */
    private fun contextMult(role: Role): Float = when (role) {
        Role.DRIVER -> 1f                                   // never gated down
        Role.OBJECT -> 1f                                   // cheap, always on
        Role.SIGN -> if (signsExpectedAhead || nearJunction) 1f else IDLE_SIGN
        Role.LIGHTS -> if (nearJunction) 1f else IDLE_LIGHTS
        Role.FOLLOWING -> if (leadPresent) 1f else IDLE_FOLLOWING
        Role.LANE -> if (!laneStable) 1f else IDLE_LANE
    }

    /** Effective interval (frames) for a role given thermal + context. */
    fun intervalFor(role: Role): Int {
        val base = baseInterval[role] ?: 1
        // Driver monitoring is safety-critical (drowsiness onset is silent), so it
        // is exempt from context gating and resists thermal backoff: it only slows
        // under SEVERE thermal pressure, and even then less than other roles.
        val thermal = if (role == Role.DRIVER) {
            if (thermalMult >= SEVERE_THERMAL) 1.5f else 1f
        } else thermalMult
        val eff = base * thermal * contextMult(role)
        return eff.toInt().coerceAtLeast(1)
    }

    /**
     * Advance this role's frame counter and report whether it should run now.
     * Call once per frame per role that is enabled.
     */
    fun shouldRun(role: Role): Boolean {
        val n = (counters[role] ?: 0) + 1
        counters[role] = n
        val interval = intervalFor(role)
        if (n % interval == 0) return true
        return false
    }

    /** Reset all counters (e.g. on monitoring start). */
    fun reset() = counters.clear()

    fun debugState(): String =
        "sign=${intervalFor(Role.SIGN)} lights=${intervalFor(Role.LIGHTS)} " +
        "follow=${intervalFor(Role.FOLLOWING)} lane=${intervalFor(Role.LANE)} " +
        "thermal=%.1f".format(thermalMult)
}
