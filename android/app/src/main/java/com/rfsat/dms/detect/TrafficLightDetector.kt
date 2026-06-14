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
 * Learned traffic-light detector + colour classifier (YOLOv8-nano, 4 classes:
 * red, green, off, yellow). Replaces the colour-blob heuristic in
 * TrafficLightAnalyzer when traffic_light.tflite is present.
 *
 * Output format matches the YOLO family used elsewhere: float input
 * [1,640,640,3] in 0..1, single raw output [1, 4+numClasses, 8400] with
 * normalized cx,cy,w,h boxes; this class performs the decode + NMS.
 *
 * Brake-light rejection: car tail-lights are red and can fool a colour
 * detector. Detected RED lights that fall inside (or substantially overlap) a
 * vehicle box from the main object detector, AND sit in the lower/middle of the
 * frame where tail-lights appear, are discarded. Genuine signals are high and
 * not enclosed by a vehicle.
 */
class TrafficLightDetector(
    context: Context,
    assetName: String = "traffic_light.tflite",
    private val scoreThresh: Float = 0.40f,
    private val iouThresh: Float = 0.45f,
) {
    enum class Colour { RED, GREEN, OFF, YELLOW }
    data class Light(val colour: Colour, val score: Float,
                     val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun cx() = (left + right) / 2f
        fun cy() = (top + bottom) / 2f
    }

    private val inputSize = 640
    private val numClasses = 4
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

    /**
     * @param vehicleBoxes normalized vehicle detections from the object detector,
     *        used for brake-light rejection.
     * @return surviving lights (already NMS'd and brake-light filtered).
     */
    fun detect(frame: Bitmap, vehicleBoxes: List<Detection>): List<Light> {
        val (sx, sy, padX, padY) = fill(frame)
        interpreter.run(inBuf, output)
        val o = output[0]

        val cand = ArrayList<Light>(32)
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
            cand += Light(Colour.entries[bestC], bestS,
                l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        }
        return rejectBrakeLights(nms(cand), vehicleBoxes)
    }

    private fun rejectBrakeLights(lights: List<Light>, vehicles: List<Detection>): List<Light> =
        lights.filter { lt ->
            if (lt.colour != Colour.RED) return@filter true
            // A real signal sits high in the scene; tail-lights sit lower and
            // are enclosed by a vehicle box.
            val enclosed = vehicles.any { v ->
                lt.cx() in v.left..v.right && lt.cy() in v.top..v.bottom
            }
            val low = lt.cy() > 0.45f
            !(enclosed && low)
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

    private fun nms(dets: List<Light>): List<Light> {
        val sorted = dets.sortedByDescending { it.score }.toMutableList()
        val keep = ArrayList<Light>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0); keep += best
            sorted.removeAll { iou(best, it) > iouThresh }
        }
        return keep
    }

    private fun iou(a: Light, b: Light): Float {
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
}
