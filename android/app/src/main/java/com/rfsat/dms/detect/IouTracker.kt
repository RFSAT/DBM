package com.rfsat.dms.detect

import com.rfsat.dms.Detection
import kotlin.math.max
import kotlin.math.min

/** Greedy IoU tracker — sufficient for TTC-proxy purposes at 5–10 FPS. */
class IouTracker(private val iouThresh: Float = 0.3f, private val ttlMs: Long = 1200) {

    class Track(var last: Detection, var tMs: Long) {
        private val history = ArrayDeque<Pair<Long, Float>>()  // (t, area)
        init { push(tMs, area(last)) }

        fun update(d: Detection, t: Long) { last = d; tMs = t; push(t, area(d)) }

        private fun push(t: Long, a: Float) {
            history.addLast(t to a)
            while (history.size > 1 && t - history.first().first > 1500) history.removeFirst()
        }

        /** Relative area growth per second over the recent window. */
        fun areaGrowthPerSec(): Float {
            if (history.size < 3) return 0f
            val (t0, a0) = history.first(); val (t1, a1) = history.last()
            val dt = (t1 - t0) / 1000f
            return if (dt < 0.2f || a0 <= 0f) 0f else (a1 - a0) / a0 / dt
        }

        companion object {
            fun area(d: Detection) = max(0f, d.right - d.left) * max(0f, d.bottom - d.top)
        }
    }

    private val tracks = mutableListOf<Track>()

    fun update(dets: List<Detection>, tMs: Long): List<Track> {
        val unmatched = dets.toMutableList()
        for (tr in tracks) {
            val best = unmatched.maxByOrNull { iou(tr.last, it) } ?: continue
            if (iou(tr.last, best) >= iouThresh) {
                tr.update(best, tMs)
                unmatched.remove(best)
            }
        }
        unmatched.forEach { tracks += Track(it, tMs) }
        tracks.removeAll { tMs - it.tMs > ttlMs }
        return tracks.filter { it.tMs == tMs }
    }

    private fun iou(a: Detection, b: Detection): Float {
        val l = max(a.left, b.left); val t = max(a.top, b.top)
        val r = min(a.right, b.right); val bo = min(a.bottom, b.bottom)
        val inter = max(0f, r - l) * max(0f, bo - t)
        val union = Track.area(a) + Track.area(b) - inter
        return if (union <= 0f) 0f else inter / union
    }
}
