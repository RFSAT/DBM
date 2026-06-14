package com.rfsat.dms.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.rfsat.dms.AnalysisResult
import com.rfsat.dms.DetClass
import com.rfsat.dms.LaneLine
import com.rfsat.dms.util.DLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records one camera's analysed frames — WITH the detection overlays burnt
 * in — to an H.264/MP4 file. Frames arrive at analysis rate (6–10 FPS); the
 * encoder timestamps them with wall-clock deltas so playback speed is true.
 *
 * Pipeline per frame: copy frame bitmap -> draw overlays (boxes, lane lines,
 * timestamp banner) -> ARGB to I420 -> MediaCodec (COLOR_FormatYUV420Flexible)
 * -> MediaMuxer. Files: filesDir/recordings/<role>_<start>.mp4. Recordings
 * older than RETENTION_DAYS are pruned when a new recording starts.
 */
class OverlayVideoRecorder(
    private val dir: File,
    private val role: String,
    private val width: Int,
    private val height: Int,
) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var startNs = 0L
    private val bufInfo = MediaCodec.BufferInfo()
    private val paintBox = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val paintLine = Paint().apply { strokeWidth = 5f }
    private val paintText = Paint().apply {
        color = Color.WHITE; textSize = 16f; setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.UK)

    private fun classColor(c: DetClass): Int = when (c) {
        DetClass.PEDESTRIAN -> Color.rgb(255, 179, 0)
        DetClass.BICYCLE, DetClass.MOTORCYCLE -> Color.rgb(255, 112, 67)
        DetClass.CAR -> Color.rgb(66, 165, 245)
        DetClass.TRUCK, DetClass.BUS -> Color.rgb(126, 87, 194)
        DetClass.SIGN -> Color.rgb(38, 198, 218)
        DetClass.LIGHT -> Color.rgb(239, 83, 80)
        DetClass.OTHER -> Color.GREEN
    }
    private var yuv: ByteArray? = null
    var active = false; private set

    @Synchronized
    fun start() {
        if (active) return
        runCatching {
            dir.mkdirs()
            // retention
            val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 3600 * 1000
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }

            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 10)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            val name = "${role}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
            muxer = MediaMuxer(File(dir, name).absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            track = -1
            startNs = System.nanoTime()
            active = true
            DLog.i(TAG, "$role recording started: $name")
        }.onFailure { DLog.e(TAG, "$role recorder start failed", it) }
    }

    /** Encode one analysed frame with its overlays burnt in. */
    @Synchronized
    fun encode(frame: Bitmap, result: AnalysisResult, tMs: Long) {
        if (!active) return
        runCatching {
            val annotated = annotate(frame, result, tMs)
            val c = codec ?: return
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val data = toI420(annotated)
                annotated.recycle()
                c.getInputBuffer(inIdx)!!.apply { clear(); put(data) }
                c.queueInputBuffer(inIdx, 0, data.size,
                    (System.nanoTime() - startNs) / 1000, 0)
            } else annotated.recycle()
            drain(false)
        }.onFailure { DLog.e(TAG, "$role encode failed", it) }
    }

    @Synchronized
    fun stop() {
        if (!active) return
        active = false
        runCatching {
            codec?.let { c ->
                val idx = c.dequeueInputBuffer(10_000)
                if (idx >= 0) c.queueInputBuffer(idx, 0, 0,
                    (System.nanoTime() - startNs) / 1000,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
                c.stop(); c.release()
            }
            muxer?.let { runCatching { it.stop() }; it.release() }
            DLog.i(TAG, "$role recording stopped")
        }.onFailure { DLog.e(TAG, "$role recorder stop failed", it) }
        codec = null; muxer = null; track = -1
    }

    // ---- internals ----

    private fun annotate(src: Bitmap, r: AnalysisResult, tMs: Long): Bitmap {
        val b = Bitmap.createScaledBitmap(src, width, height, true)
            .copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(b)
        r.detections.forEach { d ->
            paintBox.color = if (d.risky) Color.RED else classColor(d.detClass)
            cv.drawRect(d.left * width, d.top * height,
                d.right * width, d.bottom * height, paintBox)
            cv.drawText(d.detClass.display, d.left * width,
                (d.top * height - 4f).coerceAtLeast(12f), paintText)
        }
        r.laneLines.forEach { l ->
            paintLine.color = when (l.kind) {
                LaneLine.Kind.DOUBLE_SOLID -> Color.RED
                LaneLine.Kind.SOLID -> Color.YELLOW
                LaneLine.Kind.DASHED -> Color.CYAN
            }
            cv.drawLine(l.xBottom * width, height.toFloat(),
                l.xTop * width, height * 0.55f, paintLine)
        }
        r.events.forEach { ev ->
            paintText.color = Color.RED
            cv.drawText(ev.type.name + " " + ev.detail, 8f, 40f, paintText)
            paintText.color = Color.WHITE
        }
        cv.drawText("$role  ${tsFmt.format(Date(tMs))}", 8f, height - 10f, paintText)
        return b
    }

    /** ARGB bitmap -> I420 byte array. */
    private fun toI420(b: Bitmap): ByteArray {
        val w = b.width; val h = b.height
        val argb = IntArray(w * h)
        b.getPixels(argb, 0, w, 0, 0, w, h)
        val size = w * h * 3 / 2
        val out = yuv?.takeIf { it.size == size } ?: ByteArray(size).also { yuv = it }
        var yi = 0; var ui = w * h; var vi = ui + ui / 4
        var j = 0
        while (j < h) {
            var i = 0
            while (i < w) {
                val c = argb[j * w + i]
                val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val bl = c and 0xFF
                out[yi++] = ((66 * r + 129 * g + 25 * bl + 128 shr 8) + 16)
                    .coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    out[ui++] = ((-38 * r - 74 * g + 112 * bl + 128 shr 8) + 128)
                        .coerceIn(0, 255).toByte()
                    out[vi++] = ((112 * r - 94 * g - 18 * bl + 128 shr 8) + 128)
                        .coerceIn(0, 255).toByte()
                }
                i++
            }
            j++
        }
        return out
    }

    private fun drain(end: Boolean) {
        val c = codec ?: return
        while (true) {
            val outIdx = c.dequeueOutputBuffer(bufInfo, if (end) 10_000 else 0)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer!!.addTrack(c.outputFormat)
                    muxer!!.start()
                }
                outIdx >= 0 -> {
                    if (bufInfo.size > 0 && track >= 0) {
                        val buf = c.getOutputBuffer(outIdx)!!
                        muxer!!.writeSampleData(track, buf, bufInfo)
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    companion object {
        private const val TAG = "OverlayRecorder"
        const val RETENTION_DAYS = 7
    }
}
