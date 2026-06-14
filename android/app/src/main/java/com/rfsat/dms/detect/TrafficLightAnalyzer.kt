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
        val state: State; var cx = 0f; var cy = 0f; var blobN = 0
        when {
            rN >= minBlob && rN >= aN -> { state = State.RED; cx = rX / rN; cy = rY / rN; blobN = rN }
            aN >= minBlob -> { state = State.AMBER; cx = aX / aN; cy = aY / aN; blobN = aN }
            gN >= minBlob -> { state = State.GREEN }
            else -> { state = State.NONE }
        }

        var det: Detection? = null
        if (state == State.RED || state == State.AMBER) {
            val sz = kotlin.math.sqrt(blobN.toFloat()) / w
            val nx = cx / w; val ny = cy / h
            det = Detection(
                if (state == State.RED) "RED" else "AMBER", 0.6f,
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
        val state = when (signal?.colour) {
            TrafficLightDetector.Colour.RED -> State.RED
            TrafficLightDetector.Colour.YELLOW -> State.AMBER
            else -> if (lights.any { it.colour == TrafficLightDetector.Colour.GREEN })
                State.GREEN else State.NONE
        }

        var det: Detection? = null
        if (signal != null && state != State.GREEN) {
            det = Detection(if (state == State.RED) "RED" else "AMBER", signal.score,
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
    }
}
