package com.rfsat.dms.detect

import android.graphics.Bitmap
import com.rfsat.dms.Detection
import com.rfsat.dms.RiskEventCandidate
import com.rfsat.dms.RiskType
import com.rfsat.dms.Severity

/**
 * Traffic-light state detection and red/amber-crossing logic.
 *
 * Detection: when traffic_light.tflite is present, a learned YOLOv8 detector
 * (TrafficLightDetector) locates and colour-classifies signals and rejects
 * vehicle brake lights. Otherwise a lightweight colour-blob heuristic scans the
 * upper ROI for compact, highly saturated red/amber/green regions. Both feed
 * the same crossing state machine below.
 *
 * Crossing logic: a violation is judged at the moment the vehicle passes under
 * the signal — approximated by the light blob leaving the top of the frame
 * (its vertical position rising past EXIT_Y) or growing then disappearing —
 * while moving (speed > MIN_SPEED) and while the last confident state was red
 * or amber. This avoids flagging a normal stop at a red light: a crossing
 * requires the signal to have been red/amber AND the vehicle still moving as
 * it passes beneath.
 *
 * Confidence is moderate: phone-camera colour and the absence of map/junction
 * context mean this is an assistance-grade detector. It is corroborated by
 * motion (the vehicle must be moving) and is subject to the same cross-checks
 * and rate limiting as other events.
 *
 * The learned detector and the heuristic share the crossing interface, so the
 * application works with or without the model asset.
 */
class TrafficLightAnalyzer(context: android.content.Context? = null) {

    enum class State { NONE, RED, AMBER, GREEN }

    // Learned detector used when traffic_light.tflite is present; otherwise the
    // colour-blob heuristic below is used.
    private val detector: TrafficLightDetector? = context?.let { ctx ->
        if (runCatching { ctx.assets.open("traffic_light.tflite").close() }.isSuccess)
            runCatching { TrafficLightDetector(ctx) }.getOrNull() else null
    }
    val usingModel get() = detector != null

    private var lastState = State.NONE
    private var lastStrongState = State.NONE
    private var lastBlobY = 0f
    private var lastBlobSize = 0f
    private var lastEventMs = 0L

    // --- Temporal smoothing -------------------------------------------------
    // Raw per-frame colour is noisy: a glint can read RED for one frame, a
    // bloomed green can read NONE. Acting on a single frame causes flickering
    // overlays and, worse, false RED_LIGHT_CROSSING events. We therefore confirm
    // a state only after it has been seen on several consecutive frames, and we
    // HOLD the last confirmed state across a brief dropout so a momentary miss
    // does not look like the signal vanished. The crossing state machine runs on
    // the CONFIRMED state, not the raw one.
    private var rawCandidate = State.NONE     // the state currently accumulating
    private var rawStreak = 0                 // consecutive frames of rawCandidate
    private var confirmedState = State.NONE    // smoothed state the rest of the code uses
    private var confirmedAtMs = 0L            // when confirmedState last refreshed
    private var lastSeenMs = 0L               // last frame the confirmed state was actually observed

    /**
     * Fold a raw per-frame observation into the confirmed state.
     *  - A new colour must persist for CONFIRM_FRAMES consecutive frames before
     *    it replaces the confirmed state (debounce).
     *  - When raw == NONE we do NOT immediately clear: the confirmed state is
     *    held for HOLD_MS so a one- or two-frame dropout is ignored. Only after
     *    the hold elapses with no re-observation does it fall to NONE.
     * Returns the confirmed state to drive overlays and crossing logic.
     */
    private fun smooth(raw: State, tMs: Long): State {
        if (raw != State.NONE) lastSeenMs = tMs
        // Accumulate a streak for whatever raw colour we are seeing.
        if (raw == rawCandidate) rawStreak++ else { rawCandidate = raw; rawStreak = 1 }

        if (raw != State.NONE && raw != confirmedState && rawStreak >= CONFIRM_FRAMES) {
            // A different colour has persisted long enough — accept it.
            confirmedState = raw
            confirmedAtMs = tMs
        } else if (raw == confirmedState && raw != State.NONE) {
            // Re-confirmed same colour; refresh timestamp.
            confirmedAtMs = tMs
        } else if (raw == State.NONE && confirmedState != State.NONE) {
            // Possible dropout: hold the confirmed state until HOLD_MS passes
            // with no re-observation, then release to NONE.
            if (tMs - lastSeenMs > HOLD_MS) confirmedState = State.NONE
        }
        return confirmedState
    }


    /** @return (overlay detection of the light if any, crossing event or null) */
    fun analyze(frame: Bitmap, speedKmh: Int, tMs: Long,
                vehicleBoxes: List<Detection> = emptyList()):
            Pair<Detection?, RiskEventCandidate?> {
        // Preferred path: learned detector + colour classifier.
        detector?.let { det ->
            val lights = det.detect(frame, vehicleBoxes)
            return decideFromModel(lights, speedKmh, tMs, frame)
        }
        val w = frame.width; val h = frame.height
        val roiH = (h * 0.45f).toInt()           // lights are high in the scene
        val px = IntArray(w * roiH)
        frame.getPixels(px, 0, w, 0, 0, w, roiH)

        // Accumulate saturated red / amber / green pixels and their centroids.
        var rN = 0; var rX = 0f; var rY = 0f
        var aN = 0; var aX = 0f; var aY = 0f
        var gN = 0
        var i = 0
        while (i < px.size) {
            val c = px[i]
            val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val bl = c and 0xFF
            val mx = maxOf(r, g, bl); val mn = minOf(r, g, bl)
            val sat = if (mx == 0) 0 else (mx - mn) * 255 / mx
            if (mx > 160 && sat > 90) {
                val x = (i % w).toFloat(); val y = (i / w).toFloat()
                when {
                    r > 150 && g < 110 && bl < 110 -> { rN++; rX += x; rY += y }
                    r > 170 && g > 120 && bl < 110 -> { aN++; aX += x; aY += y }
                    g > 150 && r < 120 && bl < 130 -> { gN++ }
                }
            }
            i += 2   // subsample for speed
        }

        val minBlob = (w * roiH) / 4000          // scale threshold to frame size
        val rawState: State; var cx = 0f; var cy = 0f; var blobN = 0
        when {
            rN >= minBlob && rN >= aN -> { rawState = State.RED; cx = rX / rN; cy = rY / rN; blobN = rN }
            aN >= minBlob -> { rawState = State.AMBER; cx = aX / aN; cy = aY / aN; blobN = aN }
            gN >= minBlob -> { rawState = State.GREEN }
            else -> { rawState = State.NONE }
        }
        // Smooth across frames before the crossing logic acts on it.
        val state = smooth(rawState, tMs)

        var det: Detection? = null
        if (rawState == State.RED || rawState == State.AMBER) {
            val sz = kotlin.math.sqrt(blobN.toFloat()) / w
            val nx = cx / w; val ny = cy / h
            det = Detection(
                if (rawState == State.RED) "RED" else "AMBER", 0.6f,
                (nx - sz).coerceIn(0f, 1f), (ny - sz).coerceIn(0f, 1f),
                (nx + sz).coerceIn(0f, 1f), (ny + sz).coerceIn(0f, 1f),
                risky = true)
            lastStrongState = state
            lastBlobY = ny; lastBlobSize = sz
        }

        // Crossing: previously saw red/amber, the blob has just left the top of
        // frame (passed overhead) and the vehicle is moving.
        var event: RiskEventCandidate? = null
        val disappeared = (lastState == State.RED || lastState == State.AMBER) &&
                state == State.NONE && lastBlobY < EXIT_Y
        if (disappeared && speedKmh >= MIN_SPEED_KMH &&
            tMs - lastEventMs > EVENT_INTERVAL_MS) {
            lastEventMs = tMs
            event = when (lastStrongState) {
                State.RED -> RiskEventCandidate(RiskType.RED_LIGHT_CROSSING,
                    Severity.CRITICAL, 0.6f, "passed under a red signal at $speedKmh km/h")
                State.AMBER -> RiskEventCandidate(RiskType.AMBER_LIGHT_CROSSING,
                    Severity.WARNING, 0.55f, "passed on amber at $speedKmh km/h")
                else -> null
            }
        }
        lastState = state
        return det to event
    }

    /** Crossing decision using learned-detector output. Same state machine as
     *  the heuristic path: remember red/amber, fire when the signal passes
     *  overhead while the vehicle is moving. */
    private fun decideFromModel(
        lights: List<TrafficLightDetector.Light>, speedKmh: Int, tMs: Long, frame: Bitmap
    ): Pair<Detection?, RiskEventCandidate?> {
        // Choose the most relevant signal: prefer RED, then AMBER, highest score,
        // favouring lights high in the scene.
        val signal = lights.filter { it.colour == TrafficLightDetector.Colour.RED ||
                                     it.colour == TrafficLightDetector.Colour.YELLOW }
            .maxByOrNull { it.score * (1f - it.cy()) }
        val rawState = when (signal?.colour) {
            TrafficLightDetector.Colour.RED -> State.RED
            TrafficLightDetector.Colour.YELLOW -> State.AMBER
            else -> if (lights.any { it.colour == TrafficLightDetector.Colour.GREEN })
                State.GREEN else State.NONE
        }
        // Smooth across frames before the crossing logic acts on it.
        val state = smooth(rawState, tMs)

        var det: Detection? = null
        if (signal != null && rawState != State.GREEN && rawState != State.NONE) {
            det = Detection(if (rawState == State.RED) "RED" else "AMBER", signal.score,
                signal.left, signal.top, signal.right, signal.bottom, risky = true)
            lastStrongState = state
            lastBlobY = signal.cy()
        }

        var event: RiskEventCandidate? = null
        val disappeared = (lastState == State.RED || lastState == State.AMBER) &&
                state == State.NONE && lastBlobY < EXIT_Y
        if (disappeared && speedKmh >= MIN_SPEED_KMH &&
            tMs - lastEventMs > EVENT_INTERVAL_MS) {
            lastEventMs = tMs
            event = when (lastStrongState) {
                State.RED -> RiskEventCandidate(RiskType.RED_LIGHT_CROSSING,
                    Severity.CRITICAL, signal?.score ?: 0.8f,
                    "passed under a red signal at $speedKmh km/h")
                State.AMBER -> RiskEventCandidate(RiskType.AMBER_LIGHT_CROSSING,
                    Severity.WARNING, signal?.score ?: 0.7f,
                    "passed on amber at $speedKmh km/h")
                else -> null
            }
        }
        lastState = state
        return det to event
    }

    fun close() = detector?.close()

    companion object {
        const val EXIT_Y = 0.25f          // blob near top before disappearing
        const val MIN_SPEED_KMH = 10
        const val EVENT_INTERVAL_MS = 8000L
        // Temporal smoothing: a new colour must hold this many consecutive frames
        // to be confirmed; a confirmed colour survives dropouts for HOLD_MS.
        const val CONFIRM_FRAMES = 3
        const val HOLD_MS = 600L
    }
}
