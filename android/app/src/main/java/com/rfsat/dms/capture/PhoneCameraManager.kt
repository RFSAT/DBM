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
    /** When false (vehicle ~stationary), road analysis runs at a reduced rate
     *  to save CPU/battery — a performance recommendation from the review. */
    @Volatile var vehicleMoving = true
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

    private val previews = mutableMapOf<CameraRole, Preview>()

    init {
        // Refresh each preview's surface provider at the moment its view is
        // re-attached to the window (returning to the Detector tab). Doing
        // this on ATTACH — not on tab composition — guarantees the
        // TextureView surface exists when the provider is issued.
        hookAttach(interiorPreview, CameraRole.DRIVER)
        hookAttach(roadPreview, CameraRole.FRONT)
    }

    private val rebindRunnable = Runnable { rebind() }

    /** Full use-case rebind — same recovery path as app minimise/restore. */
    fun rebind() {
        val p = provider ?: return
        DLog.i(TAG, "rebind requested (mode=$mode)")
        runCatching {
            if (mode == Mode.CONCURRENT) bindConcurrent(p) else muxBindCurrent(p)
        }.onFailure { DLog.e(TAG, "rebind failed", it) }
    }

    private fun hookAttach(view: PreviewView, role: CameraRole) {
        view.addOnAttachStateChangeListener(object :
                android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                // Re-attaching after a tab switch: a surface-provider refresh
                // alone proved unreliable; do what minimise/restore does — a
                // full (debounced) rebind of the use cases.
                handler.removeCallbacks(rebindRunnable)
                handler.postDelayed(rebindRunnable, 150)
            }
            override fun onViewDetachedFromWindow(v: android.view.View) = Unit
        })
    }

    /** Manual refresh (kept for explicit recovery paths). */
    fun refreshSurfaces() {
        previews[CameraRole.DRIVER]?.surfaceProvider = interiorPreview.surfaceProvider
        previews[CameraRole.FRONT]?.surfaceProvider = roadPreview.surfaceProvider
        DLog.i(TAG, "surface providers refreshed (manual)")
    }

    private fun singleConfig(
        selector: CameraSelector, view: PreviewView, role: CameraRole
    ): SingleCameraConfig {
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = view.surfaceProvider
            previews[role] = it
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
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = view.surfaceProvider
            previews[role] = it
        }
        runCatching {
            p.bindToLifecycle(lifecycleOwner, selector, preview, analysisUseCase(role))
        }.onFailure { DLog.e(TAG, "mux bind failed for $role", it) }
    }

    private fun analysisUseCase(role: CameraRole): ImageAnalysis =
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            // Request a higher analysis resolution. The default is ~640x480,
            // which leaves distant/oncoming vehicles and motorbikes too small
            // for the detector once letterboxed to 640. 1280x720 roughly doubles
            // the linear resolution of small objects. The road camera benefits
            // most; the driver camera can stay lower to save CPU.
            .setResolutionSelector(
                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            android.util.Size(
                                if (role == CameraRole.FRONT) 1280 else 960,
                                if (role == CameraRole.FRONT) 720 else 540),
                            androidx.camera.core.resolutionselector.ResolutionStrategy
                                .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build())
            .build().also { ua ->
                var lastT = 0L
                ua.setAnalyzer(analysisExecutor) { img: ImageProxy ->
                    val now = System.currentTimeMillis()
                    // Driver pipeline always full-rate; road pipeline drops to
                    // ~2 fps when stationary, ~6 fps when moving.
                    val minIntervalMs = when {
                        role == CameraRole.DRIVER -> 100L
                        vehicleMoving -> 160L
                        else -> 500L
                    }
                    if (now - lastT >= minIntervalMs) {
                        lastT = now
                        var bmp = img.toBitmap()
                        val rot = img.imageInfo.rotationDegrees
                        if (rot != 0) {
                            val m = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
                            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                            bmp.recycle(); bmp = r
                        }
                        onFrame(role, bmp, now)
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
