package com.rfsat.dms.capture

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
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
    /** Frame-interval multiplier driven by device thermal state. 1.0 = normal;
     *  higher = slower analysis to shed heat. Prevents the prolonged-use
     *  overheating seen on long drives. */
    @Volatile private var thermalFactor = 1.0
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    /** At high thermal levels the road pipeline (4 ML models) is suspended to
     *  shed the dominant heat source, keeping only the lighter driver pipeline.
     *  This is a stronger measure than rate-throttling alone, added after a
     *  drive where the phone hit CRITICAL thermal and was force-stopped. */
    @Volatile var thermalSuspendRoad = false
        private set
    @get:androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private val thermalListener by lazy {
        android.os.PowerManager.OnThermalStatusChangedListener { status ->
            thermalFactor = when (status) {
                android.os.PowerManager.THERMAL_STATUS_NONE,
                android.os.PowerManager.THERMAL_STATUS_LIGHT -> 1.0
                android.os.PowerManager.THERMAL_STATUS_MODERATE -> 1.6
                android.os.PowerManager.THERMAL_STATUS_SEVERE -> 3.0
                else -> 5.0
            }
            // Fully suspend the heavy road pipeline only at CRITICAL+ (was
            // SEVERE). At SEVERE the road pipeline keeps running but heavily
            // rate-limited (factor 3.0) — this preserves sign/hazard detection,
            // which full suspension was silently killing for long stretches of a
            // hot drive, while still shedding most of the load.
            thermalSuspendRoad = status >= android.os.PowerManager.THERMAL_STATUS_CRITICAL
            DLog.i(TAG, "thermal status $status -> factor $thermalFactor" +
                if (thermalSuspendRoad) " (road analysis suspended)" else "")
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var mode = Mode.MULTIPLEXED
    private var muxShowingInterior = true
    private var released = false

    fun start() {
        // Thermal monitoring is API 29+ (Android 10). On older devices it is
        // simply skipped — thermalFactor stays 1.0 and analysis runs full-rate.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                powerManager.addThermalStatusListener(analysisExecutor, thermalListener)
            }
        }
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
            // NOTE: we do NOT eagerly refresh surfaces or rebind here. At startup
            // the Detector PreviewViews may not be attached yet (the app can open
            // on another tab), so there is nothing to connect to. The single
            // source of truth for connecting a stream to its view is the
            // attach-triggered debounced rebind in hookAttach(): it fires when the
            // views actually exist (first show, and every return to the tab),
            // binding both cameras to the real, laid-out surfaces.
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
        DLog.i(TAG, "bound CONCURRENT front+back (both use cases bound)")
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
        val p = provider ?: run { DLog.w(TAG, "rebind: provider not ready"); return }
        DLog.i(TAG, "rebind requested (mode=$mode) " +
            "driverView[attached=${interiorPreview.isAttachedToWindow} " +
            "${interiorPreview.width}x${interiorPreview.height}] " +
            "roadView[attached=${roadPreview.isAttachedToWindow} " +
            "${roadPreview.width}x${roadPreview.height}]")
        runCatching {
            if (mode == Mode.CONCURRENT) bindConcurrent(p) else muxBindCurrent(p)
        }.onFailure { DLog.e(TAG, "rebind failed", it) }
    }

    /** Call when the activity returns to the foreground. Switching to another
     *  app stops the activity; CameraX unbinds, and because the PreviewView may
     *  not detach/re-attach, the attach-listener rebind does not fire — leaving
     *  blank previews on return. This re-establishes the camera binding. If the
     *  provider is not ready yet, start() from scratch. A second, later rebind
     *  catches the case where the preview surface was not ready at the first
     *  attempt (the "frozen image until you switch tabs" symptom). */
    fun resume() {
        if (released) return
        handler.removeCallbacks(rebindRunnable)
        if (provider != null) {
            // On return from background the OS unbound the session, so ONE rebind
            // is warranted. Follow it with a cheap surface refresh (not a second
            // rebind) in case a TextureView surface settled slightly later.
            handler.postDelayed(rebindRunnable, 150)
            handler.postDelayed({ if (!released) refreshSurfaces() }, 600)
            DLog.i(TAG, "resume: rebind + surface refresh scheduled")
        } else {
            DLog.i(TAG, "resume: provider not ready, starting")
            start()
        }
    }

    // Tracks whether each role's PreviewView has ever been attached+bound while
    // present. The first time a view truly attaches we must (re)bind so CameraX
    // connects to the real, laid-out surface; binding earlier (e.g. while the app
    // opened on another tab and the view did not exist) connects to nothing.
    // Roles whose PreviewView is currently attached to the window. A rebind is
    // only useful once the views actually exist, and CameraX drops a preview when
    // its view detaches (leaving a tab), so we rebind when they (re)attach.
    private val attachedRoles = mutableSetOf<CameraRole>()

    private fun hookAttach(view: PreviewView, role: CameraRole) {
        view.addOnAttachStateChangeListener(object :
                android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                attachedRoles += role
                // Re-issue this view's surface provider immediately (cheap), then
                // schedule ONE debounced rebind. We rebind on every (re)attach,
                // not just the first, because returning to the Detector tab
                // re-creates the views and the streams must be reconnected to the
                // real, laid-out surfaces. The debounce (removeCallbacks +
                // postDelayed) coalesces the two views' near-simultaneous attaches
                // into a SINGLE rebind, and absorbs rapid tab flips — so this does
                // NOT reproduce the old rebind storm (which came from multiple
                // rebinds per attach plus startup rebinds, not from one per
                // settle). The delay lets BOTH TextureView surfaces exist before
                // the bind, fixing "only the driver stream shows".
                handler.postDelayed({
                    if (!released) previews[role]?.surfaceProvider = view.surfaceProvider
                }, 60)
                handler.removeCallbacks(rebindRunnable)
                handler.postDelayed(rebindRunnable, 250)
                DLog.i(TAG, "attach $role (attached=${attachedRoles.size}) -> rebind scheduled")
            }
            override fun onViewDetachedFromWindow(v: android.view.View) {
                attachedRoles -= role
            }
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
        val sp = view.surfaceProvider
        DLog.i(TAG, "singleConfig[$role]: view attached=${view.isAttachedToWindow} " +
            "size=${view.width}x${view.height} surfaceProvider=${sp != null}")
        val preview = Preview.Builder()
            // Match the analysis 16:9 aspect so overlay boxes align with video.
            .setResolutionSelector(
                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        androidx.camera.core.resolutionselector.AspectRatioStrategy(
                            androidx.camera.core.AspectRatio.RATIO_16_9,
                            androidx.camera.core.resolutionselector.AspectRatioStrategy
                                .FALLBACK_RULE_AUTO))
                    .build())
            .build().also {
            it.surfaceProvider = view.surfaceProvider
            previews[role] = it
            DLog.i(TAG, "singleConfig[$role]: Preview built, surfaceProvider set")
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
        val preview = Preview.Builder()
            // Match the analysis 16:9 aspect so overlay boxes align with video.
            .setResolutionSelector(
                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        androidx.camera.core.resolutionselector.AspectRatioStrategy(
                            androidx.camera.core.AspectRatio.RATIO_16_9,
                            androidx.camera.core.resolutionselector.AspectRatioStrategy
                                .FALLBACK_RULE_AUTO))
                    .build())
            .build().also {
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
                var frameCount = 0L
                var lastFrameLogT = 0L
                ua.setAnalyzer(analysisExecutor) { img: ImageProxy ->
                    val now = System.currentTimeMillis()
                    // Frame-flow diagnostic: confirm frames actually arrive from
                    // each camera (a bound-but-blank preview shows whether the
                    // problem is the stream not flowing vs. not rendering). Logs
                    // the arrival rate per role every ~3 s.
                    frameCount++
                    if (now - lastFrameLogT > 3000) {
                        DLog.i(TAG, "frames[$role]: $frameCount received " +
                            "(latest ${img.width}x${img.height})")
                        lastFrameLogT = now
                    }
                    // Driver pipeline always full-rate; road pipeline drops to
                    // ~2 fps when stationary, ~6 fps when moving. Under thermal
                    // stress all intervals are stretched to shed heat.
                    val baseInterval = when {
                        role == CameraRole.DRIVER -> 100L
                        vehicleMoving -> 160L
                        else -> 500L
                    }
                    val minIntervalMs = (baseInterval * thermalFactor).toLong()
                    // Under high thermal load, skip the heavy road pipeline
                    // entirely (driver analysis continues at reduced rate).
                    if (role == CameraRole.FRONT && thermalSuspendRoad) {
                        img.close(); return@setAnalyzer
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            runCatching { powerManager.removeThermalStatusListener(thermalListener) }
        handler.removeCallbacksAndMessages(null)
        provider?.unbindAll()
        analysisExecutor.shutdown()
    }
}
