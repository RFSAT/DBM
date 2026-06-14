package com.rfsat.dms.detect

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GTSRB traffic-sign classifier (43 classes), the recognition stage of a
 * two-stage sign pipeline. Localisation (finding sign-shaped regions) is done
 * upstream; each candidate crop is classified here.
 *
 * Model: MobileNet-based GTSRB classifier, input [1,224,224,3] float 0..1,
 * output [1,43] class scores. Used when gtsrb_sign.tflite is present in assets.
 *
 * The 43 GTSRB classes are mapped to (a) a display name, (b) a regulatory
 * category, and (c) where applicable, a posted speed-limit value and a
 * RiskType for regulatory signs that imply a compliance event.
 */
class SignClassifier(context: Context, assetName: String = "gtsrb_sign.tflite") {

    private val size = 224
    private val numClasses = 43
    private val interpreter: Interpreter = Interpreter(
        loadAsset(context, assetName),
        Interpreter.Options().apply { setNumThreads(2) })

    private val inBuf = ByteBuffer
        .allocateDirect(size * size * 3 * 4).order(ByteOrder.nativeOrder())
    private val out = Array(1) { FloatArray(numClasses) }

    data class SignResult(val classId: Int, val name: String, val score: Float,
                          val speedLimitKmh: Int?)

    /** Classify a sign crop. Returns null if below confidence. */
    fun classify(crop: Bitmap, minScore: Float = 0.6f): SignResult? {
        val scaled = Bitmap.createScaledBitmap(crop, size, size, true)
        val px = IntArray(size * size)
        scaled.getPixels(px, 0, size, 0, 0, size, size)
        inBuf.rewind()
        for (p in px) {
            inBuf.putFloat((p shr 16 and 0xFF) / 255f)
            inBuf.putFloat((p shr 8 and 0xFF) / 255f)
            inBuf.putFloat((p and 0xFF) / 255f)
        }
        if (scaled != crop) scaled.recycle()
        interpreter.run(inBuf, out)
        var best = 0; var bestS = out[0][0]
        for (i in 1 until numClasses) if (out[0][i] > bestS) { bestS = out[0][i]; best = i }
        if (bestS < minScore) return null
        return SignResult(best, NAMES[best], bestS, SPEED_LIMITS[best])
    }

    fun close() = interpreter.close()

    private fun loadAsset(context: Context, name: String): ByteBuffer {
        context.assets.openFd(name).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).channel.use { ch ->
                return ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY,
                    fd.startOffset, fd.declaredLength)
            }
        }
    }

    companion object {
        val NAMES = arrayOf(
            "20 km/h", "30 km/h", "50 km/h", "60 km/h", "70 km/h", "80 km/h",
            "End 80 km/h", "100 km/h", "120 km/h", "No overtaking",
            "No overtaking (trucks)", "Priority road ahead", "Priority road",
            "Yield", "Stop", "No vehicles", "No trucks", "No entry",
            "General caution", "Dangerous curve left", "Dangerous curve right",
            "Double curve", "Bumpy road", "Slippery road", "Road narrows right",
            "Road work", "Traffic signals", "Pedestrians", "Children crossing",
            "Bicycles crossing", "Beware ice/snow", "Wild animals",
            "End of limits", "Turn right ahead", "Turn left ahead", "Ahead only",
            "Go straight or right", "Go straight or left", "Keep right",
            "Keep left", "Roundabout", "End no overtaking",
            "End no overtaking (trucks)")

        // Posted limit (km/h) for the speed-limit classes, else null.
        val SPEED_LIMITS = arrayOf(
            20, 30, 50, 60, 70, 80, null, 100, 120, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null)

        // Categories for display grouping.
        fun category(id: Int): String = when (id) {
            in 0..8, 9, 10, 15, 16, 17 -> "Regulatory"
            in 18..31 -> "Warning"
            else -> "Information"
        }
    }
}
