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
 * YOLO26-nano object detector using the raw TFLite Interpreter.
 *
 * The Ultralytics YOLO26 TFLite export is NOT in the SSD/TFLite-Task format:
 * its single output tensor is [1, 84, 8400] (84 = 4 box coords + 80 COCO class
 * scores; 8400 anchor points), boxes are normalized cx,cy,w,h in [0,1], the
 * model is NMS-free, and the input is float32 [1,640,640,3] in 0..1. This
 * decoder therefore does the box decode, score thresholding, class selection
 * and Non-Maximum Suppression that the Task library would otherwise provide.
 *
 * Input letterboxing preserves aspect ratio; output boxes are mapped back to
 * normalized full-frame coordinates so the rest of the pipeline is unchanged.
 */
class YoloDetector(
    context: Context,
    assetName: String = "yolo26n.tflite",
    private val scoreThresh: Float = 0.35f,
    private val iouThresh: Float = 0.45f,
) {
    private val inputSize = 640
    private val numClasses = 80
    private val numAnchors = 8400

    private var nnApi: NnApiDelegate? = null
    private val interpreter: Interpreter

    init {
        val model = loadAsset(context, assetName)
        val opts = Interpreter.Options().apply {
            // NNAPI acceleration with graceful CPU fall-back.
            try {
                nnApi = NnApiDelegate(); addDelegate(nnApi)
            } catch (_: Throwable) { nnApi = null; setNumThreads(3) }
        }
        interpreter = try {
            Interpreter(model, opts)
        } catch (_: Throwable) {
            // delegate unsupported on this device -> plain CPU interpreter
            nnApi?.close(); nnApi = null
            Interpreter(model, Interpreter.Options().apply { setNumThreads(3) })
        }
    }

    private val inBuf: ByteBuffer = ByteBuffer
        .allocateDirect(inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { Array(4 + numClasses) { FloatArray(numAnchors) } }

    /** COCO id -> label, limited to the classes DBM cares about. */
    private val labelOf = mapOf(
        0 to "person", 1 to "bicycle", 2 to "car", 3 to "motorcycle",
        5 to "bus", 7 to "truck", 9 to "traffic light", 11 to "stop sign"
    )

    fun detect(frame: Bitmap): List<Detection> {
        // Letterbox into a square input, recording scale + padding for un-mapping.
        val (sx, sy, padX, padY) = fill(frame)
        interpreter.run(inBuf, output)
        val o = output[0]

        val cand = ArrayList<Detection>(64)
        for (a in 0 until numAnchors) {
            // best class for this anchor
            var bestC = -1; var bestS = 0f
            var c = 0
            while (c < numClasses) {
                val s = o[4 + c][a]
                if (s > bestS) { bestS = s; bestC = c }
                c++
            }
            if (bestS < scoreThresh) continue
            val label = labelOf[bestC] ?: continue
            // decode box (normalized to 640 letterboxed space) -> frame-normalized
            val cx = o[0][a] * inputSize; val cy = o[1][a] * inputSize
            val bw = o[2][a] * inputSize; val bh = o[3][a] * inputSize
            val l = ((cx - bw / 2f) - padX) / sx
            val tp = ((cy - bh / 2f) - padY) / sy
            val r = ((cx + bw / 2f) - padX) / sx
            val bt = ((cy + bh / 2f) - padY) / sy
            cand += Detection(label, bestS,
                (l / frame.width).coerceIn(0f, 1f), (tp / frame.height).coerceIn(0f, 1f),
                (r / frame.width).coerceIn(0f, 1f), (bt / frame.height).coerceIn(0f, 1f))
        }
        return nms(cand)
    }

    /** Letterbox the bitmap into inBuf; returns (scaleX, scaleY, padX, padY) in
     *  input-pixel space relative to the original frame pixels. */
    private data class Fit(val sx: Float, val sy: Float, val padX: Float, val padY: Float)
    private fun fill(frame: Bitmap): Fit {
        val scale = min(inputSize / frame.width.toFloat(), inputSize / frame.height.toFloat())
        val newW = (frame.width * scale).toInt(); val newH = (frame.height * scale).toInt()
        val padX = (inputSize - newW) / 2f; val padY = (inputSize - newH) / 2f
        val scaled = Bitmap.createScaledBitmap(frame, newW, newH, true)
        val canvas = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(canvas).apply {
            drawColor(android.graphics.Color.rgb(114, 114, 114))   // YOLO grey pad
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

    private fun nms(dets: List<Detection>): List<Detection> {
        val sorted = dets.sortedByDescending { it.score }.toMutableList()
        val keep = ArrayList<Detection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0); keep += best
            sorted.removeAll { iou(best, it) > iouThresh && it.labelText == best.labelText }
        }
        return keep
    }

    private fun iou(a: Detection, b: Detection): Float {
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
