package com.rfsat.dms.stream

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.rtsp.RtspMediaSource

/**
 * One RTSP stream: Media3/ExoPlayer renders into a [TextureView]; frames for
 * inference are sampled from the TextureView at [analysisFps] via getBitmap(),
 * which avoids a second decode path. This is the pragmatic phone-side design:
 * decode once (hardware), display, and tap frames at the analyzer's own rate.
 *
 * Auto-reconnects with exponential backoff on any playback error (WiFi drops,
 * node reboots at ignition, etc.).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class StreamPlayer(
    context: Context,
    private val rtspUrl: String,
    private val analysisFps: Int,
    private val onFrame: (Bitmap, Long) -> Unit,
    private val onState: (Boolean) -> Unit = {},
) {
    val textureView = TextureView(context)
    private val handler = Handler(Looper.getMainLooper())
    private var backoffMs = 1000L
    private var released = false

    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setVideoTextureView(textureView)
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                onState(state == Player.STATE_READY)
                if (state == Player.STATE_READY) backoffMs = 1000L
            }
            override fun onPlayerError(error: PlaybackException) {
                onState(false)
                handler.postDelayed({ if (!released) connect() }, backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
            }
        })
    }

    private val sampler = object : Runnable {
        override fun run() {
            if (released) return
            if (player.playbackState == Player.STATE_READY && textureView.isAvailable) {
                // Sample at analysis resolution directly — cheaper than full-size copy.
                textureView.getBitmap(ANALYSIS_W, ANALYSIS_H)?.let {
                    onFrame(it, System.currentTimeMillis())
                }
            }
            handler.postDelayed(this, 1000L / analysisFps)
        }
    }

    fun connect() {
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)            // robust over WiFi
            .setTimeoutMs(8000)
            .createMediaSource(MediaItem.fromUri(rtspUrl))
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        handler.removeCallbacks(sampler)
        handler.post(sampler)
    }

    fun release() {
        released = true
        handler.removeCallbacks(sampler)
        player.release()
    }

    companion object {
        const val ANALYSIS_W = 640
        const val ANALYSIS_H = 480
    }
}
