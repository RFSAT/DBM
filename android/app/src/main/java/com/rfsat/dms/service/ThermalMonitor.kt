package com.rfsat.dms.service

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.rfsat.dms.util.DLog

/**
 * Reads the device thermal state and maps it to a throttling multiplier for the
 * ProcessingGovernor. Two signals are combined:
 *
 *  - Thermal STATUS (API 29+): a discrete level NONE..SHUTDOWN reported by the OS
 *    thermal service. This is the authoritative "the device is getting hot" flag.
 *  - Thermal HEADROOM (API 30+): a forecast where 1.0 means we are AT the throttle
 *    threshold and >1.0 means over it. A finer, earlier-warning signal.
 *
 * The result is a multiplier >= 1.0 that the governor multiplies into every
 * (non-driver) role's frame interval, so the hotter the device, the less often
 * the expensive analyses run — backing off before the OS forcibly throttles the
 * CPU/GPU, which keeps the app responsive and the device from overheating.
 *
 * Everything is logged so the behaviour can be verified on a drive.
 */
class ThermalMonitor(private val ctx: Context) {

    // Lazy + guarded: resolve PowerManager on first use (in start()), never during
    // construction, so this is safe even if the instance is built early.
    private val pm: PowerManager? by lazy {
        runCatching { ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager }.getOrNull()
    }
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    /** Current multiplier (>=1). Read by the governor each frame. */
    @Volatile var multiplier: Float = 1f
        private set

    @Volatile private var lastStatus: Int = -1
    private var lastHeadroomLogMs = 0L

    companion object {
        private const val TAG = "ThermalMonitor"
        // status -> multiplier. NONE/LIGHT run full rate; the device only starts
        // throttling analyses once it reports MODERATE or worse.
        private fun statusMult(status: Int): Float = when (status) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT     -> 1f
            PowerManager.THERMAL_STATUS_MODERATE  -> 1.5f
            PowerManager.THERMAL_STATUS_SEVERE    -> 2.5f
            PowerManager.THERMAL_STATUS_CRITICAL  -> 4f
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN  -> 6f
            else -> 1f
        }

        private fun statusName(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN($status)"
        }
    }

    /** Begin listening. Safe to call on any API level; no-ops the parts that need newer APIs. */
    fun start() {
        try {
            val p = pm ?: run { DLog.w(TAG, "no PowerManager; thermal backoff disabled"); return }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val initial = p.currentThermalStatus
                lastStatus = initial
                multiplier = statusMult(initial)
                DLog.i(TAG, "thermal status at start: ${statusName(initial)} -> x%.1f".format(multiplier))
                val l = PowerManager.OnThermalStatusChangedListener { status ->
                    lastStatus = status
                    recompute()
                    DLog.i(TAG, "thermal status changed: ${statusName(status)} -> x%.1f".format(multiplier))
                }
                listener = l
                p.addThermalStatusListener(l)
            } else {
                DLog.i(TAG, "thermal status API unavailable (API ${Build.VERSION.SDK_INT}); backoff off")
            }
        } catch (e: Throwable) {
            // Thermal backoff is an optimisation, never worth crashing for.
            multiplier = 1f
            DLog.w(TAG, "thermal monitor disabled (start failed): ${e.message}")
        }
    }

    /**
     * Poll the headroom forecast (API 30+). Call periodically from the frame loop
     * (it is rate-limited internally). Headroom rises before the discrete status
     * does, giving earlier backoff. Combined with status by taking the max.
     */
    fun pollHeadroom(nowMs: Long) {
        val p = pm ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // getThermalHeadroom wants a forecast horizon in seconds; 0 = now.
        val hr = try { p.getThermalHeadroom(0) } catch (_: Throwable) { Float.NaN }
        if (hr.isNaN()) return
        recompute(hr)
        if (nowMs - lastHeadroomLogMs > 15_000) {        // log every ~15 s
            lastHeadroomLogMs = nowMs
            DLog.i(TAG, "thermal headroom=%.2f status=%s -> x%.1f".format(
                hr, statusName(lastStatus), multiplier))
        }
    }

    /** Recompute the multiplier from the latest status and (optional) headroom. */
    private fun recompute(headroom: Float = Float.NaN) {
        val byStatus = statusMult(lastStatus)
        // headroom 1.0 = at threshold. Map >=0.85 into a gentle ramp so we back
        // off slightly BEFORE the OS flips the discrete status.
        val byHeadroom = when {
            headroom.isNaN() -> 1f
            headroom >= 1.0f -> 2.5f
            headroom >= 0.95f -> 2f
            headroom >= 0.85f -> 1.5f
            else -> 1f
        }
        multiplier = maxOf(byStatus, byHeadroom)
    }

    fun stop() {
        val p = pm ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listener?.let { p.removeThermalStatusListener(it) }
        }
        listener = null
    }
}
