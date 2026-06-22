package com.rfsat.dms.detect

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.rfsat.dms.Detection
import com.rfsat.dms.util.DLog
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
    /** The currently committed speed limit (km/h), persisted across frames so
     *  the limit stays shown until a new sign changes it. */
    private var committedLimit: Int? = null
    private var lastOcrValue: Int? = null

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
        // True when signs came from the EU detector (whose class IDs match
        // SignDetector constants used by TurnMonitor). False for the GTSRB
        // fallback, whose IDs differ and must not drive turn logic.
        val fromEuDetector: Boolean = false,
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
        speedMs: Float = -1f,
        approach: SignApproachPredictor? = null,
    ): SignOutput {
        val dets = mutableListOf<Detection>()
        val signs = mutableListOf<com.rfsat.dms.RecognisedSign>()
        val unrecognised = mutableListOf<String>()
        var adopted: Int? = committedLimit

        // Preferred path: one-stage EU sign detector.
        val det = detector
        if (det != null) {
            val tDet0 = android.os.SystemClock.elapsedRealtime()
            val hits = det.detect(frame).toMutableList()
            // Second pass for DISTANT signs: at 960x720 a far sign is only a few
            // pixels and is missed once the frame is letterboxed to the 640 model
            // input. Distant signs sit near the vanishing point (upper-centre), so
            // we run the detector again on an upscaled centre crop, giving those
            // small signs more effective resolution, and map the hits back to
            // full-frame coordinates. Near/large signs are already caught by the
            // full-frame pass above; overlaps are de-duplicated on merge.
            val crop = centerCropForDistant(frame)
            if (crop != null) {
                val (cropBmp, cl, ct, cw, ch) = crop
                for (h in det.detect(cropBmp)) {
                    // remap crop-normalised coords -> full-frame-normalised
                    val rl = cl + h.left * cw
                    val rt = ct + h.top * ch
                    val rr = cl + h.right * cw
                    val rb = ct + h.bottom * ch
                    val mapped = h.copy(left = rl, top = rt, right = rr, bottom = rb)
                    if (hits.none { iou(it, mapped) > 0.3f }) hits += mapped
                }
                cropBmp.recycle()
            }
            val detMs = android.os.SystemClock.elapsedRealtime() - tDet0
            if (hits.isNotEmpty())
                DLog.i(TAG, "timing: detector %d ms (%d hits, frame %dx%d)".format(
                    detMs, hits.size, frame.width, frame.height))
            var sawSpeedSign = false
            for (hit in hits) {
                signs += com.rfsat.dms.RecognisedSign(
                    hit.name, SignDetector.category(hit.classId), hit.score, hit.classId)
                dets += Detection(hit.name, hit.score,
                    hit.left, hit.top, hit.right, hit.bottom, risky = false)
                // Speed-limit signs are a single generic class; the NUMBER is
                // read by OCR. OCR is deferred until the box is large enough to
                // be legible. At driving speed a sign is only legible for ~1
                // frame, so a single confident read is ADOPTED immediately
                // rather than requiring repeated agreeing reads (which never
                // accumulate at speed). A misread guard prevents a one-off bad
                // read from overriding a stable limit: switching to a DIFFERENT
                // value requires either high detection confidence or a second
                // agreeing read.
                if (hit.classId == SignDetector.SPEED_LIMIT_ID &&
                    hit.score >= SPEED_MIN_CONF) {
                    sawSpeedSign = true
                    val boxH = hit.bottom - hit.top
                    // OCR timing: size gate as before, plus (when supplied) the
                    // GPS-speed-aware approach predictor, which aims the OCR call at
                    // the frame where the sign is largest within its readable window
                    // rather than every frame it is visible. The map already carries
                    // the limit, so deferring OCR by a frame or two costs nothing.
                    val timingOk = approach?.shouldOcr(
                        boxH, speedMs, android.os.SystemClock.elapsedRealtime()) ?: true
                    if (boxH >= SPEED_OCR_MIN_BOX && timingOk) {
                        // Dual-sign check: a box much taller than wide often means
                        // two stacked signs (e.g. a speed limit above a secondary
                        // sign). Split into top/bottom halves and OCR each, taking
                        // the most plausible speed-limit reading, rather than
                        // OCR-ing the merged box (which usually fails).
                        val boxW = hit.right - hit.left
                        val aspect = if (boxW > 0) boxH / boxW else 1f
                        val v = if (aspect >= DUAL_SIGN_ASPECT) {
                            DLog.i(TAG, "dual-sign suspected (aspect %.2f), splitting".format(aspect))
                            ocrDualSign(frame, hit)
                        } else {
                            ocrSpeedLimit(frame, hit)
                        }
                        DLog.i(TAG, "speed-limit OCR: box=%.3f read=%s".format(boxH,
                            v?.toString() ?: "none"))
                        if (v != null) {
                            val same = (v == adopted)
                            val strong = hit.score >= SPEED_STRONG_CONF
                            val confirming = (v == lastOcrValue)
                            // A read from a SMALL crop (below the proven-readable
                            // size) is treated as tentative: it must be confirmed
                            // by a second agreeing read before it is committed, so
                            // a tiny-crop misread cannot set the limit on its own.
                            // Reads from large crops commit immediately as before.
                            val smallCrop = boxH < SPEED_OCR_TRUST_BOX
                            when {
                                smallCrop && !confirming && !same -> {
                                    DLog.i(TAG, "speed-limit $v from small crop " +
                                        "(box=%.3f), awaiting confirmation".format(boxH))
                                }
                                adopted == null || same ->
                                    adopted = v                       // first / reaffirm
                                strong || confirming -> {
                                    adopted = v                       // confident change
                                    DLog.i(TAG, "speed limit adopted: $v km/h")
                                }
                                else ->
                                    DLog.i(TAG, "speed-limit $v pending confirm " +
                                        "(current $adopted)")
                            }
                            lastOcrValue = v
                            committedLimit = adopted                  // persist
                        }
                    } else {
                        DLog.i(TAG, "speed-limit sign seen but too small for OCR " +
                            "(box=%.3f < %.3f)".format(boxH, SPEED_OCR_MIN_BOX))
                    }
                }
            }
            if (dets.isEmpty()) candidate = null
            // No speed-limit sign in view: clear the approach history so the next
            // sign's growth is tracked from scratch (don't blend two signs).
            if (!sawSpeedSign) approach?.reset()
            return SignOutput(dets, adopted, signs, unrecognised, fromEuDetector = true)
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
                    res.speedLimitKmh?.let {
                        if (candidate == it) { adopted = it; committedLimit = it }
                        else candidate = it
                    }
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

    /** OCR the number on a detected speed-limit sign box. Returns a plausible
     *  limit (multiple of 5, 5..130) or null. The crop is padded slightly and
     *  upscaled if small, because ML Kit struggles with tiny text. */
    /** For a suspected stacked dual-sign box: OCR the top and bottom halves
     *  separately and return the most plausible speed limit found. The speed
     *  limit may be in either half, so both are tried; a valid reading wins. */
    private suspend fun ocrDualSign(frame: Bitmap, hit: SignDetector.SignHit): Int? {
        val mid = (hit.top + hit.bottom) / 2f
        val topHalf = hit.copy(bottom = mid)
        val botHalf = hit.copy(top = mid)
        val vTop = ocrSpeedLimit(frame, topHalf)
        val vBot = ocrSpeedLimit(frame, botHalf)
        DLog.i(TAG, "dual-sign halves: top=%s bottom=%s".format(
            vTop?.toString() ?: "none", vBot?.toString() ?: "none"))
        // Prefer a reading that exists; if both do, prefer the top (speed limit
        // is usually the upper sign in a stack).
        return vTop ?: vBot
    }

    private suspend fun ocrSpeedLimit(frame: Bitmap, hit: SignDetector.SignHit): Int? {
        val w = frame.width; val h = frame.height
        // Pad generously and ASYMMETRICALLY wide. Evidence from real drives
        // (50 read as 5, 100 as 10, worse for signs on the right) shows the
        // detector box can be shifted/narrow horizontally and clip the trailing
        // digit. A wide horizontal pad recovers the whole number even when the
        // box is offset; vertical pad stays moderate. Better to include some
        // border than to lose a digit.
        val padX = 0.45f      // was 0.12 — wide, to survive a horizontally-off box
        val padY = 0.20f
        val bw = (hit.right - hit.left); val bh = (hit.bottom - hit.top)
        val l = ((hit.left - bw * padX) * w).toInt().coerceIn(0, w - 1)
        val t = ((hit.top - bh * padY) * h).toInt().coerceIn(0, h - 1)
        val r = ((hit.right + bw * padX) * w).toInt().coerceIn(l + 1, w)
        val b = ((hit.bottom + bh * padY) * h).toInt().coerceIn(t + 1, h)
        var crop = runCatching { Bitmap.createBitmap(frame, l, t, r - l, b - t) }
            .getOrNull() ?: return null
        DLog.i(TAG, "OCR crop: norm[%.3f,%.3f,%.3f,%.3f] px[%d,%d,%d,%d] of %dx%d".format(
            hit.left, hit.top, hit.right, hit.bottom, l, t, r, b, w, h))
        // Upscale so the shorter side is at least ~96px — ML Kit reads digits
        // far better when they are not tiny.
        val minSide = minOf(crop.width, crop.height)
        if (minSide < 96) {
            val s = 96f / minSide
            crop = runCatching {
                Bitmap.createScaledBitmap(crop, (crop.width * s).toInt(),
                    (crop.height * s).toInt(), true)
            }.getOrNull() ?: crop
        }
        val tOcr0 = android.os.SystemClock.elapsedRealtime()
        val ocr = runCatching {
            recognizer.process(InputImage.fromBitmap(crop, 0)).await()
        }.getOrNull()
        val ocrMs = android.os.SystemClock.elapsedRealtime() - tOcr0
        DLog.i(TAG, "timing: OCR %d ms (crop %dx%d)".format(ocrMs, crop.width, crop.height))
        crop.recycle()
        if (ocr == null) return null
        for (block in ocr.textBlocks) for (line in block.lines) {
            val txt = line.text.trim().replace("O", "0").replace("o", "0").replace(" ", "")
            val v = txt.toIntOrNull() ?: continue
            if (v in 5..130 && v % 5 == 0) return v
        }
        return null
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

    /**
     * Upscaled centre crop for the distant-sign detection pass. Returns the crop
     * bitmap plus its origin/size as fractions of the full frame (so hits can be
     * mapped back), or null if the frame is too small to bother. The crop covers
     * the upper-centre region where approaching roadside signs appear before they
     * grow large; it is scaled up so small signs gain effective resolution.
     */
    private fun centerCropForDistant(
        frame: Bitmap
    ): Quint? {
        val w = frame.width; val h = frame.height
        if (w < 640 || h < 480) return null
        // Crop the central 50% width and the upper-centre 50% height band (signs
        // approach through the vanishing-point region, slightly above centre).
        val cwFrac = 0.5f; val chFrac = 0.5f
        val clFrac = 0.25f; val ctFrac = 0.15f      // band from 15%..65% height
        val cl = (w * clFrac).toInt(); val ct = (h * ctFrac).toInt()
        val cw = (w * cwFrac).toInt(); val ch = (h * chFrac).toInt()
        val sub = Bitmap.createBitmap(frame, cl, ct, cw, ch)
        // Upscale 2x so the model input (letterboxed to 640) sees roughly double
        // the linear resolution of a distant sign vs. the full-frame pass.
        val scaled = Bitmap.createScaledBitmap(sub, cw * 2, ch * 2, true)
        if (scaled != sub) sub.recycle()
        return Quint(scaled, clFrac, ctFrac, cwFrac, chFrac)
    }

    private data class Quint(
        val bmp: Bitmap, val lFrac: Float, val tFrac: Float,
        val wFrac: Float, val hFrac: Float)

    /** IoU of two SignHits in normalised coords (for de-duping merged passes). */
    private fun iou(a: SignDetector.SignHit, b: SignDetector.SignHit): Float {
        val l = maxOf(a.left, b.left); val t = maxOf(a.top, b.top)
        val r = minOf(a.right, b.right); val bo = minOf(a.bottom, b.bottom)
        val iw = (r - l).coerceAtLeast(0f); val ih = (bo - t).coerceAtLeast(0f)
        val inter = iw * ih
        val ua = (a.right - a.left) * (a.bottom - a.top)
        val ub = (b.right - b.left) * (b.bottom - b.top)
        val union = ua + ub - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun close() = classifier?.close()

    companion object {
        private const val TAG = "SignAnalyzer"
        const val SPEED_MIN_CONF = 0.45f
        const val SPEED_STRONG_CONF = 0.7f
        // Minimum sign-box height (fraction of frame) before OCR is attempted.
        // Lowered to 0.028: the 18-June-2 drive showed 42 of 51 sign sightings
        // were "too small" — signs are detected small and only briefly reach a
        // readable size, so the window was missed. Reads from crops below
        // SPEED_OCR_TRUST_BOX are confirmed before use (see analyze()).
        const val SPEED_OCR_MIN_BOX = 0.028f
        // A detected box taller than this ratio (height/width) is treated as a
        // possible stacked dual-sign and split for OCR. Single round speed signs
        // are ~1:1; ~1.6+ suggests two signs stacked vertically.
        const val DUAL_SIGN_ASPECT = 1.6f
        // Box height at/above which a single OCR read is trusted immediately
        // (the smallest size that read correctly on real drives was ~0.036).
        const val SPEED_OCR_TRUST_BOX = 0.036f
    }
}
