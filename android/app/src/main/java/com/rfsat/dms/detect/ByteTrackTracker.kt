package com.rfsat.dms.detect

import com.rfsat.dms.Detection
import kotlin.math.max
import kotlin.math.min

/**
 * ByteTrack-style multi-object tracker (recommendation from the algorithm
 * review). Two improvements over the previous greedy IoU tracker:
 *
 *  1. Constant-velocity prediction: each track predicts its next box from its
 *     recent motion, so matching survives brief detector misses and fast
 *     motion far better than position-only IoU.
 *  2. Two-stage association (the ByteTrack idea): high-confidence detections
 *     are matched first; remaining tracks are then given a second chance to
 *     match LOW-confidence detections that a single-threshold tracker would
 *     discard. This keeps partially-occluded or distant objects alive,
 *     reducing both identity switches and one-frame spurious tracks.
 *
 * Each track exposes a stable id, its age in frames (hits), and the relative
 * area-growth rate used for the time-to-collision proxy. Pure Kotlin — no
 * model required; works with any detector that emits Detection boxes.
 */
class ByteTrackTracker(
    private val iouHigh: Float = 0.3f,
    private val iouLow: Float = 0.2f,
    private val highScore: Float = 0.5f,
    private val maxAgeMs: Long = 1500,
) {
    class Track(var last: Detection, var tMs: Long, val id: Int) {
        var ageFrames = 1; private set
        var misses = 0
        private var vx = 0f; private var vy = 0f          // normalized box-centre velocity /s
        private val history = ArrayDeque<Pair<Long, Float>>()
        init { history.addLast(tMs to area(last)) }

        fun predictedBox(atMs: Long): Detection {
            val dt = (atMs - tMs) / 1000f
            val cx = (last.left + last.right) / 2f + vx * dt
            val cy = (last.top + last.bottom) / 2f + vy * dt
            val hw = (last.right - last.left) / 2f
            val hh = (last.bottom - last.top) / 2f
            return last.copy(left = cx - hw, top = cy - hh, right = cx + hw, bottom = cy + hh)
        }

        fun update(d: Detection, atMs: Long) {
            val dt = (atMs - tMs) / 1000f
            if (dt > 0.01f) {
                val pcx = (last.left + last.right) / 2f; val pcy = (last.top + last.bottom) / 2f
                val ncx = (d.left + d.right) / 2f; val ncy = (d.top + d.bottom) / 2f
                vx = 0.6f * vx + 0.4f * (ncx - pcx) / dt
                vy = 0.6f * vy + 0.4f * (ncy - pcy) / dt
            }
            last = d; tMs = atMs; ageFrames++; misses = 0
            history.addLast(atMs to area(d))
            while (history.size > 1 && atMs - history.first().first > 1500) history.removeFirst()
        }

        fun areaGrowthPerSec(): Float {
            if (history.size < 3) return 0f
            val (t0, a0) = history.first(); val (t1, a1) = history.last()
            val dt = (t1 - t0) / 1000f
            return if (dt < 0.2f || a0 <= 0f) 0f else (a1 - a0) / a0 / dt
        }

        companion object { fun area(d: Detection) = max(0f, d.right - d.left) * max(0f, d.bottom - d.top) }
    }

    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    fun update(dets: List<Detection>, tMs: Long): List<Track> {
        val high = dets.filter { it.score >= highScore }.toMutableList()
        val low = dets.filter { it.score < highScore }.toMutableList()

        // Stage 1: match all tracks to high-score detections (predicted boxes).
        associate(tracks, high, iouHigh, tMs)
        // Stage 2: tracks still unmatched this frame get a chance at low-score dets.
        val unmatched = tracks.filter { it.tMs != tMs }
        associate(unmatched, low, iouLow, tMs)

        // New tracks from leftover high-score detections.
        high.forEach { tracks += Track(it, tMs, nextId++) }

        // Age out.
        tracks.forEach { if (it.tMs != tMs) it.misses++ }
        tracks.removeAll { tMs - it.tMs > maxAgeMs }
        return tracks.filter { it.tMs == tMs }
    }

    private fun associate(cands: List<Track>, dets: MutableList<Detection>,
                          thresh: Float, tMs: Long) {
        for (tr in cands) {
            if (tr.tMs == tMs) continue
            val pred = tr.predictedBox(tMs)
            val best = dets.maxByOrNull { iou(pred, it) } ?: continue
            if (iou(pred, best) >= thresh) { tr.update(best, tMs); dets.remove(best) }
        }
    }

    private fun iou(a: Detection, b: Detection): Float {
        val l = max(a.left, b.left); val t = max(a.top, b.top)
        val r = min(a.right, b.right); val bo = min(a.bottom, b.bottom)
        val inter = max(0f, r - l) * max(0f, bo - t)
        val union = Track.area(a) + Track.area(b) - inter
        return if (union <= 0f) 0f else inter / union
    }
}
