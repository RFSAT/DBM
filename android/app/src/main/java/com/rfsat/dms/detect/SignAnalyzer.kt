package com.rfsat.dms.detect

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.rfsat.dms.Detection
import kotlinx.coroutines.tasks.await

/**
 * Road-sign recognition, v1: speed-limit signs via on-device ML Kit OCR.
 *
 * Approach: run text recognition on the upper 60 % of the road frame (signs
 * are above the road surface), accept 2–3 digit tokens that are plausible
 * limits (multiples of 10, 20..130 km/h) and where the text block is roughly
 * square-ish (sign plate), not wide (number plates, adverts). A limit must be
 * seen in 2 consecutive analysed frames before being adopted — cheap false-
 * positive suppression.
 *
 * Extension hooks (Phase 1.x):
 *  - full sign classifier: fine-tuned TFLite model on GTSRB (62+ classes:
 *    no-entry, no-left/right-turn, no-U-turn, stop, give-way...) — the
 *    no-turn classes feed RiskType.ILLEGAL_TURN together with gyro yaw rate.
 */
class SignAnalyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var candidate: Int? = null

    /** @return (overlay detections, adopted speed limit km/h or null) */
    suspend fun analyze(frame: Bitmap): Pair<List<Detection>, Int?> {
        val roiH = (frame.height * 0.6f).toInt()
        val roi = Bitmap.createBitmap(frame, 0, 0, frame.width, roiH)
        val result = runCatching {
            recognizer.process(InputImage.fromBitmap(roi, 0)).await()
        }.getOrNull() ?: return emptyList<Detection>() to null

        var adopted: Int? = null
        val dets = mutableListOf<Detection>()
        for (block in result.textBlocks) for (line in block.lines) {
            val txt = line.text.trim().replace("O", "0")
            val v = txt.toIntOrNull() ?: continue
            if (v !in 20..130 || v % 10 != 0) continue
            val bb = line.boundingBox ?: continue
            val ar = bb.width().toFloat() / bb.height()
            if (ar > 2.5f) continue   // wide text: plates/adverts, not a sign digit pair

            dets += Detection("limit $v", 0.8f,
                bb.left.toFloat() / frame.width, bb.top.toFloat() / frame.height,
                bb.right.toFloat() / frame.width, bb.bottom.toFloat() / frame.height)

            if (candidate == v) adopted = v else candidate = v
        }
        if (dets.isEmpty()) candidate = null
        return dets to adopted
    }
}
