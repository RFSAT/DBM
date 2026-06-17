package com.rfsat.dms.detect

import android.content.Context
import android.graphics.Bitmap
import com.rfsat.dms.Detection
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * EU traffic-sign detector (YOLO-format, 27 classes) trained on the Mapillary
 * Traffic Sign Dataset. Unlike the GTSRB classifier, this is a one-stage
 * detector: it both localises and classifies signs, so no region proposer is
 * needed. Output is the YOLO tensor [1, 4+27, 8400] with normalized cx,cy,w,h.
 *
 * Used when sign_eu.tflite is present; otherwise the app falls back to the
 * GTSRB classifier + region proposer.
 */
class SignDetector(
    context: Context,
    assetName: String = "sign_eu.tflite",
    private val scoreThresh: Float = 0.45f,
    private val iouThresh: Float = 0.45f,
) {
    private val inputSize = 640
    private val numClasses = NAMES.size           // 21
    private val numAnchors = 8400

    private var nnApi: NnApiDelegate? = null
    private val interpreter: Interpreter

    init {
        val model = loadAsset(context, assetName)
        interpreter = try {
            nnApi = NnApiDelegate()
            Interpreter(model, Interpreter.Options().apply { addDelegate(nnApi) })
        } catch (_: Throwable) {
            nnApi?.close(); nnApi = null
            Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })
        }
    }

    private val inBuf: ByteBuffer = ByteBuffer
        .allocateDirect(inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { Array(4 + numClasses) { FloatArray(numAnchors) } }

    data class SignHit(val classId: Int, val name: String, val score: Float,
                       val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun detect(frame: Bitmap): List<SignHit> {
        val (sx, sy, padX, padY) = fill(frame)
        interpreter.run(inBuf, output)
        val o = output[0]
        val cand = ArrayList<SignHit>(32)
        for (a in 0 until numAnchors) {
            var bestC = 0; var bestS = o[4][a]
            var c = 1
            while (c < numClasses) { val s = o[4 + c][a]; if (s > bestS) { bestS = s; bestC = c }; c++ }
            if (bestS < scoreThresh) continue
            val cx = o[0][a] * inputSize; val cy = o[1][a] * inputSize
            val bw = o[2][a] * inputSize; val bh = o[3][a] * inputSize
            val l = (((cx - bw / 2f) - padX) / sx) / frame.width
            val t = (((cy - bh / 2f) - padY) / sy) / frame.height
            val r = (((cx + bw / 2f) - padX) / sx) / frame.width
            val b = (((cy + bh / 2f) - padY) / sy) / frame.height
            cand += SignHit(bestC, NAMES[bestC], bestS,
                l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        }
        return nms(cand)
    }

    private data class Fit(val sx: Float, val sy: Float, val padX: Float, val padY: Float)
    private fun fill(frame: Bitmap): Fit {
        val scale = min(inputSize / frame.width.toFloat(), inputSize / frame.height.toFloat())
        val newW = (frame.width * scale).toInt(); val newH = (frame.height * scale).toInt()
        val padX = (inputSize - newW) / 2f; val padY = (inputSize - newH) / 2f
        val scaled = Bitmap.createScaledBitmap(frame, newW, newH, true)
        val canvas = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(canvas).apply {
            drawColor(android.graphics.Color.rgb(114, 114, 114))
            drawBitmap(scaled, padX, padY, null)
        }
        val px = IntArray(inputSize * inputSize)
        canvas.getPixels(px, 0, inputSize, 0, 0, inputSize, inputSize)
        inBuf.rewind()
        for (p in px) {
            inBuf.putFloat((p shr 16 and 0xFF) / 255f)
            inBuf.putFloat((p shr 8 and 0xFF) / 255f)
            inBuf.putFloat((p and 0xFF) / 255f)
        }
        scaled.recycle(); canvas.recycle()
        return Fit(scale, scale, padX, padY)
    }

    private fun nms(dets: List<SignHit>): List<SignHit> {
        val sorted = dets.sortedByDescending { it.score }.toMutableList()
        val keep = ArrayList<SignHit>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0); keep += best
            sorted.removeAll { iou(best, it) > iouThresh }
        }
        return keep
    }

    private fun iou(a: SignHit, b: SignHit): Float {
        val l = max(a.left, b.left); val t = max(a.top, b.top)
        val r = min(a.right, b.right); val bo = min(a.bottom, b.bottom)
        val inter = max(0f, r - l) * max(0f, bo - t)
        val ua = (a.right - a.left) * (a.bottom - a.top)
        val ub = (b.right - b.left) * (b.bottom - b.top)
        val u = ua + ub - inter
        return if (u <= 0f) 0f else inter / u
    }

    private fun loadAsset(context: Context, name: String): ByteBuffer {
        context.assets.openFd(name).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).channel.use { ch ->
                return ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY,
                    fd.startOffset, fd.declaredLength)
            }
        }
    }

    fun close() { interpreter.close(); nnApi?.close() }

    companion object {
        // Class order MUST match labels.txt (21-class Mapillary model).
        // Index: 0 no_left_turn 1 no_right_turn 2 no_u_turn 3 no_straight
        // 4 no_turns 5 no_overtaking 6 no_entry 7 stop 8 yield 9 speed_limit
        // 10 end_limit 11 keep_right 12 keep_left 13 roundabout 14 ahead_only
        // 15 warn_pedestrians 16 warn_children 17 warn_roadworks
        // 18 warn_curve_left 19 warn_curve_right 20 warn_slippery
        val NAMES = arrayOf(
            "No left turn", "No right turn", "No U-turn", "No straight",
            "No turns", "No overtaking", "No entry", "Stop", "Yield",
            "Speed limit", "End of limit", "Keep right", "Keep left",
            "Roundabout", "Ahead only",
            "Pedestrians", "Children", "Roadworks", "Curve left", "Curve right",
            "Slippery road")

        // The single speed-limit class — the NUMBER is read by OCR at runtime,
        // not encoded in the class. SPEED_LIMITS is therefore null everywhere;
        // SPEED_LIMIT_ID marks the class that triggers OCR + tracking.
        const val SPEED_LIMIT_ID = 9
        const val END_LIMIT_ID = 10

        // Category for colour-coding (warnings are the last six classes).
        fun category(id: Int): String = if (id in 15..20) "Warning" else "Regulatory"

        // Turn-restriction class ids for illegal-turn detection.
        const val NO_LEFT_TURN = 0
        const val NO_RIGHT_TURN = 1
        const val NO_U_TURN = 2
        const val NO_STRAIGHT = 3
        const val NO_TURNS = 4
        const val NO_ENTRY = 6
        const val AHEAD_ONLY = 14
    }
}
