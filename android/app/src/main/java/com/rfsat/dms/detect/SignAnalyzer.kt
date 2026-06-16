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
class SignAnalyzer(context: android.content.Context? = null) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var candidate: Int? = null
    private val speedVotes = ArrayDeque<Int>()

    // Preferred: one-stage EU sign DETECTOR (Mapillary-trained, 27 classes).
    private val detector: SignDetector? = context?.let { ctx ->
        if (runCatching { ctx.assets.open("sign_eu.tflite").close() }.isSuccess)
            runCatching { SignDetector(ctx) }.getOrNull() else null
    }
    // Fallback: two-stage GTSRB classifier + region proposer.
    private val classifier: SignClassifier? = if (detector != null) null else context?.let { ctx ->
        if (runCatching { ctx.assets.open("gtsrb_sign.tflite").close() }.isSuccess)
            runCatching { SignClassifier(ctx) }.getOrNull() else null
    }
    val hasClassifier get() = classifier != null || detector != null

    data class SignOutput(
        val detections: List<Detection>,
        val speedLimitSeen: Int?,
        val signs: List<com.rfsat.dms.RecognisedSign>,
        val unrecognised: List<String> = emptyList(),
    )

    /**
     * Two-stage recognition. signCandidates are bounding boxes (normalized)
     * proposed upstream — e.g. YOLO "stop sign" detections — which are cropped
     * and classified by the GTSRB model. The OCR path remains as a fallback /
     * cross-check for speed-limit digits.
     */
    suspend fun analyze(
        frame: Bitmap,
        signCandidates: List<Detection> = emptyList(),
    ): SignOutput {
        val dets = mutableListOf<Detection>()
        val signs = mutableListOf<com.rfsat.dms.RecognisedSign>()
        val unrecognised = mutableListOf<String>()
        var adopted: Int? = null

        // Preferred path: one-stage EU sign detector.
        val det = detector
        if (det != null) {
            for (hit in det.detect(frame)) {
                signs += com.rfsat.dms.RecognisedSign(
                    hit.name, SignDetector.category(hit.classId), hit.score, hit.classId)
                dets += Detection(hit.name, hit.score,
                    hit.left, hit.top, hit.right, hit.bottom, risky = false)
                // Speed-limit adoption is error-prone (the model can confuse
                // similar digits), so require a higher confidence AND a majority
                // vote over the last few high-confidence readings before
                // adopting, rather than just two consecutive frames. This cuts
                // wrong numbers at the cost of a small, bounded delay.
                hit.speedLimitKmh?.let { v ->
                    if (hit.score >= SPEED_MIN_CONF) {
                        speedVotes.addLast(v)
                        while (speedVotes.size > SPEED_VOTE_WINDOW) speedVotes.removeFirst()
                        val winner = speedVotes.groupingBy { it }.eachCount()
                            .maxByOrNull { it.value }
                        if (winner != null && winner.value >= SPEED_VOTE_MIN)
                            adopted = winner.key
                    }
                }
            }
            if (dets.isEmpty()) candidate = null
            return SignOutput(dets, adopted, signs, unrecognised)
        }

        // Fallback path: propose sign regions for the GTSRB classifier.
        val clf = classifier
        val candidates = if (clf != null)
            (signCandidates + proposeSignRegions(frame))
                .distinctBy { (it.left * 20).toInt() to (it.top * 20).toInt() }
                .take(5)
        else signCandidates

        // Stage 2: classify candidate sign regions with the GTSRB model.
        if (clf != null) {
            for (cand in candidates) {
                val l = (cand.left * frame.width).toInt().coerceIn(0, frame.width - 2)
                val t = (cand.top * frame.height).toInt().coerceIn(0, frame.height - 2)
                val r = (cand.right * frame.width).toInt().coerceIn(l + 1, frame.width)
                val b = (cand.bottom * frame.height).toInt().coerceIn(t + 1, frame.height)
                val crop = runCatching {
                    Bitmap.createBitmap(frame, l, t, r - l, b - t)
                }.getOrNull() ?: continue
                val res = clf.classify(crop)
                if (res != null) {
                    crop.recycle()
                    signs += com.rfsat.dms.RecognisedSign(
                        res.name, SignClassifier.category(res.classId), res.score,
                        res.classId)
                    dets += cand.copy(labelText = res.name, risky = false)
                    res.speedLimitKmh?.let { if (candidate == it) adopted = it else candidate = it }
                } else {
                    // Diagnostic: a sign-shaped region the model could not
                    // confidently recognise. Log its border colour and the best
                    // (low-confidence) guess so we can gauge how often EU signs
                    // absent from GTSRB — e.g. no-turn prohibitions — appear.
                    if (cand.labelText.startsWith("sign?:")) {
                        val colour = cand.labelText.substringAfter(":")
                        val guess = clf.inspect(crop)
                        unrecognised += "$colour border, best guess '${guess.name}'" +
                            " ${(guess.score * 100).toInt()}%"
                    }
                    crop.recycle()
                }
            }
        }

        // Stage fallback: OCR speed-limit digits in the upper ROI.
        val roiH = (frame.height * 0.6f).toInt()
        val roi = Bitmap.createBitmap(frame, 0, 0, frame.width, roiH)
        val ocr = runCatching {
            recognizer.process(InputImage.fromBitmap(roi, 0)).await()
        }.getOrNull()
        roi.recycle()
        if (ocr != null) {
            for (block in ocr.textBlocks) for (line in block.lines) {
                val txt = line.text.trim().replace("O", "0")
                val v = txt.toIntOrNull() ?: continue
                if (v !in 20..130 || v % 10 != 0) continue
                val bb = line.boundingBox ?: continue
                if (bb.width().toFloat() / bb.height() > 2.5f) continue
                dets += Detection("limit $v", 0.8f,
                    bb.left.toFloat() / frame.width, bb.top.toFloat() / frame.height,
                    bb.right.toFloat() / frame.width, bb.bottom.toFloat() / frame.height)
                if (candidate == v) adopted = v else candidate = v
            }
        }
        if (dets.isEmpty()) candidate = null
        return SignOutput(dets, adopted, signs, unrecognised)
    }

    /**
     * Lightweight sign-region proposer. Traffic signs have saturated red, blue
     * or yellow borders against the background. This scans the upper ~55% of the
     * frame on a coarse grid, finds connected cells rich in those hues, and
     * returns square-ish bounding boxes around the densest clusters. It is a
     * recall-oriented proposer: the GTSRB classifier's confidence threshold
     * rejects non-sign crops, so a few false regions are harmless.
     */
    private fun proposeSignRegions(frame: Bitmap): List<Detection> {
        val w = frame.width; val h = frame.height
        val roiH = (h * 0.55f).toInt()
        val gx = 24; val gy = 16                      // coarse grid
        val cellW = w / gx; val cellH = roiH / gy
        if (cellW < 2 || cellH < 2) return emptyList()
        val px = IntArray(w * roiH)
        frame.getPixels(px, 0, w, 0, 0, w, roiH)
        val score = Array(gy) { IntArray(gx) }
        val redC = Array(gy) { IntArray(gx) }
        val blueC = Array(gy) { IntArray(gx) }
        val yellowC = Array(gy) { IntArray(gx) }
        var i = 0
        while (i < px.size) {
            val c = px[i]
            val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val bl = c and 0xFF
            val mx = maxOf(r, g, bl); val mn = minOf(r, g, bl)
            val sat = if (mx == 0) 0 else (mx - mn) * 255 / mx
            if (mx > 90 && sat > 80) {
                val isRed = r > 140 && g < 110 && bl < 110
                val isBlue = bl > 130 && r < 110 && g < 140
                val isYellow = r > 150 && g > 140 && bl < 110
                if (isRed || isBlue || isYellow) {
                    val x = (i % w); val y = (i / w)
                    val cx = x / cellW; val cy = y / cellH
                    if (cx in 0 until gx && cy in 0 until gy) {
                        score[cy][cx]++
                        if (isRed) redC[cy][cx]++
                        else if (isBlue) blueC[cy][cx]++
                        else yellowC[cy][cx]++
                    }
                }
            }
            i += 3                                    // subsample
        }
        // Pick top cells and grow a square region around each.
        val cellArea = cellW * cellH
        val hot = ArrayList<Triple<Int, Int, Int>>()
        for (cy in 0 until gy) for (cx in 0 until gx) {
            val s = score[cy][cx]
            if (s > cellArea / 18) hot += Triple(s, cx, cy)
        }
        hot.sortByDescending { it.first }
        val out = ArrayList<Detection>()
        val used = HashSet<Long>()
        for ((_, cx, cy) in hot.take(4)) {
            val key = (cx / 3).toLong() * 100 + (cy / 3)
            if (!used.add(key)) continue
            val pxc = (cx + 0.5f) * cellW; val pyc = (cy + 0.5f) * cellH
            val half = maxOf(cellW, cellH) * 1.6f
            val colour = when (maxOf(redC[cy][cx], blueC[cy][cx], yellowC[cy][cx])) {
                redC[cy][cx] -> "red"; blueC[cy][cx] -> "blue"; else -> "yellow"
            }
            out += Detection("sign?:$colour", 0.5f,
                ((pxc - half) / w).coerceIn(0f, 1f), ((pyc - half) / h).coerceIn(0f, 1f),
                ((pxc + half) / w).coerceIn(0f, 1f), ((pyc + half) / h).coerceIn(0f, 1f))
        }
        return out
    }

    fun close() = classifier?.close()

    companion object {
        const val SPEED_MIN_CONF = 0.55f
        const val SPEED_VOTE_WINDOW = 5
        const val SPEED_VOTE_MIN = 3
    }
}
