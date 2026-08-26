package com.rfsat.dms.research

import android.content.Context
import android.graphics.Bitmap
import com.rfsat.dms.util.DLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase-0 research capture for the metric-depth work.
 *
 * Records ROAD frames (phone back camera, looking ahead through the windscreen)
 * as a numbered JPEG sequence, plus a synchronised CSV of per-frame telemetry
 * (timestamp, saved speed, GPS). Because DBM produces both the frames and the
 * speed from the same clock, the video<->speed synchronisation is exact — there
 * is no external sync problem to solve, which is exactly what the scale
 * calibration (depth = speed*dt / flow) needs.
 *
 * Deliberately minimal and OFF the critical path:
 *  - Enabled only in a research build/flag; does nothing unless started.
 *  - Frame saving runs on an IO coroutine; the analysis pipeline is never
 *    blocked waiting for disk.
 *  - Frame throttling and a hard frame cap bound storage use.
 *  - A single in-flight write at a time (dropIfBusy) so a slow disk can never
 *    back up the caller — we drop frames rather than stall the camera. Depth
 *    research wants representative frames, not every frame.
 *
 * Output goes to app external files dir: /Android/data/<pkg>/files/depth_capture/
 * <sessionId>/ with frames frame_000001.jpg ... and telemetry.csv, both pullable
 * over MTP/adb without root.
 */
class DepthCaptureRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Save at most one frame every this many ms (default ~5 fps is plenty for
     *  depth reference + flow; full rate would waste storage). */
    private val minIntervalMs: Long = 200L,
    /** Stop automatically after this many frames to bound storage. */
    private val maxFrames: Int = 3000,
    /** JPEG quality for the saved road frames. */
    private val jpegQuality: Int = 90,
) {
    private val running = AtomicBoolean(false)
    private val frameCount = AtomicInteger(0)
    private val writeBusy = AtomicBoolean(false)
    @Volatile private var lastSavedMs = 0L
    @Volatile private var sessionDir: File? = null
    @Volatile private var csv: File? = null
    @Volatile private var startedAtMs = 0L

    val isRunning: Boolean get() = running.get()
    val saved: Int get() = frameCount.get()
    val sessionPath: String? get() = sessionDir?.absolutePath

    /** Begin a new capture session. Returns the session directory, or null on
     *  failure (e.g. no storage). Safe to call when already running (no-op). */
    fun start(): String? {
        if (running.getAndSet(true)) return sessionDir?.absolutePath
        return runCatching {
            val base = File(context.getExternalFilesDir(null), "depth_capture")
            val id = "session_${System.currentTimeMillis()}"
            val dir = File(base, id).apply { mkdirs() }
            val header = "frame,filename,tMs,dtMs,speedKmh,speedSrc,lat,lon,headingDeg\n"
            val c = File(dir, "telemetry.csv").apply { writeText(header) }
            sessionDir = dir; csv = c
            frameCount.set(0); lastSavedMs = 0L; startedAtMs = System.currentTimeMillis()
            DLog.i(TAG, "capture started: ${dir.absolutePath}")
            dir.absolutePath
        }.onFailure {
            DLog.e(TAG, "capture start failed", it); running.set(false)
        }.getOrNull()
    }

    /** Stop the current session. Returns the directory that holds the data. */
    fun stop(): String? {
        if (!running.getAndSet(false)) return sessionDir?.absolutePath
        val dir = sessionDir?.absolutePath
        DLog.i(TAG, "capture stopped: $dir (${frameCount.get()} frames)")
        return dir
    }

    /**
     * Offer a road frame for capture. Cheap and non-blocking: applies the
     * interval throttle and the busy-drop synchronously, copies the bitmap, and
     * hands the actual JPEG encode + disk write to an IO coroutine. The caller
     * (the analysis pipeline) returns immediately.
     *
     * @param frame   the road bitmap (NOT recycled by us until the copy is done)
     * @param tMs     the same frame timestamp the pipeline uses
     * @param speedKmh best available speed at this instant
     * @param speedSrc which source that speed came from (OBD/GPS/VISUAL/NONE)
     * @param lat/lon/heading GPS context if available (NaN/null otherwise)
     */
    fun offer(
        frame: Bitmap, tMs: Long, speedKmh: Int, speedSrc: String,
        lat: Double?, lon: Double?, headingDeg: Double?,
    ) {
        if (!running.get()) return
        val now = System.currentTimeMillis()
        if (now - lastSavedMs < minIntervalMs) return          // throttle
        if (frameCount.get() >= maxFrames) { stop(); return }   // storage cap
        if (writeBusy.getAndSet(true)) return                  // drop if disk busy
        lastSavedMs = now
        val dtMs = if (startedAtMs == 0L) 0 else now - startedAtMs
        // Copy now so the pipeline may recycle the original immediately.
        val copy = runCatching { frame.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        if (copy == null) { writeBusy.set(false); return }
        val idx = frameCount.incrementAndGet()

        scope.launch(Dispatchers.IO) {
            try {
                val dir = sessionDir ?: return@launch
                val name = "frame_%06d.jpg".format(idx)
                File(dir, name).outputStream().use {
                    copy.compress(Bitmap.CompressFormat.JPEG, jpegQuality, it)
                }
                val la = lat?.toString() ?: ""
                val lo = lon?.toString() ?: ""
                val hd = headingDeg?.let { if (it.isNaN()) "" else "%.1f".format(it) } ?: ""
                csv?.appendText("$idx,$name,$tMs,$dtMs,$speedKmh,$speedSrc,$la,$lo,$hd\n")
            } catch (e: Exception) {
                DLog.e(TAG, "frame save failed", e)
            } finally {
                copy.recycle()
                writeBusy.set(false)
            }
        }
    }

    companion object { private const val TAG = "DepthCapture" }
}
