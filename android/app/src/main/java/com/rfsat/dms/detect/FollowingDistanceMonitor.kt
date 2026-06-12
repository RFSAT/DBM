package com.rfsat.dms.detect

import com.rfsat.dms.Detection
import com.rfsat.dms.RiskEventCandidate
import com.rfsat.dms.RiskType
import com.rfsat.dms.Severity

/**
 * Safe following-distance monitoring.
 *
 * Distance to the lead vehicle is estimated monocularly from its bounding-box
 * width: d = f_px * W_vehicle / w_px, with f_px ≈ FOCAL_FACTOR * frame width
 * (typical phone main-camera horizontal FOV ~58–70°) and W_vehicle = 1.8 m.
 * Accuracy is ±20–30 % — adequate for a graded warning, not for measurement.
 *
 * Required (stopping) distance at speed v:
 *   d_stop = v * t_react + v² / (2 a)
 * with t_react = 1.0 s and a = 6.5 m/s² (dry-road service braking), the whole
 * result scaled by the user's stopping-distance factor (50–200 %) from
 * Settings — e.g. 150 % for wet conditions or a cautious margin.
 *
 * The lead vehicle is the vehicle-class detection whose box is horizontally
 * centred (own lane) with the largest width (nearest). Events require the
 * condition to persist PERSIST_MS to ride out single-frame box jitter.
 */
class FollowingDistanceMonitor {

    /** Stopping-distance scale factor from Settings (1.0 = textbook dry road). */
    @Volatile var factor = 1.0f

    private var tooCloseSinceMs = 0L
    private var lastEventMs = 0L

    fun check(
        detections: List<Detection>,
        speedKmh: Int,
        tMs: Long,
    ): Pair<RiskEventCandidate?, Float?> {
        if (speedKmh < MIN_SPEED_KMH) { tooCloseSinceMs = 0; return null to null }
        val lead = detections
            .filter { it.labelText in VEHICLES &&
                      (it.left + it.right) / 2f in 0.35f..0.65f }
            .maxByOrNull { it.right - it.left } ?: run {
                tooCloseSinceMs = 0; return null to null
            }

        val wNorm = lead.right - lead.left
        if (wNorm < 0.02f) { tooCloseSinceMs = 0; return null to null }
        val distM = FOCAL_FACTOR * VEHICLE_WIDTH_M / wNorm

        val v = speedKmh / 3.6f
        val stopM = (v * REACTION_S + v * v / (2f * DECEL_MS2)) * factor

        if (distM >= stopM) { tooCloseSinceMs = 0; return null to distM }

        if (tooCloseSinceMs == 0L) { tooCloseSinceMs = tMs; return null to distM }
        if (tMs - tooCloseSinceMs < PERSIST_MS) return null to distM
        if (tMs - lastEventMs < EVENT_INTERVAL_MS) return null to distM
        lastEventMs = tMs

        val severe = distM < stopM * 0.6f
        return RiskEventCandidate(
            RiskType.UNSAFE_FOLLOWING_DISTANCE,
            if (severe) Severity.CRITICAL else Severity.WARNING,
            0.6f,
            "≈%.0f m gap, ≥%.0f m needed at %d km/h".format(distM, stopM, speedKmh)
        ) to distM
    }

    companion object {
        val VEHICLES = setOf("car", "truck", "bus", "motorcycle")
        const val VEHICLE_WIDTH_M = 1.8f
        const val FOCAL_FACTOR = 0.9f      // f_px / frame-width
        const val REACTION_S = 1.0f
        const val DECEL_MS2 = 6.5f
        const val MIN_SPEED_KMH = 20
        const val PERSIST_MS = 1200L
        const val EVENT_INTERVAL_MS = 10_000L
    }
}
