package com.rfsat.dms.detect

import com.rfsat.dms.Detection
import com.rfsat.dms.RiskEventCandidate
import com.rfsat.dms.RiskType
import com.rfsat.dms.Severity
import android.graphics.Bitmap
import kotlin.math.abs

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

    /** Focal factor f_px/frame-width; refined at runtime when a vehicle is
     *  tracked over a known closing speed (own speed while the lead is roughly
     *  stationary in-frame gives a distance-rate cross-check). Bounded so a
     *  noisy estimate cannot wildly distort distances. */
    @Volatile var focalFactor = FOCAL_FACTOR
    private val focalSamples = ArrayDeque<Float>()

    /** Feed when own speed is known and a single lead vehicle is tracked: the
     *  change in estimated distance over dt should match own speed if the lead
     *  is slow/stopped; the ratio refines the focal factor. */
    fun calibrateFocal(prevWNorm: Float, curWNorm: Float, dtSec: Float, ownSpeedKmh: Int) {
        if (dtSec < 0.2f || ownSpeedKmh < 25 || prevWNorm < 0.02f || curWNorm < 0.02f) return
        val dPrev = focalFactor * VEHICLE_WIDTH_M / prevWNorm
        val dCur = focalFactor * VEHICLE_WIDTH_M / curWNorm
        val measuredClosing = (dPrev - dCur) / dtSec            // m/s
        val expected = ownSpeedKmh / 3.6f
        if (measuredClosing <= 0.5f) return
        val k = focalFactor * (expected / measuredClosing)
        if (k.isFinite() && k in 0.4f..1.6f) {
            focalSamples.addLast(k)
            if (focalSamples.size > 40) focalSamples.removeFirst()
            if (focalSamples.size >= 12) focalFactor = focalSamples.sorted()[focalSamples.size / 2]
        }
    }

    private var tooCloseSinceMs = 0L
    private var lastEventMs = 0L

    // --- Lead-vehicle hazard tracking (sway + hard braking) ---
    private val leadCentreHist = ArrayDeque<Pair<Long, Float>>()   // (t, centreX)
    private val leadWidthHist = ArrayDeque<Pair<Long, Float>>()    // (t, wNorm)
    private var swayDir = 0            // last significant lateral direction
    private var swayCount = 0         // direction reversals within window
    private var swayWindowStartMs = 0L
    private var lastSwayEventMs = 0L
    private var lastBrakeEventMs = 0L

    /**
     * Estimate whether the lead vehicle's brake lights are illuminated, by
     * sampling the lower band of its bounding box (where tail-lights sit) for
     * bright, highly-saturated red. Genuine brake lights appear as TWO bright-red
     * clusters (left + right), often with a central high mount, so we split the
     * band into left/right halves and require strong red on BOTH sides — this
     * rejects a single red object (a sticker, a reflection) and the diffuse red
     * of the body paint. Returns a 0..1 strength (0 = off, ~1 = clearly lit).
     *
     * This is a corroborating cue for hard braking: box-growth says "closing",
     * brake lights say "they are actively braking", and the two together are a
     * much stronger and earlier signal than growth alone. It is intentionally
     * conservative — when unsure it returns a low value and the geometry still
     * governs.
     */
    private fun brakeLightStrength(frame: Bitmap, lead: Detection): Float {
        val W = frame.width; val H = frame.height
        // Lower ~45% of the box, full width, clamped to the frame.
        val bl = (lead.left * W).toInt().coerceIn(0, W - 1)
        val br = (lead.right * W).toInt().coerceIn(0, W - 1)
        val boxTop = lead.top * H; val boxBot = lead.bottom * H
        val bandTop = (boxBot - (boxBot - boxTop) * 0.45f).toInt().coerceIn(0, H - 1)
        val bandBot = boxBot.toInt().coerceIn(0, H - 1)
        val bw = br - bl; val bh = bandBot - bandTop
        if (bw < 8 || bh < 4) return 0f
        val px = IntArray(bw * bh)
        frame.getPixels(px, 0, bw, bl, bandTop, bw, bh)

        val mid = bw / 2
        var leftRed = 0; var rightRed = 0; var total = 0
        var i = 0
        while (i < px.size) {
            val c = px[i]
            val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            val sat = if (mx == 0) 0 else (mx - mn) * 255 / mx
            // Bright + saturated + clearly red-dominant = an illuminated lamp.
            if (mx > 170 && sat > 100 && r > 150 && r > g + 60 && r > b + 60) {
                if ((i % bw) < mid) leftRed++ else rightRed++
            }
            total++
            i += 2   // subsample for speed
        }
        if (total == 0) return 0f
        // Need meaningful red on BOTH sides (the two lamps). Strength scales with
        // the weaker side so a single bright blob does not score high.
        val leftFrac = leftRed.toFloat() / (total / 2f)
        val rightFrac = rightRed.toFloat() / (total / 2f)
        val weaker = minOf(leftFrac, rightFrac)
        // ~1.5% of a half-band lit is a confident pair of lamps; scale to 0..1.
        return (weaker / 0.015f).coerceIn(0f, 1f)
    }

    /**
     * Detect hazards of the LEAD vehicle, evaluated ONLY when it is closer than
     * the safe following distance. Returns any events plus the lead's bounding
     * box so the caller can capture an evidence image / attempt plate OCR.
     *
     *  - Sway: lead box centre crosses a large lateral span repeatedly without a
     *    sustained one-way move (a lane change).
     *  - Hard braking: lead box grows rapidly (closing fast). When a camera frame
     *    is supplied, illuminated brake lights corroborate the geometry: lit
     *    brake lights make a smaller growth sufficient and raise confidence;
     *    growth with NO visible brake lights is demoted (it may just be own-speed
     *    closing), reducing false hard-braking alerts.
     */
    fun checkLeadHazards(
        lead: Detection?, distM: Float?, stopM: Float?, tMs: Long,
        frame: Bitmap? = null,
    ): Pair<List<RiskEventCandidate>, Detection?> {

        // Gate: only when a lead exists and is closer than the safe distance.
        if (lead == null || distM == null || stopM == null || distM >= stopM) {
            leadCentreHist.clear(); leadWidthHist.clear()
            swayCount = 0; swayDir = 0
            return emptyList<RiskEventCandidate>() to null
        }
        val out = mutableListOf<RiskEventCandidate>()
        val centre = (lead.left + lead.right) / 2f
        val wNorm = lead.right - lead.left

        // ---- Sway detection ----
        leadCentreHist.addLast(tMs to centre)
        while (leadCentreHist.isNotEmpty() && tMs - leadCentreHist.first().first > SWAY_WINDOW_MS)
            leadCentreHist.removeFirst()
        if (leadCentreHist.size >= 4) {
            val xs = leadCentreHist.map { it.second }
            val span = (xs.max() - xs.min())
            // A lane is ~the lead's own width in the image; 50% of side lane ~=
            // half a lane. Require a wide lateral span but NOT a monotonic shift
            // (which would be a genuine lane change).
            val net = abs(xs.last() - xs.first())
            if (span > SWAY_SPAN && net < span * 0.5f) {
                if (tMs - lastSwayEventMs > EVENT_INTERVAL_MS) {
                    lastSwayEventMs = tMs
                    out += RiskEventCandidate(RiskType.LEAD_VEHICLE_SWAYING,
                        Severity.WARNING, 0.5f,
                        "lead vehicle swaying within lane")
                }
            }
        }

        // ---- Hard braking (rapid box growth = rapid closing) ----
        leadWidthHist.addLast(tMs to wNorm)
        while (leadWidthHist.isNotEmpty() && tMs - leadWidthHist.first().first > BRAKE_WINDOW_MS)
            leadWidthHist.removeFirst()
        if (leadWidthHist.size >= 2) {
            val (t0, w0) = leadWidthHist.first()
            val dt = (tMs - t0) / 1000f
            if (dt > 0.15f && w0 > 0.02f) {
                val growthPerSec = (wNorm - w0) / w0 / dt    // fractional growth/s
                // Brake-light corroboration (only when a frame is supplied).
                //  - Lit brake lights: accept a smaller growth (they ARE braking)
                //    and report with high confidence + an explicit message.
                //  - No/weak brake lights: require the full growth threshold and
                //    report with lower confidence — this may be own-speed closing
                //    rather than the lead braking.
                val brakeStrength = frame?.let { brakeLightStrength(it, lead) } ?: -1f
                val lit = brakeStrength >= BRAKE_LIGHT_MIN
                val effThreshold =
                    if (lit) BRAKE_GROWTH_PER_SEC * BRAKE_LIT_GROWTH_FACTOR
                    else BRAKE_GROWTH_PER_SEC
                if (growthPerSec > effThreshold &&
                    tMs - lastBrakeEventMs > EVENT_INTERVAL_MS) {
                    lastBrakeEventMs = tMs
                    val (sev, conf, msg) = when {
                        lit -> Triple(Severity.CRITICAL, 0.85f,
                            "lead vehicle braking hard (brake lights on)")
                        brakeStrength < 0f -> Triple(Severity.CRITICAL, 0.7f,
                            "lead vehicle braking hard / closing fast")
                        else -> Triple(Severity.WARNING, 0.55f,
                            "closing on lead vehicle fast")
                    }
                    out += RiskEventCandidate(RiskType.LEAD_HARD_BRAKING, sev, conf, msg)
                }
            }
        }
        return out to (if (out.isNotEmpty()) lead else null)
    }

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
        // Class-specific physical width sharpens the monocular distance
        // (algorithm-review recommendation) versus a single average width.
        val widthM = when (lead.labelText.lowercase()) {
            "truck", "bus" -> 2.5f
            "motorcycle", "motorbike" -> 0.8f
            else -> VEHICLE_WIDTH_M
        }
        val distM = focalFactor * widthM / wNorm

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
        // Lead-hazard tuning.
        const val SWAY_WINDOW_MS = 4000L
        const val SWAY_SPAN = 0.10f          // ~50% of a side lane in image terms
        const val BRAKE_WINDOW_MS = 700L
        const val BRAKE_GROWTH_PER_SEC = 0.9f // box widening ~90%/s = rapid closing
        // Brake-light corroboration thresholds.
        const val BRAKE_LIGHT_MIN = 0.5f      // strength >= this counts as "lit"
        const val BRAKE_LIT_GROWTH_FACTOR = 0.55f // lit lights accept 55% of the growth bar
    }
}
