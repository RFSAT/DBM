package com.rfsat.dms.capture

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera.SingleCameraConfig
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.rfsat.dms.CameraRole
import com.rfsat.dms.util.DLog
import java.util.concurrent.Executors

/**
 * Phase-1 capture: the phone's own cameras.
 *  - INTERIOR  = front (screen-side) camera, watching the driver/cabin.
 *  - ROAD      = rear camera, looking ahead through the windscreen.
 *
 * Strategy:
 *  1. If the device supports CameraX ConcurrentCamera (Android 11+ and HAL
 *     concurrent capability), run both cameras simultaneously.
 *  2. Otherwise fall back to TIME-MULTIPLEXING: alternate cameras, with the
 *     interior (driver) camera given the larger share of the duty cycle
 *     since it carries the safety-critical detections.
 *
 * Frames are delivered as RGB bitmaps (analysis size) with timestamps.
 */
class PhoneCameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    val interiorPreview: PreviewView,
    val roadPreview: PreviewView,
    private val onFrame: (CameraRole, Bitmap, Long) -> Unit,
    private val onMode: (Mode) -> Unit = {},
) {
    enum class Mode { CONCURRENT, MULTIPLEXED }

    companion object { private const val TAG = "PhoneCameras" }

    private val analysisExecutor = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var mode = Mode.MULTIPLEXED
    private var muxShowingInterior = true
    private var released = false

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            provider = future.get()
            val p = provider!!
            DLog.i(TAG, "camera provider ready; concurrent combos: " +
                p.availableConcurrentCameraInfos.size)
            if (p.availableConcurrentCameraInfos.isNotEmpty()) {
                runCatching { bindConcurrent(p) }
                    .onFailure {
                        DLog.w(TAG, "concurrent bind failed, falling back to multiplex", it)
                        bindMultiplexed(p)
                    }
            } else bindMultiplexed(p)
        }, ContextCompat.getMainExecutor(context))
    }

    // ---- concurrent front + back ----
    private fun bindConcurrent(p: ProcessCameraProvider) {
        p.unbindAll()
        val interior = singleConfig(CameraSelector.DEFAULT_FRONT_CAMERA,
            interiorPreview, CameraRole.DRIVER)
        val road = singleConfig(CameraSelector.DEFAULT_BACK_CAMERA,
            roadPreview, CameraRole.FRONT)
        p.bindToLifecycle(listOf(interior, road))
        mode = Mode.CONCURRENT
        DLog.i(TAG, "bound CONCURRENT front+back")
        onMode(mode)
    }

    private fun singleConfig(
        selector: CameraSelector, view: PreviewView, role: CameraRole
    ): SingleCameraConfig {
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = view.surfaceProvider
        }
        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(analysisUseCase(role))
            .build()
        return SingleCameraConfig(selector, group, lifecycleOwner)
    }

    // ---- fallback: alternate cameras ----
    private fun bindMultiplexed(p: ProcessCameraProvider) {
        mode = Mode.MULTIPLEXED
        DLog.i(TAG, "bound MULTIPLEXED (alternating cameras)")
        onMode(mode)
        muxBindCurrent(p)
        handler.post(object : Runnable {
            override fun run() {
                if (released) return
                muxShowingInterior = !muxShowingInterior
                muxBindCurrent(p)
                // Driver camera gets 6 s of each 9 s cycle.
                handler.postDelayed(this, if (muxShowingInterior) 6000L else 3000L)
            }
        })
    }

    private fun muxBindCurrent(p: ProcessCameraProvider) {
        p.unbindAll()
        val (selector, view, role) =
            if (muxShowingInterior)
                Triple(CameraSelector.DEFAULT_FRONT_CAMERA, interiorPreview, CameraRole.DRIVER)
            else
                Triple(CameraSelector.DEFAULT_BACK_CAMERA, roadPreview, CameraRole.FRONT)
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        runCatching {
            p.bindToLifecycle(lifecycleOwner, selector, preview, analysisUseCase(role))
        }.onFailure { DLog.e(TAG, "mux bind failed for $role", it) }
    }

    private fun analysisUseCase(role: CameraRole): ImageAnalysis =
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build().also { ua ->
                var lastT = 0L
                val minIntervalMs = if (role == CameraRole.DRIVER) 100L else 160L // 10 / ~6 fps
                ua.setAnalyzer(analysisExecutor) { img: ImageProxy ->
                    val now = System.currentTimeMillis()
                    if (now - lastT >= minIntervalMs) {
                        lastT = now
                        onFrame(role, img.toBitmap(), now)
                    }
                    img.close()
                }
            }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        provider?.unbindAll()
        analysisExecutor.shutdown()
    }
}
