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
 * REQUIRED GAP — configurable, because the right criterion is not universal:
 *
 *   TIME (default)  gap = v * timeGapSec, the ordinary two-second rule.
 *   DISTANCE        a constant metre figure the driver sets.
 *   STOPPING        v * t_react + v² / (2a), scaled by [factor].
 *
 * TIME is the default, and STOPPING is NOT, because the full stopping distance
 * is what you need to halt before a STATIONARY obstacle: it ignores that the
 * vehicle ahead is also moving and also travels its own braking distance. Used
 * as a following criterion it demands 87 m at 100 km/h and 119 m at 120 km/h —
 * a 3.1 s and 3.6 s gap — so it flagged plainly safe motorway gaps as unsafe.
 * The mode is kept for drivers who want that margin deliberately.
 *
 * LEAD SELECTION. Candidates are vehicle-class detections near the image centre;
 * of those, the one estimated NEAREST becomes the lead (by distance, not by box
 * width — a distant truck has a wider box than a near car). A fixed image-centre
 * window alone is far too permissive at range: ±0.15 of frame width spans ±3.3 m
 * at 20 m but ±10 m at 60 m, which admits two lanes either side and oncoming
 * traffic. So own-lane membership is also tested in METRES, from the same
 * pinhole geometry, with an allowance for mount yaw that grows with distance.
 *
 * Events require the condition to persist PERSIST_MS to ride out box jitter.
 */
class FollowingDistanceMonitor {

    /** How the required gap is derived. */
    enum class GapMode { TIME, DISTANCE, STOPPING }

    /** Criterion for the required gap; see the class comment. */
    @Volatile var gapMode = GapMode.TIME

    /** Headway in seconds for [GapMode.TIME]. */
    @Volatile var timeGapSec = DEFAULT_TIME_GAP_S

    /** Constant gap in metres for [GapMode.DISTANCE]. */
    @Volatile var fixedGapM = DEFAULT_FIXED_GAP_M

    /** Stopping-distance scale factor from Settings (1.0 = textbook dry road).
     *  Applies to [GapMode.STOPPING] only. */
    @Volatile var factor = 1.0f

    /**
     * The gap the driver should be keeping at [speedKmh], in metres. One place,
     * so the warning, the hazard gate and the plate-reading gate cannot drift
     * apart (they previously computed it three times, one of them ignoring the
     * user's factor).
     */
    fun requiredGapM(speedKmh: Int): Float {
        val v = speedKmh / 3.6f
        return when (gapMode) {
            GapMode.TIME -> maxOf(v * timeGapSec, MIN_GAP_M)
            GapMode.DISTANCE -> fixedGapM
            GapMode.STOPPING -> (v * REACTION_S + v * v / (2f * DECEL_MS2)) * factor
        }
    }

    /** Physical width assumed for a detection's class, which sharpens the
     *  monocular distance versus one average width for everything. */
    private fun physicalWidthM(d: Detection): Float = when (d.labelText.lowercase()) {
        "truck", "bus" -> 2.5f
        "motorcycle", "motorbike" -> 0.8f
        else -> VEHICLE_WIDTH_M
    }

    /** Estimated distance to a detection in metres, or null if its box is too
     *  small for the estimate to mean anything. */
    fun distanceTo(d: Detection): Float? {
        val wNorm = d.right - d.left
        if (wNorm < MIN_BOX_W) return null
        return focalFactor * physicalWidthM(d) / wNorm
    }

    /** Image x of our own lane's centre. Taken from the same mount calibration
     *  the lane detector uses (lane_center), because a phone clamped off to one
     *  side puts the road centre away from 0.5 and would otherwise bias every
     *  lane test below. */
    @Volatile var centreOffset = 0f

    /** Own lane centre in normalised image x. */
    private fun laneCentreX() = (0.5f + centreOffset).coerceIn(0.2f, 0.8f)

    /**
     * Lateral budget in metres for "in our lane" at [distM]. Half a lane plus a
     * small residual-yaw term, widened on a bend by the offset the road itself
     * produces: a lead 60 m ahead on a 500 m-radius curve sits d²/(2R) ≈ 3.6 m
     * to one side, so a fixed budget would reject the genuine lead on every
     * motorway curve. The curve term uses the MAGNITUDE of the yaw rate only —
     * YawRateMonitor auto-detects its axis, so its sign carries no reliable
     * direction and the budget widens symmetrically instead of shifting.
     */
    private fun laneBudgetM(distM: Float, yawRateDps: Float, speedKmh: Int): Float {
        var budget = LANE_HALF_WIDTH_M + distM * YAW_TOL_TAN
        val v = speedKmh / 3.6f
        if (v > 3f && abs(yawRateDps) > CURVE_MIN_DPS) {
            val omega = abs(Math.toRadians(yawRateDps.toDouble()).toFloat())   // rad/s
            budget += distM * distM * omega / (2f * v)
        }
        return budget
    }

    /**
     * Whether a detection at [distM] plausibly sits in OUR lane. The lateral
     * offset follows from the same pinhole model as the distance:
     *
     *   lateral_m = (centreX - laneCentreX) * frame_w_px / f_px * d
     *             = (centreX - laneCentreX) / FOCAL_FACTOR * d
     *
     * This is what the old fixed image-centre window could not do: ±0.15 of
     * frame width is ±3.3 m at 20 m but ±10 m at 60 m, so at range it accepted
     * two lanes either side — and oncoming traffic — as "the vehicle ahead".
     */
    private fun inOwnLane(
        d: Detection, distM: Float, yawRateDps: Float, speedKmh: Int,
    ): Boolean {
        val centre = (d.left + d.right) / 2f
        val lateralM = abs(centre - laneCentreX()) / focalFactor * distM
        return lateralM <= laneBudgetM(distM, yawRateDps, speedKmh)
    }

    /**
     * The vehicle being followed: the nearest own-lane vehicle-class detection,
     * or null. Public so every caller gates on the SAME vehicle — the service
     * previously re-implemented this filter twice with its own rules.
     *
     * Nearest by ESTIMATED DISTANCE, not by box width: a truck 50 m away has a
     * wider box than a car at 30 m, and the old widest-box rule picked the truck.
     *
     * [yawRateDps] and [speedKmh] are optional and only widen the lane test on a
     * bend; omitting them gives the straight-road behaviour.
     */
    fun selectLead(
        detections: List<Detection>, yawRateDps: Float = 0f, speedKmh: Int = 0,
    ): Detection? {
        val lo = laneCentreX() - CENTRE_WINDOW
        val hi = laneCentreX() + CENTRE_WINDOW
        return detections.asSequence()
            .filter { it.labelText in VEHICLES }
            .filter { ((it.left + it.right) / 2f) in lo..hi }
            .mapNotNull { det -> distanceTo(det)?.let { det to it } }
            .filter { (det, dist) -> inOwnLane(det, dist, yawRateDps, speedKmh) }
            .minByOrNull { it.second }
            ?.first
    }

    /** Focal factor f_px/frame-width; refined at runtime when a vehicle is
     *  tracked over a known closing speed (own speed while the lead is roughly
     *  stationary in-frame gives a distance-rate cross-check). Bounded so a
     *  noisy estimate cannot wildly distort distances. */
    @Volatile var focalFactor = FOCAL_FACTOR
    private val focalSamples = ArrayDeque<Float>()

    /**
     * Feed when own speed is known and a single lead vehicle is tracked: the
     * change in estimated distance over dt should match own speed if the lead is
     * slow/stopped; the ratio refines the focal factor.
     *
     * The validity condition — that the lead is standing still — cannot be
     * verified from one camera, so it is enforced by REJECTING samples that
     * disagree with it. A lead moving at a fraction f of own speed closes at
     * v(1-f) and yields k = focalFactor/(1-f): f = 0.37 alone would hand us a
     * 1.6x correction, and since most traffic one closes on is moving rather
     * than parked, a wide acceptance window drifts the focal factor upward and
     * makes every distance read long — warnings then arrive late. So a sample
     * must close within [CLOSING_TOL] of own speed, and the result must stay
     * within [FOCAL_MIN]..[FOCAL_MAX] of the nominal optics.
     */
    fun calibrateFocal(prevWNorm: Float, curWNorm: Float, dtSec: Float, ownSpeedKmh: Int) {
        if (dtSec < 0.2f || ownSpeedKmh < 25 || prevWNorm < 0.02f || curWNorm < 0.02f) return
        val dPrev = focalFactor * VEHICLE_WIDTH_M / prevWNorm
        val dCur = focalFactor * VEHICLE_WIDTH_M / curWNorm
        val measuredClosing = (dPrev - dCur) / dtSec            // m/s
        val expected = ownSpeedKmh / 3.6f
        if (measuredClosing <= 0.5f) return
        // Only a nearly-stationary lead can calibrate: reject anything whose
        // closing rate is not close to our own speed.
        if (abs(measuredClosing - expected) > CLOSING_TOL * expected) return
        val k = focalFactor * (expected / measuredClosing)
        if (k.isFinite() && k in FOCAL_MIN..FOCAL_MAX) {
            focalSamples.addLast(k)
            if (focalSamples.size > 40) focalSamples.removeFirst()
            if (focalSamples.size >= 12) focalFactor = focalSamples.sorted()[focalSamples.size / 2]
        }
    }

    private var tooCloseSinceMs = 0L
    private var lastEventMs = 0L

    /** Recent distance estimates (t, metres) for the median below. */
    private val distHist = ArrayDeque<Pair<Long, Float>>()

    /**
     * Median of the last [DIST_SMOOTH_MS] of distance estimates. Box width is
     * jittery, and the jitter matters most exactly where it hurts: a vehicle at
     * 60 m is only ~19 px wide in a 720-px frame, so ±2 px of box noise is ±10 %
     * of the width and so ±10 % of the distance. A median rides that out without
     * the lag of an average over a longer window; PERSIST_MS still governs how
     * long the condition must hold before an event.
     */
    private fun smoothedDistance(raw: Float, tMs: Long): Float {
        distHist.addLast(tMs to raw)
        while (distHist.isNotEmpty() && tMs - distHist.first().first > DIST_SMOOTH_MS)
            distHist.removeFirst()
        val xs = distHist.map { it.second }.sorted()
        return xs[xs.size / 2]
    }

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
        yawRateDps: Float = 0f,
    ): Pair<RiskEventCandidate?, Float?> {
        if (speedKmh < MIN_SPEED_KMH) {
            tooCloseSinceMs = 0; distHist.clear(); return null to null
        }
        val lead = selectLead(detections, yawRateDps, speedKmh) ?: run {
            tooCloseSinceMs = 0; distHist.clear(); return null to null
        }
        val raw = distanceTo(lead) ?: run {
            tooCloseSinceMs = 0; distHist.clear(); return null to null
        }
        val distM = smoothedDistance(raw, tMs)

        val needM = requiredGapM(speedKmh)

        if (distM >= needM) { tooCloseSinceMs = 0; return null to distM }

        if (tooCloseSinceMs == 0L) { tooCloseSinceMs = tMs; return null to distM }
        if (tMs - tooCloseSinceMs < PERSIST_MS) return null to distM
        if (tMs - lastEventMs < EVENT_INTERVAL_MS) return null to distM
        lastEventMs = tMs

        val severe = distM < needM * 0.6f
        val v = speedKmh / 3.6f
        // Both the measured and the required gap, in metres AND seconds, so an
        // evidence record can be judged after the fact without re-deriving the
        // criterion that produced it.
        val haveSec = if (v > 0.1f) distM / v else 0f
        val why = when (gapMode) {
            GapMode.TIME -> "%.1f s rule".format(timeGapSec)
            GapMode.DISTANCE -> "fixed %.0f m".format(fixedGapM)
            GapMode.STOPPING -> "stopping distance × %.0f%%".format(factor * 100f)
        }
        return RiskEventCandidate(
            RiskType.UNSAFE_FOLLOWING_DISTANCE,
            if (severe) Severity.CRITICAL else Severity.WARNING,
            0.6f,
            "≈%.0f m (%.1f s) gap, ≥%.0f m needed at %d km/h — %s"
                .format(distM, haveSec, needM, speedKmh, why)
        ) to distM
    }

    companion object {
        val VEHICLES = setOf("car", "truck", "bus", "motorcycle")
        const val VEHICLE_WIDTH_M = 1.8f
        const val FOCAL_FACTOR = 0.9f      // f_px / frame-width
        // Self-calibration guards (see calibrateFocal).
        /** How far a sample's closing rate may sit from own speed to count. */
        const val CLOSING_TOL = 0.25f
        const val FOCAL_MIN = 0.70f        // ~70 deg horizontal FOV
        const val FOCAL_MAX = 1.20f        // ~45 deg horizontal FOV
        const val REACTION_S = 1.0f
        const val DECEL_MS2 = 6.5f
        const val MIN_SPEED_KMH = 20

        // --- required-gap defaults and bounds (mirrored by the Settings UI) ---
        const val DEFAULT_TIME_GAP_S = 2.0f    // the ordinary two-second rule
        const val MIN_TIME_GAP_S = 1.0f
        const val MAX_TIME_GAP_S = 4.0f
        const val DEFAULT_FIXED_GAP_M = 30f
        const val MIN_FIXED_GAP_M = 10f
        const val MAX_FIXED_GAP_M = 120f
        /** Floor for the time-gap mode, so a crawl still needs a car's length. */
        const val MIN_GAP_M = 6f

        // --- lead selection ---
        /** Image-centre pre-filter: cheap, and bounds mount error at close range. */
        const val CENTRE_WINDOW = 0.15f
        /** Own-lane budget in metres: a vehicle in our 3.5 m lane sits within
         *  ~1.7 m of its centre even at the extremes, while one in the next lane
         *  is at least ~1.8 m off, so this separates them. */
        const val LANE_HALF_WIDTH_M = 1.9f
        /** Residual lateral budget per metre of distance (~0.9° of mount yaw).
         *  Deliberately small: anything larger re-admits the adjacent lane at
         *  range, which is what this test exists to exclude. A mount yawed more
         *  than that should be calibrated (Settings → lane centring). */
        const val YAW_TOL_TAN = 0.015f
        /** Yaw rate above which the lane budget is widened for curvature. */
        const val CURVE_MIN_DPS = 1.5f
        /** Boxes narrower than this make the distance estimate meaningless. */
        const val MIN_BOX_W = 0.02f
        /** Window for the median distance estimate. */
        const val DIST_SMOOTH_MS = 700L
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
