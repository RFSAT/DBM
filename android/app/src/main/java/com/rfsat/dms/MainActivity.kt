package com.rfsat.dms

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.style.TextAlign
import com.rfsat.dms.detect.SignDetector
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.drawText
import com.rfsat.dms.DetClass
import com.rfsat.dms.capture.PhoneCameraManager
import com.rfsat.dms.data.DmsDatabase
import com.rfsat.dms.service.MonitorService
import com.rfsat.dms.ui.HistoryScreen
import com.rfsat.dms.ui.SummaryScreen
import com.rfsat.dms.ui.theme.DbmTheme
import com.rfsat.dms.ui.theme.EnactDark
import com.rfsat.dms.ui.theme.EnactDarkMid
import com.rfsat.dms.ui.theme.EnactGreen
import com.rfsat.dms.ui.theme.EnactLime
import com.rfsat.dms.ui.theme.EnactOnSurface
import com.rfsat.dms.ui.theme.EnactOnSurfaceDim
import com.rfsat.dms.ui.theme.EnactSurface
import com.rfsat.dms.ui.theme.EnactWarning
import com.rfsat.dms.util.DLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object { private const val TAG = "MainActivity" }

    // Driver-view zoom-out factor (1.0 = normal, lower = more zoomed out). Held as
    // Compose state so the slider updates the live preview immediately. Seeded from
    // prefs in onCreate.
    private var driverViewZoom by mutableStateOf(1f)

    private var service: MonitorService? = null
    private var cameras: PhoneCameraManager? = null
    private lateinit var interiorView: PreviewView
    private lateinit var roadView: PreviewView
    private val captureMode = MutableStateFlow("starting…")

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            DLog.i(TAG, "service connected")
            service = (binder as MonitorService.LocalBinder).service
            maybeStart()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    private var permissionsOk = false
    // Consent dialog removed; startup no longer gates on it. Field retained
    // (always true) so the existing maybeStart() guard logs remain meaningful.
    private var privacyAccepted = true
    /** Map-database importer: opens the system file picker, copies the chosen
     *  .db into the app's private maps dir where it can always be read (robust
     *  under scoped storage on modern Android — manual placement in Download
     *  does not work on Android 13+/Galaxy S24). */
    private val importMapLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) { DLog.i(TAG, "map import cancelled"); return@registerForActivityResult }
            importMapDatabase(uri)
        }

    private fun importMapDatabase(uri: android.net.Uri) {
        mapImportStatus.value = "Importing…"
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val ok = runCatching {
                val dir = java.io.File(filesDir, "maps").apply { mkdirs() }
                val out = java.io.File(dir, "greece.db")
                contentResolver.openInputStream(uri)!!.use { input ->
                    out.outputStream().use { input.copyTo(it, 1 shl 20) }
                }
                DLog.i(TAG, "map imported -> ${out.path} (${out.length() / 1_000_000} MB)")
                true
            }.getOrElse { DLog.e(TAG, "map import failed", it); false }
            mapImportStatus.value = if (ok)
                "Imported. Restart monitoring to load the map." else "Import failed."
        }
    }

    private val mapImportStatus = MutableStateFlow("")

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            DLog.i(TAG, "permission results: " + res.entries.joinToString {
                "${it.key.substringAfterLast('.')}=${it.value}" })
            permissionsOk = res[Manifest.permission.CAMERA] == true
            if (permissionsOk) { startMonitorService(); maybeStart() }
            else DLog.w(TAG, "CAMERA permission denied — monitoring cannot start")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DLog.i(TAG, "MainActivity onCreate")
        driverViewZoom = getSharedPreferences("dbm", MODE_PRIVATE)
            .getFloat("driver_view_zoom", 1f)
        interiorView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        roadView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        // (3) keep the screen on while DBM is active
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // NOTE: a camera-typed foreground service may only be started once the
        // CAMERA runtime permission is granted (Android 14+ enforces this and
        // throws otherwise). So we request permissions first and start/bind the
        // service from the permission result, not here.
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            perms += Manifest.permission.POST_NOTIFICATIONS
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // For OBD adapter connection (BLUETOOTH_CONNECT) and the in-app BLE
            // scan (BLUETOOTH_SCAN). Requested here so OBD setup works without a
            // separate prompt; harmless if the user never uses OBD.
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        permLauncher.launch(perms.toTypedArray())
        setContent { DbmTheme { Surface(Modifier.fillMaxSize()) { Root() } } }
    }

    // ---- Screen-dim (thermal/power saving) ---------------------------------
    // After a configurable idle period on the Detector screen, the display is
    // dimmed to near-black while the cameras and detection keep running (the
    // service is unaffected). A tap restores full brightness and restarts the
    // timer. This removes the display — a significant heat source — during long
    // drives without stopping monitoring. Audio/TTS alerts still reach the
    // driver while dimmed.
    private val dimHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val dimmedState = androidx.compose.runtime.mutableStateOf(false)
    private var dimmed: Boolean
        get() = dimmedState.value
        set(v) { dimmedState.value = v }
    private val dimRunnable = Runnable { applyDim(true) }

    /** Dim delay in seconds; 0 = never dim. Read from prefs. */
    private fun dimDelaySec(): Int =
        getSharedPreferences("dbm", MODE_PRIVATE).getInt("screen_dim_sec", 30)

    /** Near-black but not fully off, so the screen stays on (camera/preview
     *  surfaces and the foreground service are unaffected). */
    private val dimLevel = 0.02f

    private fun applyDim(on: Boolean) {
        dimmed = on
        val lp = window.attributes
        lp.screenBrightness = if (on) dimLevel else
            android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    /** Restart the idle timer (called on any interaction). Wakes if dimmed. */
    fun resetDimTimer() {
        dimHandler.removeCallbacks(dimRunnable)
        if (dimmed) applyDim(false)
        val sec = dimDelaySec()
        if (sec > 0 && dimAllowed) dimHandler.postDelayed(dimRunnable, sec * 1000L)
    }

    /** Only auto-dim while actively monitoring on the Detector tab. */
    @Volatile var dimAllowed = false
        set(value) { field = value; resetDimTimer() }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetDimTimer()
    }

    /** Start and bind the monitoring service. Called only after the CAMERA
     *  permission is granted, so the camera-typed FGS start is legal. */
    private fun startMonitorService() {
        if (service != null) return
        startForegroundService(Intent(this, MonitorService::class.java))
        bindService(Intent(this, MonitorService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    private fun maybeStart() {
        DLog.i(TAG, "maybeStart: service=${service != null} perms=$permissionsOk privacy=$privacyAccepted cams=${cameras != null}")
        val svc = service ?: return
        if (!permissionsOk || !privacyAccepted || cameras != null) return
        svc.onPermissionsGranted()
        cameras = PhoneCameraManager(this, this, interiorView, roadView,
            onFrame = { role, bmp, t -> svc.submitFrame(role, bmp, t) },
            onMode = { captureMode.value = it.name.lowercase() }
        ).also { it.start(); svc.cameraManager = it }
    }

    // ------------------------------------------------------------------ UI

    private val tabs = listOf("About", "Detector", "Summary", "History", "OBD", "Log", "Settings")

    @Composable
    private fun Root() {
        var tab by remember { mutableIntStateOf(0) }
        // OBD tab (index 4) is only shown when the OBD adapter is enabled in
        // Settings. We keep the index mapping intact (so the `when (tab)` dispatch
        // is unchanged) and simply skip rendering that one tab when disabled.
        val prefs = remember { getSharedPreferences("dbm", MODE_PRIVATE) }
        var obdOn by remember { mutableStateOf(prefs.getBoolean("obd_enabled", false)) }
        // Re-read when returning to the tab row (cheap; keeps it in sync after the
        // user toggles OBD in Settings).
        androidx.compose.runtime.LaunchedEffect(tab) {
            obdOn = prefs.getBoolean("obd_enabled", false)
            // If OBD was turned off while its tab was selected, fall back to Detector.
            if (tab == 4 && !obdOn) tab = 1
        }
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(EnactDark).safeDrawingPadding()) {
            ScrollableTabRow(
                selectedTabIndex = tab,
                containerColor = EnactDarkMid,
                contentColor = EnactGreen,
                edgePadding = 8.dp,
                indicator = { pos ->
                    // When the OBD tab is hidden, the rendered position list is
                    // shorter than the tab index space; map the selected logical
                    // tab to its rendered position and clamp to avoid overflow.
                    val renderedIndex = if (!obdOn && tab > 4) tab - 1 else tab
                    if (renderedIndex in pos.indices) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(pos[renderedIndex]),
                            color = EnactGreen)
                    }
                },
            ) {
                tabs.forEachIndexed { i, name ->
                    // Skip the OBD tab (index 4) when OBD is disabled.
                    if (i == 4 && !obdOn) return@forEachIndexed
                    Tab(selected = tab == i, onClick = { tab = i },
                        selectedContentColor = EnactGreen,
                        unselectedContentColor = EnactOnSurfaceDim,
                        text = { Text(name, fontSize = 13.sp) })
                }
                // Exit: an action, not a screen. Never "selected"; tapping it
                // fully shuts the app down (stops the foreground service and
                // releases cameras) via the shared exitApp(). Tinted to read as
                // an action rather than another tab.
                Tab(selected = false, onClick = { exitApp() },
                    selectedContentColor = EnactWarning,
                    unselectedContentColor = EnactWarning,
                    text = { Text("Exit", fontSize = 13.sp,
                        fontWeight = FontWeight.Bold) })
            }
            when (tab) {
                0 -> AboutScreen()
                1 -> DetectorScreen()
                2 -> SummaryScreen(
                        dao = DmsDatabase.get(this@MainActivity).events(),
                        complianceState = (service?.scorer?.state
                            ?: MutableStateFlow(ComplianceState())).collectAsState().value,
                        onResetCounters = { service?.resetCounters() })
                3 -> HistoryScreen(dao = DmsDatabase.get(this@MainActivity).events(),
                        onBack = { tab = 1 })
                4 -> ObdScreen()
                5 -> LogScreen()
                6 -> SettingsScreen()
            }
            // Auto-dim only while on the Detector tab and actively monitoring.
            val monitoring by (service?.analysing
                ?: MutableStateFlow(false)).collectAsState()
            LaunchedEffect(tab, monitoring) {
                dimAllowed = (tab == 1 && monitoring)
            }
        }
        // Faint indicator while the screen is dimmed for thermal saving, so the
        // driver can see DBM is still monitoring (not off) and knows a tap wakes
        // it. Drawn over everything; very low alpha to add minimal light/heat.
        if (dimmed) {
            Box(Modifier.fillMaxSize()
                    .clickable(
                        interactionSource = remember {
                            androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null) { resetDimTimer() },
                contentAlignment = Alignment.BottomCenter) {
                Text("DBM monitoring — tap to wake",
                    color = Color(0x33FFFFFF), fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 40.dp))
            }
        }
        }
    }

    // ---- Detector: cameras side by side, detections underneath ----

    @Composable
    private fun DetectorScreen() {
        val portrait = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            StatusStrip()
            SignStrip()
            Spacer(Modifier.height(6.dp))
            if (portrait) {
                // Videos stacked one under the other; detections at the bottom.
                Column(Modifier.fillMaxWidth().weight(3.0f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CameraCard(CameraRole.DRIVER, interiorView,
                        Modifier.weight(1f).fillMaxWidth())
                    CameraCard(CameraRole.FRONT, roadView,
                        Modifier.weight(1f).fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth().weight(1.6f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CameraCard(CameraRole.DRIVER, interiorView, Modifier.weight(1f))
                    CameraCard(CameraRole.FRONT, roadView, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(6.dp))
            ControlBar()
            Spacer(Modifier.height(6.dp))
            DetectionPanel(Modifier.weight(0.7f))
        }
            ThermalNoticeToast(Modifier.align(Alignment.Center))
        }
    }

    /**
     * Brief, glanceable banner shown in the centre of the screen when thermal
     * management pauses or resumes pipelines, so a paused pipeline reads as the
     * app managing heat — not as a fault. Light-red when paused, light-green when
     * resumed; large bold text, auto-dismisses after ~3 s. Designed to be read at
     * a glance while driving.
     */
    @Composable
    private fun ThermalNoticeToast(modifier: Modifier = Modifier) {
        val notice by (cameras?.thermalNotice
            ?: MutableStateFlow<com.rfsat.dms.capture.PhoneCameraManager.ThermalNotice?>(null))
            .collectAsState()
        var shown by remember {
            mutableStateOf<com.rfsat.dms.capture.PhoneCameraManager.ThermalNotice?>(null) }
        LaunchedEffect(notice?.id) {
            val n = notice ?: return@LaunchedEffect
            shown = n
            kotlinx.coroutines.delay(3000)
            if (shown?.id == n.id) shown = null
        }
        val n = shown ?: return
        val bg = if (n.suspended) Color(0xFFFFCDD2) else Color(0xFFC8E6C9) // light red / green
        val fg = if (n.suspended) Color(0xFFB71C1C) else Color(0xFF1B5E20) // deep red / green
        Row(modifier
                .padding(20.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (n.suspended) "⚠" else "✓", color = fg, fontSize = 26.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.padding(end = 12.dp))
            Text(n.text, color = fg, fontSize = 18.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                lineHeight = 22.sp)
        }
    }

    @Composable
    private fun SignStrip() {
        val sgns by (service?.recognisedSigns
            ?: MutableStateFlow(emptyList<com.rfsat.dms.RecognisedSign>()))
            .collectAsState()
        if (sgns.isEmpty()) return
        Row(Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            sgns.take(4).forEach { sg ->
                val col = when (sg.category) {
                    "Regulatory" -> Color(0xFFE57373)
                    "Warning" -> EnactWarning
                    else -> Color(0xFF42A5F5)
                }
                Row(Modifier.clip(RoundedCornerShape(8.dp))
                        .background(EnactSurface)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(end = 5.dp)
                        .clip(RoundedCornerShape(3.dp)).background(col)
                        .padding(3.dp)) {}
                    Text(sg.name, color = EnactOnSurface, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }

    @Composable
    private fun StatusStrip() {
        val st by (service?.scorer?.state
            ?: MutableStateFlow(ComplianceState())).collectAsState()
        val mode by captureMode.collectAsState()
        Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.verticalGradient(listOf(EnactSurface, EnactDarkMid)))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("${st.score}%",
                fontWeight = FontWeight.Bold, fontSize = 18.sp,
                color = when { st.score >= 80 -> EnactGreen
                               st.score >= 50 -> EnactWarning
                               else -> Color(0xFFE57373) })
            Text("${st.currentSpeedKmh} km/h",
                fontSize = 14.sp,
                color = if (st.speedSource == SpeedSource.VISUAL) EnactWarning
                        else EnactOnSurface)
            // Speed limit shown as a small sign roundel (red ring + value) with
            // a very short "Limit" label.
            st.activeSpeedLimitKmh?.let { lim ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).padding(end = 4.dp),
                        contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val r = size.minDimension / 2f
                            drawCircle(Color.White, r, center)
                            drawCircle(Color(0xFFD32F2F), r, center,
                                style = Stroke(width = r * 0.34f))
                        }
                        Text("$lim", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            color = Color.Black)
                    }
                    Text("Limit", fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, color = EnactOnSurface)
                }
            }
            // Show a brief camera-mode hint, without internal jargon. The
            // normal (both-cameras) mode shows nothing; only the fallback is
            // flagged.
            val modeLabel = if (mode.contains("multiplex")) "single-cam" else ""
            if (modeLabel.isNotEmpty())
                Text(modeLabel, fontSize = 11.sp, color = EnactOnSurfaceDim)
        }
    }

    @Composable
    private fun CameraCard(role: CameraRole, view: PreviewView, modifier: Modifier) {
        val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
        val analysing by (service?.analysing
            ?: MutableStateFlow(true)).collectAsState()
        val liveResult by (service?.results?.get(role)
            ?: MutableStateFlow(AnalysisResult())).collectAsState()
        // When analysis is stopped/paused, show no overlays at all.
        val result = if (analysing) liveResult else AnalysisResult()
        // Driver-view zoom-out: when the phone is mounted close to the driver's
        // face the head fills the frame. This shrinks the rendered preview (and
        // its overlay, by the same factor so they stay aligned) within the card,
        // showing more of the scene and a smaller face. 1.0 = normal; lower =
        // more zoomed out. Only applies to the driver camera. The camera sensor
        // FOV is unchanged (you cannot optically zoom wider than the lens); this
        // makes the existing frame smaller on screen so it is comfortable to view.
        val driverZoom = if (role == CameraRole.DRIVER) driverViewZoom else 1f
        Box(modifier.clip(RoundedCornerShape(14.dp)).background(EnactSurface)) {
          Box(Modifier.fillMaxSize().scale(driverZoom)) {
            AndroidView(
                factory = {
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                    view
                },
                modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                // PreviewView FILL_CENTER crops the 4:3 frame to the card —
                // map normalized frame coords through the same scale+crop so
                // overlays align with the visible video.
                val frameAr = result.frameAspect
                val viewAr = size.width / size.height
                val sx: Float; val sy: Float; val ox: Float; val oy: Float
                if (viewAr > frameAr) {      // card wider: frame cropped top/bottom
                    sx = size.width; sy = size.width / frameAr
                    ox = 0f; oy = (size.height - sy) / 2f
                } else {                      // card taller: frame cropped left/right
                    sy = size.height; sx = size.height * frameAr
                    oy = 0f; ox = (size.width - sx) / 2f
                }
                fun mx(x: Float) = ox + x * sx
                fun my(y: Float) = oy + y * sy
                // The front (driver) camera preview is mirrored in COMPATIBLE
                // mode, but landmark coordinates are not. Whether the box must be
                // mirrored to match depends on the device, so it is a user
                // setting (default on for the driver camera) that can be flipped
                // once if the box tracks the wrong way. Some devices also mirror
                // the REAR preview, flipping road/plate boxes left-right — so the
                // road overlay has its own toggle (default off).
                val prefs0 = getSharedPreferences("dbm", MODE_PRIVATE)
                val mirror = if (role == CameraRole.DRIVER)
                    prefs0.getBoolean("mirror_driver_overlay", true)
                else
                    prefs0.getBoolean("mirror_road_overlay", false)
                result.detections.forEach { d ->
                    val classCol = when (d.detClass) {
                        DetClass.PEDESTRIAN -> Color(0xFFFFB300)   // amber
                        DetClass.BICYCLE,
                        DetClass.MOTORCYCLE -> Color(0xFFFF7043)   // orange — vulnerable
                        DetClass.CAR -> Color(0xFF42A5F5)          // blue
                        DetClass.TRUCK, DetClass.BUS -> Color(0xFF7E57C2) // purple — large
                        DetClass.SIGN -> Color(0xFF26C6DA)         // cyan
                        DetClass.LIGHT -> Color(0xFFEF5350)        // red
                        DetClass.OTHER -> EnactGreen
                    }
                    val col = if (d.risky) Color(0xFFE57373) else classCol
                    // Mirror x for the driver card so the face box tracks the face.
                    val dl = if (mirror) 1f - d.right else d.left
                    val dr = if (mirror) 1f - d.left else d.right
                    val l = mx(dl); val tp = my(d.top)
                    val r = mx(dr); val bt = my(d.bottom)
                    drawRect(col, topLeft = Offset(l, tp),
                        size = Size(r - l, bt - tp), style = Stroke(3f))
                    // Label: driver-state boxes carry a descriptive label
                    // (e.g. "EYES CLOSED", "yaw 12"); road detections use the
                    // class name.
                    val lbl = if (d.detClass == DetClass.OTHER) d.labelText else d.detClass.display
                    val tl = textMeasurer.measure(
                        androidx.compose.ui.text.AnnotatedString(lbl),
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 9.sp, color = Color.White))
                    val chipH = tl.size.height + 4f
                    val chipW = tl.size.width + 8f
                    val chipY = (tp - chipH).coerceAtLeast(0f)
                    drawRect(col, topLeft = Offset(l, chipY),
                        size = Size(chipW, chipH))
                    drawText(tl, topLeft = Offset(l + 4f, chipY + 2f))
                }
                result.laneLines.forEach { l ->
                    val col = when (l.kind) {
                        LaneLine.Kind.DOUBLE_SOLID -> Color(0xFFE57373)
                        LaneLine.Kind.SOLID -> EnactWarning
                        LaneLine.Kind.DASHED -> EnactLime
                    }
                    // Lane overlay model: STRAIGHT lines (a straight road has
                    // straight lines; only a real bend in the road bends them,
                    // which the detector already encodes in xBottom vs xTop).
                    // The line is drawn only in the LOWER HALF of the view: it
                    // starts at the bottom edge and rises to mid-height. The
                    // forward-tilt parameter sets how far the two lines CONVERGE
                    // toward the vanishing point as they go up — i.e. it pulls
                    // each line's top x toward the view centre (0.5). 0 = no
                    // convergence (line keeps its detected direction); 1 = top
                    // reaches the centre (full perspective convergence).
                    val tilt = result.laneForwardTilt
                    val topY = 0.5f                       // mid-height: lower half only
                    // detected top x at mid-height (interpolated along the
                    // detected bottom->ROI-top direction, so road bends are kept)
                    val span = (1f - result.roiTopFrac)
                    val sMid = if (span > 0f) (1f - topY) / span else 0f
                    val xTopDetected = l.xBottom + (l.xTop - l.xBottom) * sMid.coerceIn(0f, 1f)
                    // apply convergence toward centre
                    val xTopConverged = xTopDetected + (0.5f - xTopDetected) * tilt
                    val pB = Offset(mx(l.xBottom), my(1f))
                    val pT = Offset(mx(xTopConverged), my(topY))
                    fun drawSeg(off: Float, width: Float, dashed: Boolean) {
                        val effect = if (dashed) androidx.compose.ui.graphics.PathEffect
                            .dashPathEffect(floatArrayOf(22f, 18f), 0f) else null
                        drawLine(col, Offset(pB.x + off, pB.y), Offset(pT.x + off, pT.y),
                            strokeWidth = width, pathEffect = effect)
                    }
                    when (l.kind) {
                        LaneLine.Kind.DASHED -> drawSeg(0f, 7f, true)
                        LaneLine.Kind.SOLID -> drawSeg(0f, 9f, false)
                        LaneLine.Kind.DOUBLE_SOLID -> {
                            drawSeg(-7f, 7f, false)
                            drawSeg(7f, 7f, false)
                        }
                    }
                }
            }
          }  // end driver-zoom scaled Box (preview + overlay)
            // Large, driver-visible speed-limit sign in the lower-right of the
            // road view. Sized ~3-4x the small status-strip roundel so it is
            // readable at a glance while driving.
            if (role == CameraRole.FRONT) {
                val scState by (service?.scorer?.state
                    ?: MutableStateFlow(ComplianceState())).collectAsState()
                val showRoundel by (service?.showLimitRoundel
                    ?: MutableStateFlow(true)).collectAsState()
                val lim = if (analysing && showRoundel) scState.activeSpeedLimitKmh else null
                // Speed-limit roundel, lower-right. Reduced 20% (96 -> 77 dp).
                lim?.let { value ->
                    Box(Modifier.align(Alignment.BottomEnd).padding(12.dp)
                            .size(77.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val r = size.minDimension / 2f
                            drawCircle(Color.White, r, center)
                            drawCircle(Color(0xFFD32F2F), r, center,
                                style = Stroke(width = r * 0.30f))
                        }
                        Text("$value", fontSize = 27.sp,
                            fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
                // Other detected signs (no-turn, no-entry, warnings, etc.) shown
                // lower-left for a few seconds after they leave the frame, for
                // the driver's information (e.g. turn restrictions at lights).
                if (analysing) RecentSignsOverlay()

                // Quick on-screen toggles (cameras / parking) so the driver can
                // switch these without opening Settings. Small chips, lower-left,
                // above the recent-signs area. They write the same prefs as the
                // Settings switches and reflect current state.
                if (analysing) QuickToggles()
                // Parking advisory banner: appears when stopped and the region
                // has parking data for this spot. Advisory only — informs, never
                // accuses. Sits above the speed badge, top-centre of the road view.
                // Speed-camera warning banner (opt-in). Amber, top-centre, above
                // the parking advisory. Advisory only; the driver has taken legal
                // responsibility by enabling it in settings.
                val camWarn by (service?.cameraWarning
                    ?: MutableStateFlow<String?>(null)).collectAsState()
                if (analysing && camWarn != null) {
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xE6C2410C))
                            .padding(horizontal = 12.dp, vertical = 5.dp)) {
                        Text("\uD83D\uDCF7  ${camWarn}", color = Color.White,
                            fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                val parkAdvice by (service?.parkingAdvice
                    ?: MutableStateFlow<String?>(null)).collectAsState()
                if (analysing && parkAdvice != null) {
                    val warn = parkAdvice!!.startsWith("No ") ||
                        parkAdvice!!.startsWith("Restricted") ||
                        parkAdvice!!.contains("only") || parkAdvice!!.contains("Private")
                    // Sit below the camera banner if one is showing.
                    val topPad = if (camWarn != null) 44.dp else 8.dp
                    Box(Modifier.align(Alignment.TopCenter).padding(top = topPad)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (warn) Color(0xCCB5551F) else Color(0xCC1F6F6B))
                            .padding(horizontal = 11.dp, vertical = 5.dp)) {
                        Text("P  ${parkAdvice}", color = Color.White,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (analysing) {
                    val (srcLabel, srcColor) = when (scState.speedSource) {
                        SpeedSource.OBD -> "OBD" to EnactGreen
                        SpeedSource.GPS -> "GPS" to EnactLime
                        SpeedSource.VISUAL -> "Camera" to EnactWarning
                        SpeedSource.NONE -> "no source" to Color(0xFFE57373)
                    }
                    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x99000000))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${scState.currentSpeedKmh} km/h",
                            color = EnactOnSurface, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold)
                        Text("  ·  $srcLabel", color = srcColor, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold)
                    }
                }
            }
            // Camera/role label — top-RIGHT so it doesn't overlap the quick-toggle
            // chips (cameras/parking/limit) that sit top-left.
            Text(role.label, color = EnactOnSurface, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(EnactDarkMid.copy(alpha = 0.8f))
                    .padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }

    @Composable
    private fun ControlBar() {
        val analysing by (service?.analysing
            ?: MutableStateFlow(true)).collectAsState()
        val btnHeight = 34.dp
        val tightPad = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 8.dp, vertical = 0.dp)
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Start / Pause toggle.
            androidx.compose.material3.Button(
                onClick = {
                    if (analysing) service?.pauseAnalysis() else service?.resumeAnalysis()
                },
                modifier = Modifier.weight(1f).height(btnHeight),
                contentPadding = tightPad,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = if (analysing) EnactWarning else EnactGreen)) {
                Text(if (analysing) "Pause" else "Start", fontSize = 13.sp)
            }
            // Stop: pause analysis and reset the live score view.
            androidx.compose.material3.OutlinedButton(
                onClick = { service?.pauseAnalysis() },
                modifier = Modifier.weight(1f).height(btnHeight),
                contentPadding = tightPad) {
                Text("Stop", fontSize = 13.sp, color = EnactOnSurface)
            }
            // Exit the application.
            androidx.compose.material3.OutlinedButton(
                onClick = { exitApp() },
                modifier = Modifier.weight(1f).height(btnHeight),
                contentPadding = tightPad) {
                Text("Exit", fontSize = 13.sp, color = Color(0xFFE57373))
            }
        }
    }

    /** Fully exit: stop analysis, release cameras, unbind and stop the foreground
     *  service (otherwise it keeps the app alive), then remove the task. Without
     *  stopping the service, Exit only closes the UI. Shared by the Detector and
     *  About screens. */
    private fun exitApp() {
        service?.pauseAnalysis()
        cameras?.release()
        runCatching { unbindService(conn) }
        stopService(Intent(this@MainActivity, MonitorService::class.java))
        finishAndRemoveTask()
    }

    @Composable
    private fun DetectionPanel(modifier: Modifier) {
        val dao = remember { DmsDatabase.get(this).events() }
        val analysing by (service?.analysing
            ?: MutableStateFlow(true)).collectAsState()
        // Show a shorter list so the cameras get more vertical space.
        val dbEvents by dao.latest(8).collectAsStateWithLifecycle(initialValue = emptyList())
        // When stopped, clear past detections from the Detector screen.
        val events = if (analysing) dbEvents else emptyList()
        val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.UK) }
        Column(modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(EnactSurface)
                .padding(10.dp)) {
            Text("Detections", color = EnactGreen, fontSize = 13.sp,
                fontWeight = FontWeight.Bold)
            if (events.isEmpty())
                Text("No risk conditions detected.", color = EnactOnSurfaceDim,
                    fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            LazyColumn {
                items(events) { e ->
                    val label = e.type.replace('_', ' ').lowercase()
                        .replaceFirstChar { it.uppercase() }
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label + if (e.detail.isNotEmpty()) ": ${e.detail}" else "",
                            color = when (e.severity) {
                                "CRITICAL" -> Color(0xFFE57373)
                                "WARNING" -> EnactWarning
                                else -> EnactOnSurfaceDim
                            }, fontSize = 11.sp, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Text(fmt.format(Date(e.timestampMs)),
                            color = EnactOnSurfaceDim, fontSize = 10.sp)
                    }
                }
            }
        }
    }

    // ---- OBD tab ----

    @Composable
    private fun ObdScreen() {
        Column(Modifier.fillMaxSize().padding(14.dp)
                .verticalScroll(rememberScrollState())) {
            Text("OBD-II Adapter", color = EnactGreen, fontSize = 18.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Connect a Bluetooth OBD-II adapter to read accurate vehicle " +
                "speed (and, where supported, engine data) directly from the car.",
                color = EnactOnSurfaceDim, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            ObdSection(showLiveData = true)
        }
    }

    /**
     * Shared OBD UI used by both the OBD tab (with live data) and the Settings
     * section (controls only). Handles enable toggle, one-time adapter setup
     * (list bonded devices -> validate via handshake -> remember), forget, a live
     * connection-status line, the discovered-capabilities summary, and (on the
     * tab) live readings.
     */
    @Composable
    private fun ObdSection(showLiveData: Boolean) {
        val obd = service?.obd
        val scope = rememberCoroutineScope()
        if (obd == null) {
            Text("Start monitoring to configure OBD.",
                color = EnactOnSurfaceDim, fontSize = 13.sp)
            return
        }
        val state by obd.state.collectAsState()
        val data by obd.data.collectAsState()
        var enabled by remember { mutableStateOf(obd.enabled) }
        var candidates by remember {
            mutableStateOf<List<com.rfsat.dms.obd.ObdManager.Candidate>>(emptyList()) }
        var picking by remember { mutableStateOf(false) }
        var note by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }

        SettingRow("Use OBD-II adapter", enabled) {
            enabled = it; obd.setEnabled(it)
            note = if (it) "OBD enabled." else "OBD disabled."
        }

        // Remembered adapter + status.
        val remembered = obd.rememberedName
        Spacer(Modifier.height(6.dp))
        Text("Adapter: ${remembered ?: "none set up"}",
            color = EnactOnSurface, fontSize = 13.sp)
        Text("Status: ${obdStatusText(state)}",
            color = obdStatusColor(state), fontSize = 13.sp,
            fontWeight = FontWeight.Bold)

        if (enabled) {
            Spacer(Modifier.height(10.dp))
            Row {
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        candidates = obd.bondedCandidates(); picking = true
                        note = if (candidates.isEmpty())
                            "No paired devices. Tap Scan to find nearby adapters, " +
                            "or pair yours in Android Bluetooth settings first." else ""
                    },
                    enabled = !busy) { Text("Paired", fontSize = 13.sp) }
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        busy = true; picking = true
                        note = "Scanning for nearby adapters (a few seconds)…"
                        scope.launch {
                            candidates = obd.scanForAdapters()
                            busy = false
                            note = if (candidates.isEmpty())
                                "No adapters found. Make sure the adapter is " +
                                "plugged in and the ignition is on." else ""
                        }
                    },
                    enabled = !busy) { Text("Scan", fontSize = 13.sp) }
                if (remembered != null) {
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = { obd.forgetAdapter(); note = "Adapter forgotten." },
                        enabled = !busy) {
                        Text("Forget", fontSize = 13.sp, color = Color(0xFFE57373))
                    }
                }
            }

            // Device picker (paired + scanned; OBD-looking names first).
            if (picking && candidates.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Pick your adapter:", color = EnactOnSurfaceDim, fontSize = 12.sp)
                candidates.forEach { c ->
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            picking = false; busy = true
                            note = "Validating ${c.name}…"
                            scope.launch {
                                val ok = obd.setUpAdapter(c)
                                busy = false
                                note = if (ok) "Connected to ${c.name}."
                                       else "${c.name} did not respond as an OBD " +
                                            "adapter. Pick another or check it's plugged in."
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text("${c.name}  (${c.mac})${if (!c.bonded) "  · nearby" else ""}",
                            fontSize = 12.sp)
                    }
                }
            }
        }

        if (note.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(note, color = EnactOnSurfaceDim, fontSize = 12.sp)
        }

        // Live readings (OBD tab only).
        if (showLiveData && state == com.rfsat.dms.obd.ObdConnectionState.CONNECTED) {
            Spacer(Modifier.height(12.dp))
            Text("Live data", color = EnactGreen, fontSize = 15.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            ObdReadingRow("Speed", data.speedKmh, "km/h")
            ObdReadingRow("Engine RPM", data.rpm, "rpm")
            ObdReadingRow("Throttle", data.throttlePct, "%")
            ObdReadingRow("Engine load", data.enginePct, "%")
            ObdReadingRow("Coolant", data.coolantC, "\u00B0C")
            ObdReadingRow("Intake air", data.intakeC, "\u00B0C")
        }
    }

    @Composable
    private fun ObdReadingRow(label: String, value: Int?, unit: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = EnactOnSurface, fontSize = 13.sp)
            Text(if (value != null) "$value $unit" else "—",
                color = if (value != null) EnactOnSurface else EnactOnSurfaceDim,
                fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }

    private fun obdStatusText(s: com.rfsat.dms.obd.ObdConnectionState): String =
        when (s) {
            com.rfsat.dms.obd.ObdConnectionState.DISABLED -> "disabled"
            com.rfsat.dms.obd.ObdConnectionState.NOT_CONFIGURED -> "not set up"
            com.rfsat.dms.obd.ObdConnectionState.CONNECTING -> "connecting…"
            com.rfsat.dms.obd.ObdConnectionState.HANDSHAKING -> "handshaking…"
            com.rfsat.dms.obd.ObdConnectionState.CONNECTED -> "connected"
            com.rfsat.dms.obd.ObdConnectionState.NOT_FOUND -> "adapter not found (using GPS)"
            com.rfsat.dms.obd.ObdConnectionState.ERROR -> "error (using GPS)"
        }

    private fun obdStatusColor(s: com.rfsat.dms.obd.ObdConnectionState): Color =
        when (s) {
            com.rfsat.dms.obd.ObdConnectionState.CONNECTED -> EnactGreen
            com.rfsat.dms.obd.ObdConnectionState.CONNECTING,
            com.rfsat.dms.obd.ObdConnectionState.HANDSHAKING -> Color(0xFFFFB74D)
            com.rfsat.dms.obd.ObdConnectionState.NOT_FOUND,
            com.rfsat.dms.obd.ObdConnectionState.ERROR -> Color(0xFFE57373)
            else -> EnactOnSurfaceDim
        }

    // ---- Log tab ----

    @Composable
    private fun LogScreen() {
        var text by remember { mutableStateOf(DLog.tail()) }
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Diagnostic log (today)", color = EnactGreen,
                    fontWeight = FontWeight.Bold)
                Row {
                    TextButton(onClick = { text = DLog.tail() }) { Text("Refresh") }
                    TextButton(onClick = { saveLog() }) { Text("Save") }
                    TextButton(onClick = { shareLog() }) { Text("Share") }
                }
            }
            Column(Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(14.dp)).background(EnactDarkMid)
                    .padding(8.dp).verticalScroll(rememberScrollState())) {
                Text(text, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    color = EnactOnSurface)
            }
        }
    }

    /** Save today's log for sharing (e.g. with an AI assistant when
     *  debugging). API 29+: public Downloads via MediaStore. API 26–28
     *  (where MediaStore.Downloads does not exist): the app's external
     *  files directory, accessible over USB/file manager. */
    private fun saveLog() {
        runCatching {
            val src = DLog.currentLogFile()
            val name = "DBM-log-${java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                Locale.US).format(Date())}.txt"
            val shownPath: String
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)!!.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                shownPath = "Downloads/$name"
            } else {
                val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!
                val dst = java.io.File(dir, name)
                src.copyTo(dst, overwrite = true)
                shownPath = dst.absolutePath
            }
            DLog.i(TAG, "log saved to $shownPath")
            android.widget.Toast.makeText(this, "Saved to $shownPath",
                android.widget.Toast.LENGTH_LONG).show()
        }.onFailure { DLog.e(TAG, "log save failed", it) }
    }

    private fun shareLog() {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", DLog.currentLogFile())
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share DBM log"))
        }.onFailure { DLog.e(TAG, "log share failed", it) }
    }

    // ---- Settings tab ----

    @Composable
    private fun SettingsScreen() {
        val prefs = remember { getSharedPreferences("dbm", MODE_PRIVATE) }
        var audio by remember { mutableStateOf(prefs.getBoolean("alerts_audio", true)) }
        var tts by remember { mutableStateOf(prefs.getBoolean("alerts_tts", true)) }
        var mirrorDriver by remember {
            mutableStateOf(prefs.getBoolean("mirror_driver_overlay", true)) }
        var mirrorRoad by remember {
            mutableStateOf(prefs.getBoolean("mirror_road_overlay", false)) }
        var logGps by remember {
            mutableStateOf(prefs.getBoolean("log_gps", false)) }
        Column(Modifier.fillMaxSize().padding(14.dp)
                .verticalScroll(rememberScrollState())) {
            Text("Settings", color = EnactGreen, fontSize = 18.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            CollapsibleSection("General") {
                SettingRow("Audio alert tones", audio) {
                    audio = it; prefs.edit().putBoolean("alerts_audio", it).apply()
                    service?.setAudioAlerts(it)
                }
                SettingRow("Spoken warnings (TTS)", tts) {
                    tts = it; prefs.edit().putBoolean("alerts_tts", it).apply()
                    service?.setTtsAlerts(it)
                }
                SettingRow("Mirror driver face box", mirrorDriver) {
                    mirrorDriver = it
                    prefs.edit().putBoolean("mirror_driver_overlay", it).apply()
                }
                SettingRow("Mirror road/plate boxes", mirrorRoad) {
                    mirrorRoad = it
                    prefs.edit().putBoolean("mirror_road_overlay", it).apply()
                }
                SettingRow("Log GPS trace (for map cross-check dev)", logGps) {
                    logGps = it
                    service?.setElement("log_gps", it)
                        ?: prefs.edit().putBoolean("log_gps", it).apply()
                }
            }
            CollapsibleSection("Detection elements") {
                DetectionElementRow("Road signs (speed limits)", "det_signs")
                SpeedLimitModeRow()
                DetectionElementRow("Lane markings (overlay)", "det_lanes")
                DetectionElementRow("Single/double line-crossing events", "det_lane_cross")
                DetectionElementRow("Hard-shoulder driving", "det_shoulder")
                DetectionElementRow("Road objects (vehicles, pedestrians…)", "det_objects")
                DetectionElementRow("Unsafe following distance", "det_distance")
                DetectionElementRow("Traffic lights (red / amber crossing)", "det_lights")
                DetectionElementRow("Driver state (eyes, gaze, mirrors)", "det_driver")
                DetectionElementRow("Parking advice when stopped (where data exists)",
                    "parking_advice", default = false)
                SpeedCameraToggle()
                DetectionElementRow(
                    "Read lead-vehicle plate on serious hazard (stored locally only)",
                    "capture_plate", default = false)
            }
            CollapsibleSection("Speed limit maps") {
                MapManagerSection()
            }
            CollapsibleSection("Self-calibration") {
                Text("DBM adapts to the driver and mount during use: eye-closure " +
                    "baseline, straight-ahead head pose, the visual speed scale and " +
                    "the following-distance focal factor are learned automatically " +
                    "and bounded for safety. Independent detectors cross-check each " +
                    "other — two speed sources must agree before a speeding alert, a " +
                    "close gap must also be closing, and a line crossing must coincide " +
                    "with an actual heading change — reducing false positives.",
                    color = EnactOnSurfaceDim, fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Justify)
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = { service?.resetCalibration() }) {
                    Text("Reset calibration")
                }
            }
            CollapsibleSection("Following distance") {
                StoppingDistanceSlider()
                CacheEvictionSlider()
                MirrorIntervalSliders()
                LaneCalibrationSliders()
                DriverViewZoomSlider()
            }
            CollapsibleSection("Display & power") {
                ScreenDimSlider()
            }
            CollapsibleSection("OBD-II adapter") {
                ObdSection(showLiveData = false)
            }
            CollapsibleSection("Video recording") {
                VideoRecordingRow()
            }
            CollapsibleSection("Compliance score weights") {
                Text("Points deducted per occurrence (scaled by severity).",
                    color = EnactOnSurfaceDim, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                RiskType.entries.filter { it.implemented }.forEach { rt ->
                    WeightSlider(rt)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Data retention: 30 days (older data is automatically removed)",
                color = EnactOnSurfaceDim, fontSize = 12.sp)
            Text("All data is processed and stored on this device only.",
                color = EnactOnSurfaceDim, fontSize = 12.sp)
        }
    }

    @Composable
    private fun DetectionElementRow(label: String, key: String, default: Boolean = true) {
        val prefs = remember { getSharedPreferences("dbm", MODE_PRIVATE) }
        var on by remember { mutableStateOf(prefs.getBoolean(key, default)) }
        SettingRow(label, on) {
            on = it
            service?.setElement(key, it) ?: prefs.edit().putBoolean(key, it).apply()
        }
    }

    /** Speed-camera warning toggle. OFF by default, with a visible legal caveat:
     *  dynamic speed-camera warnings are prohibited in some countries, so the
     *  driver opts in and is responsible for local legality. Data is read from
     *  the offline OpenStreetMap region database. */
    /** Choice of how the speed limit is displayed:
     *  - Persistent (default): keep the last known limit on screen across gaps
     *    where the map/camera has no data — the value only changes when a new
     *    limit is detected.
     *  - Real only: show a limit ONLY where map or camera data exists; blank when
     *    it doesn't. The driver sees exactly what is known, nothing inferred.
     *  A switch (not buried) so the driver is aware which behaviour is active. */
    /** Small on-road quick toggles for camera warnings and parking advice, so
     *  the driver can flip them with a single tap instead of opening Settings.
     *  Positioned top-start; each chip shows its state by colour/label. */
    @Composable
    private fun androidx.compose.foundation.layout.BoxScope.QuickToggles() {
        val prefs = remember { getSharedPreferences("dbm", MODE_PRIVATE) }
        var cam by remember { mutableStateOf(prefs.getBoolean("hazard_speed_cameras", false)) }
        var park by remember { mutableStateOf(prefs.getBoolean("parking_advice", false)) }
        val showLim by (service?.showLimitRoundel
            ?: MutableStateFlow(true)).collectAsState()

        @Composable fun chip(label: String, on: Boolean, onTap: () -> Unit) {
            Box(Modifier.padding(end = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) EnactGreen.copy(alpha = 0.85f)
                                else Color(0x66000000))
                    .clickable { onTap() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text(label, color = if (on) Color.Black else EnactOnSurfaceDim,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Row(Modifier.align(Alignment.TopStart).padding(8.dp)) {
            chip(if (cam) "\uD83D\uDCF7 Cameras On" else "\uD83D\uDCF7 Cameras Off", cam) {
                cam = !cam
                service?.setElement("hazard_speed_cameras", cam)
                    ?: prefs.edit().putBoolean("hazard_speed_cameras", cam).apply()
            }
            chip(if (park) "P Parking On" else "P Parking Off", park) {
                park = !park
                service?.setElement("parking_advice", park)
                    ?: prefs.edit().putBoolean("parking_advice", park).apply()
            }
            chip(if (showLim) "\u26D4 Limit On" else "\u26D4 Limit Off", showLim) {
                service?.setShowLimitRoundel(!showLim)
                    ?: prefs.edit().putBoolean("show_limit_roundel", !showLim).apply()
            }
        }
    }

    @Composable
    private fun SpeedLimitModeRow() {
        val prefs = remember { getSharedPreferences("dbm", MODE_PRIVATE) }
        var persistent by remember {
            mutableStateOf(prefs.getBoolean("persistent_limit", true)) }
        SettingRow("Persistent speed limit (keep last until it changes)", persistent) {
            persistent = it
            service?.setElement("persistent_limit", it)
                ?: prefs.edit().putBoolean("persistent_limit", it).apply()
        }
        Text(if (persistent)
                "Keeps the last known limit on screen where the map/camera has no " +
                "data. The shown limit changes only when a new one is detected."
            else
                "Shows a limit only where map or camera data exists — blank when it " +
                "doesn't. You see only what is known, nothing carried over.",
            color = EnactOnSurfaceDim, fontSize = 10.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
    }

    @Composable
    private fun SpeedCameraToggle() {
        val prefs = remember { getSharedPreferences("dbm", MODE_PRIVATE) }
        var on by remember { mutableStateOf(prefs.getBoolean("hazard_speed_cameras", false)) }
        SettingRow("Speed camera warnings (OpenStreetMap)", on) {
            on = it
            service?.setElement("hazard_speed_cameras", it)
                ?: prefs.edit().putBoolean("hazard_speed_cameras", it).apply()
        }
        if (on) {
            Text("⚠ Dynamic speed-camera warnings are prohibited in some countries. " +
                "You are responsible for ensuring this is legal where you drive. " +
                "Data comes from OpenStreetMap and may be incomplete or outdated.",
                color = EnactWarning, fontSize = 10.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            CameraDistanceControl()
        }
    }

    /** Warning distance for speed cameras. Auto = speed-scaled (~10 s lead, so
     *  faster roads warn earlier). Fixed = a constant distance the driver sets.
     *  Stored as camera_warn_dist_m: 0 means auto, >0 is the fixed metres. */
    @Composable
    private fun CameraDistanceControl() {
        val prefs = remember { getSharedPreferences("dbm", MODE_PRIVATE) }
        var dist by remember { mutableStateOf(prefs.getInt("camera_warn_dist_m", 0)) }
        var fixed by remember { mutableStateOf(dist > 0) }
        // Remember a sensible slider value even while in Auto, so toggling Fixed
        // on doesn't jump from nothing.
        var sliderM by remember { mutableStateOf(if (dist > 0) dist.toFloat() else 300f) }
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(12.dp)).background(EnactSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp)) {
            SettingRow("Fixed warning distance", fixed) {
                fixed = it
                val v = if (it) sliderM.toInt() else 0
                dist = v
                service?.setCameraWarnDistance(v)
                    ?: prefs.edit().putInt("camera_warn_dist_m", v).apply()
            }
            if (fixed) {
                Text("Warn ${sliderM.toInt()} m before a camera",
                    color = EnactOnSurface, fontSize = 13.sp)
                Slider(value = sliderM, onValueChange = { sliderM = it },
                    onValueChangeFinished = {
                        val v = sliderM.toInt()
                        dist = v
                        service?.setCameraWarnDistance(v)
                            ?: prefs.edit().putInt("camera_warn_dist_m", v).apply()
                    },
                    valueRange = 100f..1000f, steps = 17)   // 100..1000 in 50 m steps
            } else {
                Text("Auto: warns about 10 s ahead, so faster roads warn from " +
                    "farther away (150–800 m).",
                    color = EnactOnSurfaceDim, fontSize = 11.sp)
            }
        }
    }

    /** Lower-left overlay: non-speed road signs (no-turn, no-entry, warnings…)
     *  recently seen, kept on screen ~3 s after they leave the frame so the
     *  driver can register turn restrictions etc. at lights or junctions.
     *  Rendered as actual EU-standard sign graphics (transparent outside the
     *  sign shape), not text labels. */
    @Composable
    private fun androidx.compose.foundation.layout.BoxScope.RecentSignsOverlay() {
        val sgns by (service?.recognisedSigns
            ?: MutableStateFlow(emptyList<com.rfsat.dms.RecognisedSign>()))
            .collectAsState()
        // remember classId -> last-seen time; drop speed-limit (shown as roundel)
        val seen = remember { mutableStateMapOf<Int, Long>() }
        val now = System.currentTimeMillis()
        sgns.forEach { s ->
            if (s.classId != SignDetector.SPEED_LIMIT_ID && s.score >= 0.5f)
                seen[s.classId] = now
        }
        var tick by remember { mutableStateOf(0L) }
        LaunchedEffect(Unit) {
            while (true) { kotlinx.coroutines.delay(500); tick = System.currentTimeMillis() }
        }
        val holdMs = 3000L
        val active = seen.filter { (tick.coerceAtLeast(now)) - it.value < holdMs }
            .keys.toList().takeLast(3)
        seen.keys.toList().forEach { k -> if (now - (seen[k] ?: 0) > holdMs + 2000) seen.remove(k) }

        if (active.isNotEmpty()) {
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                active.forEach { classId ->
                    val res = signDrawable(classId)
                    if (res != 0) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(res),
                            contentDescription = null,
                            modifier = Modifier.padding(top = 4.dp).size(77.dp))
                    }
                }
            }
        }
    }

    /** Map a detector class id to its EU sign drawable. */
    private fun signDrawable(classId: Int): Int = when (classId) {
        0 -> R.drawable.sign_no_left_turn
        1 -> R.drawable.sign_no_right_turn
        2 -> R.drawable.sign_no_u_turn
        3 -> R.drawable.sign_no_straight
        4 -> R.drawable.sign_no_turns
        5 -> R.drawable.sign_no_overtaking
        6 -> R.drawable.sign_no_entry
        7 -> R.drawable.sign_stop
        8 -> R.drawable.sign_yield
        9 -> R.drawable.sign_speed_limit
        10 -> R.drawable.sign_end_limit
        11 -> R.drawable.sign_keep_right
        12 -> R.drawable.sign_keep_left
        13 -> R.drawable.sign_roundabout
        14 -> R.drawable.sign_ahead_only
        15 -> R.drawable.sign_pedestrians
        16 -> R.drawable.sign_children
        17 -> R.drawable.sign_roadworks
        18 -> R.drawable.sign_curve_left
        19 -> R.drawable.sign_curve_right
        20 -> R.drawable.sign_slippery_road
        else -> 0
    }

    @Composable
    private fun MapManagerSection() {
        val scope = rememberCoroutineScope()
        val repo = remember { com.rfsat.dms.maps.MapRepository(this) }
        val downloader = remember { com.rfsat.dms.maps.MapDownloader(repo) }
        var statuses by remember {
            mutableStateOf<List<com.rfsat.dms.maps.RegionStatus>>(emptyList()) }
        var note by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var pending by remember {
            mutableStateOf<com.rfsat.dms.maps.MapRegion?>(null) }
        var pendingCountry by remember { mutableStateOf<String?>(null) }
        var toDelete by remember {
            mutableStateOf<com.rfsat.dms.maps.MapRegion?>(null) }
        var info by remember {
            mutableStateOf<com.rfsat.dms.maps.MapRegion?>(null) }
        var expandedCountries by remember { mutableStateOf(setOf<String>()) }
        // Which region id is currently downloading, and its progress (0..1, or
        // -1 for indeterminate phases like verifying). Lets us show a per-region
        // progress bar and disable only THAT region's button — not all of them.
        // Concurrent downloads: track progress per region id so several maps can
        // download at once, each with its own progress bar. -1f = indeterminate
        // (connecting/verifying), 0..1 = fraction, absent = not downloading.
        val downloadFracs = remember { androidx.compose.runtime.mutableStateMapOf<String, Float>() }
        val downloadMsgs = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
        val progressMsg by mapImportStatus.collectAsState()
        val indexUrl = "https://www.rfsat.com/products/maps/index.json"
        val bordersUrl = "https://www.rfsat.com/products/maps/borders.json"
        var borders by remember {
            mutableStateOf<com.rfsat.dms.maps.RegionBorders?>(null) }

        fun applyCatalog(cat: com.rfsat.dms.maps.MapCatalog) {
            statuses = repo.statusFor(cat)
            note = "${statuses.size} regions • updated ${cat.updated}"
            currentCatalog = cat
        }

        // Manual refresh (the "Check rfsat.com" button): shows progress, fetches
        // and caches, and reports if it couldn't reach the server.
        fun refresh() {
            busy = true; note = "Checking rfsat.com…"
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val cat = downloader.fetchAndCacheCatalog(indexUrl)
                runOnUiThread {
                    busy = false
                    if (cat == null) note = "Could not reach the map server."
                    else applyCatalog(cat)
                }
            }
        }

        // On open: show the cached list instantly (no waiting on the network),
        // then refresh in the BACKGROUND. If the server has a newer catalogue the
        // list updates silently and a small toast notes it. On a fresh install
        // with no cache, do a one-time foreground fetch (brief load).
        LaunchedEffect(Unit) {
            val cached = withContext(kotlinx.coroutines.Dispatchers.IO) {
                downloader.loadCachedCatalog()
            }
            if (cached != null) {
                applyCatalog(cached)                     // instant, from cache
                // silent background refresh
                val fresh = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    downloader.fetchAndCacheCatalog(indexUrl)
                }
                if (fresh != null && fresh.updated != cached.updated) {
                    applyCatalog(fresh)
                    android.widget.Toast.makeText(
                        this@MainActivity, "Map list updated",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            } else {
                // fresh install: one-time foreground fetch with a brief indicator
                busy = true; note = "Loading map list…"
                val fresh = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    downloader.fetchAndCacheCatalog(indexUrl)
                }
                busy = false
                if (fresh != null) applyCatalog(fresh)
                else note = "Could not reach the map server. Pull to retry."
            }
            // Region borders (borders.json): show cached instantly, then refresh
            // silently in the background. Purely for the location preview, so a
            // failure is invisible — regions without a border use their bbox.
            borders = withContext(kotlinx.coroutines.Dispatchers.IO) {
                downloader.loadCachedBorders()
            }
            val freshBorders = withContext(kotlinx.coroutines.Dispatchers.IO) {
                downloader.fetchAndCacheBorders(bordersUrl)
            }
            if (freshBorders != null) borders = freshBorders
        }

        fun startDownload(r: com.rfsat.dms.maps.MapRegion) {
            val cat = currentCatalog ?: return
            if (downloadFracs.containsKey(r.id)) return   // already downloading
            downloadFracs[r.id] = -1f
            downloadMsgs[r.id] = "Starting…"
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                downloader.download(cat, r) { p ->
                    when (p) {
                        is com.rfsat.dms.maps.MapDownloader.Progress.Downloading -> {
                            val frac = if (p.total > 0)
                                p.bytes.toFloat() / p.total.toFloat() else -1f
                            runOnUiThread {
                                downloadFracs[r.id] = frac
                                downloadMsgs[r.id] = "%d / %d MB".format(
                                    p.bytes / 1_000_000, p.total / 1_000_000)
                            }
                        }
                        com.rfsat.dms.maps.MapDownloader.Progress.Verifying ->
                            runOnUiThread {
                                downloadFracs[r.id] = -1f
                                downloadMsgs[r.id] = "Verifying…"
                            }
                        com.rfsat.dms.maps.MapDownloader.Progress.Done ->
                            runOnUiThread {
                                downloadMsgs[r.id] = "Ready"
                                mapImportStatus.value =
                                    "${r.name} ready. Restart monitoring to load it."
                            }
                        is com.rfsat.dms.maps.MapDownloader.Progress.Failed ->
                            runOnUiThread {
                                downloadMsgs[r.id] = "Failed: ${p.reason}"
                                mapImportStatus.value =
                                    "${r.name} download failed: ${p.reason}"
                            }
                    }
                }
                // clear this region's transient state and refresh install status
                runOnUiThread {
                    downloadFracs.remove(r.id)
                    downloadMsgs.remove(r.id)
                    refresh()
                }
            }
        }

        // Download every not-yet-installed region for a country (all sub-regions,
        // and/or the whole-country file if the catalogue has one). Each starts its
        // own concurrent download via startDownload, so they progress in parallel
        // with individual progress bars.
        fun startDownloadCountry(country: String) {
            statuses.filter {
                it.region.country == country &&
                it.state != com.rfsat.dms.maps.MapState.INSTALLED &&
                it.state != com.rfsat.dms.maps.MapState.UNSUPPORTED_SCHEMA
            }.forEach { startDownload(it.region) }
        }

        // confirmation dialog for a (possibly large) download / update
        pending?.let { r ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text("Download ${r.name}?") },
                text = { Text("${r.name} map (data ${r.dataDate}), about " +
                    "%.0f MB. Use Wi-Fi to avoid mobile data charges."
                    .format(r.sizeBytes / 1e6)) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        startDownload(r); pending = null }) { Text("Download") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        pending = null }) { Text("Cancel") }
                })
        }

        // confirmation for downloading a whole country (all its sub-regions)
        pendingCountry?.let { country ->
            val group = statuses.filter {
                it.region.country == country &&
                it.state != com.rfsat.dms.maps.MapState.INSTALLED &&
                it.state != com.rfsat.dms.maps.MapState.UNSUPPORTED_SCHEMA }
            val n = group.size
            val mb = group.sumOf { it.region.sizeBytes } / 1e6
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingCountry = null },
                title = { Text("Download all of $country?") },
                text = { Text("$n map${if (n == 1) "" else "s"}, about %.0f MB total. "
                    .format(mb) +
                    "They download together, each with its own progress bar. " +
                    "Use Wi-Fi to avoid mobile data charges.") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        startDownloadCountry(country); pendingCountry = null }) {
                        Text("Download all") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        pendingCountry = null }) { Text("Cancel") }
                })
        }

        // confirmation dialog for deleting an installed map (frees phone space)
        toDelete?.let { r ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { toDelete = null },
                title = { Text("Delete ${r.name} map?") },
                text = { Text("This removes the downloaded ${r.name} map from the " +
                    "phone to free space. You can download it again later.") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        repo.delete(r); toDelete = null; refresh()
                    }) { Text("Delete", color = Color(0xFFE57373)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        toDelete = null }) { Text("Cancel") }
                })
        }

        // Region info + location preview (Geofabrik-style: what's inside the map,
        // when it was built, and where the region sits on a simple Europe outline).
        info?.let { r ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { info = null },
                title = { Text(r.name, fontSize = 16.sp) },
                text = {
                    Column {
                        // Location preview: the region's bounding box on Europe.
                        // Zoom-out target: parent-country extent for a sub-region,
                        // or null (full Europe) for a standalone map.
                        RegionPreview(
                            r.id, r.bounds, borders?.border(r.id),
                            parentBorder = if (r.id.contains("__"))
                                borders?.border(r.id.substringBefore("__"))
                            else null)
                        Spacer(Modifier.height(10.dp))
                        val row: @Composable (String, String) -> Unit = { k, v ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(k, color = EnactOnSurfaceDim, fontSize = 12.sp)
                                Text(v, color = EnactOnSurface, fontSize = 12.sp)
                            }
                        }
                        fun fmt(n: Int) = "%,d".format(n)
                        row("Roads", fmt(r.roads))
                        row("…with speed limit", fmt(r.roadsWithLimit))
                        row("Parking areas", fmt(r.parkingLots))
                        row("Curbside rules", fmt(r.parkingCurb))
                        row("Speed cameras", fmt(r.speedCameras))
                        androidx.compose.material3.HorizontalDivider(
                            Modifier.padding(vertical = 6.dp),
                            color = EnactOnSurfaceDim.copy(alpha = 0.3f))
                        row("Map data date", r.dataDate.ifEmpty { "—" })
                        row("Version", "v${r.version}")
                        row("Download size",
                            if (r.sizeBytes > 0) "%.0f MB".format(r.sizeBytes / 1e6) else "—")
                        if (r.bounds != null) {
                            val b = r.bounds!!
                            row("Extent (lat)", "%.2f…%.2f".format(b[0], b[2]))
                            row("Extent (lon)", "%.2f…%.2f".format(b[1], b[3]))
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        info = null }) { Text("Close") }
                })
        }

        Column(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)).background(EnactSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Speed-limit maps", color = EnactOnSurface, fontSize = 13.sp)
            Text("Offline maps give speed limits from the map; the camera only " +
                "corrects them. Download the region(s) you drive.",
                color = EnactOnSurfaceDim, fontSize = 11.sp)

            Row {
                androidx.compose.material3.OutlinedButton(
                    onClick = { refresh() }, enabled = !busy,
                    modifier = Modifier.padding(top = 4.dp, end = 8.dp)) {
                    Text("Check for maps")
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        runCatching { importMapLauncher.launch(arrayOf("*/*")) }
                    }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Import file…")
                }
            }
            if (note.isNotEmpty())
                Text(note, color = EnactOnSurface, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp))

            // Per-region download progress is shown inside each region's row
            // (see below), so multiple maps can download at once each with its
            // own bar. Here we only keep the final ready/failed status line.
            if (progressMsg.isNotEmpty() &&
                       (progressMsg.contains("ready") || progressMsg.contains("failed"))) {
                Text(progressMsg, color = EnactOnSurfaceDim, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }

            // Group regions by country, with a collapsible header per country.
            // With the full Geofabrik catalogue (~500 regions) the list must not
            // render every row at once, so countries start collapsed; tap a
            // country to expand its regions. Installed regions are greyed out;
            // their only action is Delete to free space.
            val byCountry = statuses.groupBy { it.region.country }
                .toSortedMap()
            for ((country, regionStatuses) in byCountry) {
                val installedCount = regionStatuses.count {
                    it.state == com.rfsat.dms.maps.MapState.INSTALLED ||
                    it.state == com.rfsat.dms.maps.MapState.UPDATE_AVAILABLE }
                // A country "has sub-regions" when its entries use the id__sub form.
                // Standalone countries (e.g. Greece) have a single entry whose id
                // has no "__": tapping the header opens its info window directly
                // instead of expanding a (non-existent) sub-list.
                val hasSubRegions = regionStatuses.any { it.region.id.contains("__") }
                val standalone = if (!hasSubRegions) regionStatuses.firstOrNull() else null
                val expanded = expandedCountries.contains(country)
                // total download size across the whole country (all sub-regions,
                // or the single whole-country file), and whether any is downloading.
                val totalBytes = regionStatuses.sumOf { it.region.sizeBytes }
                val anyDownloadingHere = regionStatuses.any {
                    downloadFracs.containsKey(it.region.id) }
                Row(Modifier.fillMaxWidth()
                        .clickable {
                            val sa = standalone
                            if (sa != null) {
                                info = sa.region                // open info directly
                            } else {
                                expandedCountries = if (expanded)
                                    expandedCountries - country
                                else expandedCountries + country
                            }
                        }
                        .padding(top = 10.dp, bottom = 2.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                standalone != null -> country
                                expanded -> "\u25BE $country  (regions)"
                                else -> "\u25B8 $country  (regions)"
                            },
                            color = EnactLime, fontSize = 12.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        // downloaded marker on a subtle second line (size moved to
                        // the name line, at right, to align with the button column)
                        if (installedCount > 0)
                            Text("\u2713 $installedCount downloaded",
                                color = EnactOnSurfaceDim, fontSize = 10.sp)
                    }
                    // total size on the name line, right-aligned before the button
                    if (totalBytes > 0)
                        Text("%.0f MB".format(totalBytes / 1e6),
                            color = EnactOnSurfaceDim, fontSize = 10.sp,
                            modifier = Modifier.padding(end = 8.dp))
                    // Country-level download control, in a fixed-width box so it
                    // lines up with the sub-region action buttons below. For a
                    // standalone country it downloads that one map; for a country
                    // with sub-regions it downloads ALL of them.
                    Box(Modifier.width(76.dp),
                        contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
                    androidx.compose.material3.TextButton(
                        enabled = !anyDownloadingHere,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp),
                        onClick = {
                            val sa = standalone
                            if (sa != null) pending = sa.region
                            else pendingCountry = country
                        }) {
                        Text(if (anyDownloadingHere) "…"
                             else if (standalone != null) "Get" else "Get all",
                            fontSize = 12.sp)
                    }
                    }
                }
                if (standalone != null || !expanded) continue
                for (st in regionStatuses) {
                    val r = st.region
                    val installed = st.state == com.rfsat.dms.maps.MapState.INSTALLED
                    val isDownloadingThis = downloadFracs.containsKey(r.id)
                    val thisFrac = downloadFracs[r.id] ?: -1f
                    val thisMsg = downloadMsgs[r.id] ?: ""
                    val label = when (st.state) {
                        com.rfsat.dms.maps.MapState.INSTALLED -> "\u2713 Downloaded (v${r.version})"
                        com.rfsat.dms.maps.MapState.UPDATE_AVAILABLE ->
                            "\u2713 Downloaded • update available (v${r.version}, ${r.dataDate})"
                        com.rfsat.dms.maps.MapState.NOT_INSTALLED -> "Not downloaded"
                        com.rfsat.dms.maps.MapState.UNSUPPORTED_SCHEMA -> "Needs app update"
                    }
                    val labelColor = when (st.state) {
                        com.rfsat.dms.maps.MapState.INSTALLED,
                        com.rfsat.dms.maps.MapState.UPDATE_AVAILABLE -> EnactGreen
                        else -> EnactOnSurfaceDim
                    }
                    val nameColor = if (installed) EnactOnSurfaceDim else EnactOnSurface
                    // Each region is a Column so a per-region progress bar can sit
                    // under its row while it downloads — many can download at once.
                    Column(Modifier.fillMaxWidth().padding(top = 6.dp, start = 10.dp)) {
                    Row(Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, color = nameColor, fontSize = 12.sp)
                            Text(if (isDownloadingThis)
                                    (if (thisMsg.isNotEmpty()) thisMsg else "Downloading…")
                                 else label,
                                color = if (isDownloadingThis) EnactLime else labelColor,
                                fontSize = 10.sp)
                        }
                        // Size on the name line, right-aligned just before the
                        // info icon and action button.
                        if (r.sizeBytes > 0)
                            Text("%.0f MB".format(r.sizeBytes / 1e6),
                                color = EnactOnSurfaceDim, fontSize = 10.sp,
                                modifier = Modifier.padding(end = 8.dp))
                        // Visible, clearly tappable info affordance: a circled "i"
                        // in the accent colour with a subtle tinted background, so
                        // it reads as a button rather than decoration.
                        Box(
                            Modifier
                                .padding(end = 6.dp)
                                .size(28.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(EnactGreen.copy(alpha = 0.18f))
                                .clickable { info = r },
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            Text("\u24D8", color = EnactGreen, fontSize = 18.sp)
                        }
                        // Fixed-width action area so the info icon to its left
                        // keeps the SAME horizontal position across all states
                        // (Download / Update / … / Delete have different widths).
                        // Fixed-width action area (fits "Delete") so buttons align
                        // in a column down the list and the info icon stays put.
                        Box(Modifier.width(76.dp),
                            contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
                        val tightPad = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp)
                        when (st.state) {
                            com.rfsat.dms.maps.MapState.NOT_INSTALLED,
                            com.rfsat.dms.maps.MapState.UPDATE_AVAILABLE ->
                                androidx.compose.material3.TextButton(
                                    // Only THIS region's button is disabled while it
                                    // downloads; other regions can still be started,
                                    // so multiple downloads run concurrently.
                                    enabled = !isDownloadingThis,
                                    contentPadding = tightPad,
                                    onClick = { pending = r }) {
                                    Text(if (isDownloadingThis) "…"
                                        else if (st.state == com.rfsat.dms.maps.MapState.UPDATE_AVAILABLE)
                                            "Update" else "Get", fontSize = 12.sp)
                                }
                            com.rfsat.dms.maps.MapState.INSTALLED ->
                                androidx.compose.material3.TextButton(
                                    contentPadding = tightPad,
                                    onClick = { toDelete = r }) {
                                    Text("Delete", color = Color(0xFFE57373),
                                        fontSize = 12.sp)
                                }
                            else -> {}
                        }
                        }
                    }
                    // Per-region download progress bar — visible for THIS region
                    // while it downloads, so large maps show clear progress and
                    // several can download at once each with its own bar.
                    if (isDownloadingThis) {
                        if (thisFrac >= 0f)
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { thisFrac },
                                color = EnactGreen,
                                modifier = Modifier.fillMaxWidth()
                                    .padding(top = 2.dp, end = 6.dp))
                        else
                            androidx.compose.material3.LinearProgressIndicator(
                                color = EnactGreen,
                                modifier = Modifier.fillMaxWidth()
                                    .padding(top = 2.dp, end = 6.dp))
                    }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    private var currentCatalog: com.rfsat.dms.maps.MapCatalog? = null

    /**
     * For a sub-region id like "czech-republic__jihocesky", returns the bounding
     * box of the whole parent ("czech-republic") by unioning the bboxes of every
     * sibling sub-region sharing that prefix — so zooming out on a sub-region
     * settles on the parent-country area. Returns null for a standalone map (no
     * "__" in the id) or when no sibling bboxes are available, meaning "zoom out
     * to the full Europe extent". Bounds order matches MapRegion.bounds:
     * [minLat, minLon, maxLat, maxLon].
     */
    @Composable
    private fun MirrorIntervalSliders() {
        val prefs = remember { getSharedPreferences("dbm", MODE_PRIVATE) }
        var rear by remember { mutableStateOf(prefs.getInt("mirror_rearview_sec", 120).toFloat()) }
        var side by remember { mutableStateOf(prefs.getInt("mirror_side_sec", 120).toFloat()) }
        Column(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)).background(EnactSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Mirror-check reminders", color = EnactOnSurface, fontSize = 13.sp)
            Text("Warn if no glance toward a mirror for this long. Set to 0 to " +
                "disable that reminder.", color = EnactOnSurfaceDim, fontSize = 11.sp)
            Text("Rearview mirror: ${if (rear.toInt()==0) "off" else "${rear.toInt()} s"}",
                color = EnactOnSurface, fontSize = 12.sp)
            Slider(value = rear, onValueChange = { rear = it },
                onValueChangeFinished = {
                    service?.setMirrorIntervals(rear.toInt(), side.toInt())
                        ?: prefs.edit().putInt("mirror_rearview_sec", rear.toInt()).apply()
                },
                valueRange = 0f..300f, steps = 29)
            Text("Side mirrors: ${if (side.toInt()==0) "off" else "${side.toInt()} s"}",
                color = EnactOnSurface, fontSize = 12.sp)
            Slider(value = side, onValueChange = { side = it },
                onValueChangeFinished = {
                    service?.setMirrorIntervals(rear.toInt(), side.toInt())
                        ?: prefs.edit().putInt("mirror_side_sec", side.toInt()).apply()
                },
                valueRange = 0f..300f, steps = 29)
        }
        Spacer(Modifier.height(8.dp))
    }

    @Composable
    private fun DriverViewZoomSlider() {
        val prefs = remember { getSharedPreferences("dbm", MODE_PRIVATE) }
        Column(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)).background(EnactSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Driver view — zoom out", color = EnactOnSurface, fontSize = 13.sp)
            Text("If the phone is mounted close to your face and your head fills " +
                "the driver view, zoom out to show more of the scene. This shrinks " +
                "the on-screen view only; it does not change detection.",
                color = EnactOnSurfaceDim, fontSize = 11.sp)
            Text("Zoom: ${"%.0f".format(driverViewZoom * 100)}%",
                color = EnactOnSurface, fontSize = 12.sp)
            Slider(value = driverViewZoom, onValueChange = { driverViewZoom = it },
                onValueChangeFinished = {
                    prefs.edit().putFloat("driver_view_zoom", driverViewZoom).apply()
                },
                valueRange = 0.5f..1f, steps = 9)
        }
        Spacer(Modifier.height(8.dp))
    }

    @Composable
    private fun LaneCalibrationSliders() {
        val prefs = remember { getSharedPreferences("dbm", MODE_PRIVATE) }
        var horizon by remember { mutableStateOf(prefs.getFloat("lane_horizon", 0f)) }
        var center by remember { mutableStateOf(prefs.getFloat("lane_center", 0f)) }
        var tilt by remember { mutableStateOf(prefs.getFloat("lane_forward_tilt", 0f)) }
        Column(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)).background(EnactSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Lane detection — mount calibration", color = EnactOnSurface, fontSize = 13.sp)
            Text("Adjust if lane tracking is off due to how the phone is " +
                "tilted/mounted. Horizon: move the road area up or down. " +
                "Centre: shift left/right if the mount is not centred. " +
                "Forward tilt: how far the lane lines converge toward the top " +
                "(they are drawn straight, in the lower half, from the bottom edge).",
                color = EnactOnSurfaceDim, fontSize = 11.sp)
            Text("Horizon: ${"%+.2f".format(horizon)} (move road area up/down)",
                color = EnactOnSurface, fontSize = 12.sp)
            Slider(value = horizon, onValueChange = { horizon = it },
                onValueChangeFinished = {
                    service?.setLaneCalibration(horizon, center, tilt)
                        ?: prefs.edit().putFloat("lane_horizon", horizon).apply()
                },
                valueRange = -0.4f..0.4f, steps = 31)
            Text("Centre: ${"%+.2f".format(center)}", color = EnactOnSurface, fontSize = 12.sp)
            Slider(value = center, onValueChange = { center = it },
                onValueChangeFinished = {
                    service?.setLaneCalibration(horizon, center, tilt)
                        ?: prefs.edit().putFloat("lane_center", center).apply()
                },
                valueRange = -0.2f..0.2f, steps = 15)
            Text("Forward tilt: ${"%.2f".format(tilt)} (line convergence toward top)",
                color = EnactOnSurface, fontSize = 12.sp)
            Slider(value = tilt, onValueChange = { tilt = it },
                onValueChangeFinished = {
                    service?.setLaneCalibration(horizon, center, tilt)
                        ?: prefs.edit().putFloat("lane_forward_tilt", tilt).apply()
                },
                valueRange = 0f..1f, steps = 19)
        }
        Spacer(Modifier.height(8.dp))
    }

    @Composable
    private fun ScreenDimSlider() {
        val prefs = remember { getSharedPreferences("dbm", MODE_PRIVATE) }
        // Discrete options in seconds; 0 = never dim.
        val options = listOf(0, 10, 20, 30, 45, 60, 90, 120)
        var idx by remember {
            val cur = prefs.getInt("screen_dim_sec", 30)
            mutableStateOf(options.indexOf(cur).let { if (it < 0) 3 else it }.toFloat())
        }
        val sec = options[idx.toInt().coerceIn(0, options.size - 1)]
        Column(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)).background(EnactSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(if (sec == 0) "Dim screen while monitoring: Off"
                 else "Dim screen after: $sec s of no touch",
                color = EnactOnSurface, fontSize = 13.sp)
            Text("Dims the display to near-black during monitoring to reduce heat " +
                "and battery use. Cameras and detection keep running; audio/voice " +
                "alerts continue. Tap the screen to restore full brightness.",
                color = EnactOnSurfaceDim, fontSize = 11.sp)
            Slider(value = idx, onValueChange = { idx = it },
                onValueChangeFinished = {
                    prefs.edit().putInt("screen_dim_sec", sec).apply()
                    resetDimTimer()
                },
                valueRange = 0f..(options.size - 1).toFloat(),
                steps = options.size - 2)
        }
    }

    @Composable
    private fun StoppingDistanceSlider() {
        val prefs = remember { getSharedPreferences("dbm", MODE_PRIVATE) }
        var pct by remember { mutableStateOf(prefs.getInt("stop_dist_pct", 100).toFloat()) }
        Column(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)).background(EnactSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Required gap: ${pct.toInt()} % of computed stopping distance",
                color = EnactOnSurface, fontSize = 13.sp)
            Text("100 % = dry-road stopping distance at current speed " +
                "(1 s reaction + braking). Increase for wet roads or extra margin.",
                color = EnactOnSurfaceDim, fontSize = 11.sp)
            Slider(value = pct, onValueChange = { pct = it },
                onValueChangeFinished = {
                    val v = pct.toInt()
                    prefs.edit().putInt("stop_dist_pct", v).apply()
                    service?.setStoppingDistanceFactor(v)
                },
                valueRange = 50f..200f, steps = 14)
        }
        Spacer(Modifier.height(8.dp))
    }

    @Composable
    private fun CacheEvictionSlider() {
        val prefs = remember { getSharedPreferences("dbm", MODE_PRIVATE) }
        var n by remember { mutableStateOf(prefs.getInt("cache_evict_misses", 3).toFloat()) }
        Column(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)).background(EnactSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Forget a remembered sign after ${n.toInt()} missed re-reads",
                color = EnactOnSurface, fontSize = 13.sp)
            Text("When a speed-limit sign that was learned on a road can no longer " +
                "be read there this many times in a row, it is forgotten and the " +
                "map value is used again (handles temporary signs being removed).",
                color = EnactOnSurfaceDim, fontSize = 11.sp)
            Slider(value = n, onValueChange = { n = it },
                onValueChangeFinished = {
                    val v = n.toInt()
                    prefs.edit().putInt("cache_evict_misses", v).apply()
                    service?.setCacheEvictMisses(v)
                },
                valueRange = 1f..10f, steps = 8)
        }
        Spacer(Modifier.height(8.dp))
    }

    @Composable
    private fun VideoRecordingRow() {
        val prefs = remember { getSharedPreferences("dbm", MODE_PRIVATE) }
        var on by remember { mutableStateOf(prefs.getBoolean("record_video", false)) }
        SettingRow("Record both videos with overlays", on) {
            on = it
            service?.setVideoRecording(it)
                ?: prefs.edit().putBoolean("record_video", it).apply()
        }
        Text("MP4 files with detections burnt in, stored on this device " +
            "(7-day retention).", color = EnactOnSurfaceDim, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
    }

    @Composable
    private fun WeightSlider(rt: RiskType) {
        val prefs = remember { getSharedPreferences("dbm", MODE_PRIVATE) }
        var w by remember {
            mutableStateOf(prefs.getInt("weight_${rt.name}", rt.scorePenalty).toFloat()) }
        Column(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)).background(EnactSurface)
                .padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text("${rt.description}: ${w.toInt()} pts",
                color = EnactOnSurface, fontSize = 12.sp)
            Slider(value = w, onValueChange = { w = it },
                onValueChangeFinished = {
                    val v = w.toInt()
                    prefs.edit().putInt("weight_${rt.name}", v).apply()
                    service?.setWeight(rt, v)
                },
                valueRange = 0f..25f, steps = 24)
        }
        Spacer(Modifier.height(4.dp))
    }

    /** A simple offline "where is this region" preview: draws Europe's rough
     *  lon/lat frame and highlights the region's bounding box inside it. No map
     *  SDK or network — just enough to show location and extent (Geofabrik shows
     *  a rendered thumbnail; we can't reproduce that offline, so this shows the
     *  bounding box on a plain graticule instead). */
    @Composable
    private fun RegionPreview(
        regionId: String,
        bounds: FloatArray?,
        border: List<List<Pair<Float, Float>>>?,  // region border rings, or null=use bbox
        parentBorder: List<List<Pair<Float, Float>>>? = null  // parent country, drawn behind
    ) {
        // Full Europe extent (zoom = 1 shows all of this).
        val fullWLon = -25f; val fullELon = 45f; val fullSLat = 34f; val fullNLat = 72f
        val fullLonSpan = fullELon - fullWLon      // 70
        val fullLatSpan = fullNLat - fullSLat      // 38

        // Auto-frame: initial scale + center from the region's bbox (with margin),
        // so a small country fills the view instead of being a speck. Keyed on the
        // STABLE region id — NOT the bounds array. (bounds is a computed property
        // that returns a fresh FloatArray each access, so keying remember on it
        // would re-init scale/center on every recomposition and snap the view back
        // to the bbox — which is the "auto-refocus on zoom-out" bug.)
        val (initScale, initCenterLon, initCenterLat) = remember(regionId) {
            if (bounds != null) {
                val minLat = bounds[0]; val minLon = bounds[1]
                val maxLat = bounds[2]; val maxLon = bounds[3]
                val cLon = (minLon + maxLon) / 2f
                val cLat = (minLat + maxLat) / 2f
                val bLonSpan = (maxLon - minLon).coerceAtLeast(0.5f) * 1.6f  // 60% margin
                val bLatSpan = (maxLat - minLat).coerceAtLeast(0.5f) * 1.6f
                // scale so the bbox+margin fits both axes; clamp to [1, 12]
                val s = minOf(fullLonSpan / bLonSpan, fullLatSpan / bLatSpan)
                    .coerceIn(1f, 12f)
                Triple(s, cLon, cLat)
            } else {
                Triple(1f, (fullWLon + fullELon) / 2f, (fullSLat + fullNLat) / 2f)
            }
        }

        // Zoom-OUT target (double-tap / minimum zoom) is ALWAYS the full Europe
        // view, for every region and sub-region — so you can always pull back to
        // see where it sits in Europe.
        val minScale = 1f
        val outCenterLon = (fullWLon + fullELon) / 2f
        val outCenterLat = (fullSLat + fullNLat) / 2f

        var scale by remember(regionId) { mutableStateOf(initScale) }
        var centerLon by remember(regionId) { mutableStateOf(initCenterLon) }
        var centerLat by remember(regionId) { mutableStateOf(initCenterLat) }

        Canvas(
            Modifier.fillMaxWidth().height(150.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0E2233))     // sea
                .pointerInput(regionId) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        // pinch: multiply scale, clamp. Lower bound is minScale so
                        // zooming OUT stops at the sensible default — the parent-
                        // country area for a sub-region, or full Europe (minScale=1)
                        // for a standalone map — rather than always going to Europe.
                        val newScale = (scale * zoom).coerceIn(minScale, 40f)
                        // visible spans at the new scale
                        val lonSpan = fullLonSpan / newScale
                        val latSpan = fullLatSpan / newScale
                        // drag: pan moves the centre opposite to finger motion.
                        // Convert pixel pan to degrees using current viewport size.
                        val degPerPxX = lonSpan / size.width
                        val degPerPxY = latSpan / size.height
                        var newCenterLon = centerLon - pan.x * degPerPxX
                        var newCenterLat = centerLat + pan.y * degPerPxY  // y inverted
                        // keep the view within the full Europe extent
                        val halfLon = lonSpan / 2f; val halfLat = latSpan / 2f
                        newCenterLon = newCenterLon.coerceIn(
                            fullWLon + halfLon, fullELon - halfLon)
                        newCenterLat = newCenterLat.coerceIn(
                            fullSLat + halfLat, fullNLat - halfLat)
                        scale = newScale
                        centerLon = newCenterLon
                        centerLat = newCenterLat
                    }
                }
                .pointerInput(regionId) {
                    // Double-tap zooms OUT to the sensible default: the parent-
                    // country area for a sub-region, or the full Europe view for a
                    // standalone map.
                    detectTapGestures(onDoubleTap = {
                        scale = minScale
                        centerLon = outCenterLon
                        centerLat = outCenterLat
                    })
                }
        ) {
            val w = size.width; val h = size.height
            // visible window derived from scale + centre
            val lonSpan = fullLonSpan / scale
            val latSpan = fullLatSpan / scale
            val wLon = centerLon - lonSpan / 2f
            val eLon = centerLon + lonSpan / 2f
            val sLat = centerLat - latSpan / 2f
            val nLat = centerLat + latSpan / 2f
            fun xOf(lon: Float) = (lon - wLon) / (eLon - wLon) * w
            fun yOf(lat: Float) = (nLat - lat) / (nLat - sLat) * h
            // --- Europe landmass ----------------------------------------------
            val landFill = Color(0xFF24425A)
            val landEdge = Color(0xFF3C6A88)
            for (poly in EUROPE_LAND) {
                if (poly.size < 3) continue
                val p = androidx.compose.ui.graphics.Path()
                p.moveTo(xOf(poly[0].first), yOf(poly[0].second))
                for (i in 1 until poly.size)
                    p.lineTo(xOf(poly[i].first), yOf(poly[i].second))
                p.close()
                drawPath(p, landFill)
                drawPath(p, landEdge, style = Stroke(width = 1.2f))
            }
            // faint graticule
            val grid = EnactOnSurfaceDim.copy(alpha = 0.15f)
            var g = kotlin.math.ceil(wLon / 10f) * 10f
            while (g <= eLon) { drawLine(grid, Offset(xOf(g), 0f), Offset(xOf(g), h), 1f); g += 10f }
            var gl = kotlin.math.ceil(sLat / 10f) * 10f
            while (gl <= nLat) { drawLine(grid, Offset(0f, yOf(gl)), Offset(w, yOf(gl)), 1f); gl += 10f }
            // --- parent country outline (behind), so a sub-region can be placed
            //     within its country when zoomed out. Drawn dim, outline only.
            if (parentBorder != null && parentBorder.isNotEmpty()) {
                for (ring in parentBorder) {
                    if (ring.size < 3) continue
                    val pp = androidx.compose.ui.graphics.Path()
                    pp.moveTo(xOf(ring[0].first), yOf(ring[0].second))
                    for (i in 1 until ring.size)
                        pp.lineTo(xOf(ring[i].first), yOf(ring[i].second))
                    pp.close()
                    drawPath(pp, EnactOnSurfaceDim.copy(alpha = 0.12f))
                    drawPath(pp, EnactOnSurfaceDim.copy(alpha = 0.55f),
                        style = Stroke(width = 1.5f))
                }
            }
            // --- region highlight: border polygon if we have one, else bbox ---
            if (border != null && border.isNotEmpty()) {
                for (ring in border) {
                    if (ring.size < 3) continue
                    val bp = androidx.compose.ui.graphics.Path()
                    bp.moveTo(xOf(ring[0].first), yOf(ring[0].second))
                    for (i in 1 until ring.size)
                        bp.lineTo(xOf(ring[i].first), yOf(ring[i].second))
                    bp.close()
                    drawPath(bp, EnactGreen.copy(alpha = 0.30f))
                    drawPath(bp, EnactGreen, style = Stroke(width = 2.5f))
                }
            } else if (bounds != null) {
                val minLat = bounds[0]; val minLon = bounds[1]
                val maxLat = bounds[2]; val maxLon = bounds[3]
                val l = xOf(minLon).coerceIn(0f, w); val rt = xOf(maxLon).coerceIn(0f, w)
                val tp = yOf(maxLat).coerceIn(0f, h); val bt = yOf(minLat).coerceIn(0f, h)
                drawRect(EnactGreen.copy(alpha = 0.30f),
                    topLeft = Offset(l, tp), size = Size((rt - l).coerceAtLeast(2f), (bt - tp).coerceAtLeast(2f)))
                drawRect(EnactGreen, topLeft = Offset(l, tp),
                    size = Size((rt - l).coerceAtLeast(2f), (bt - tp).coerceAtLeast(2f)),
                    style = Stroke(width = 2.5f))
            }
        }
        Text(
            if (bounds == null && border == null)
                "(location preview needs an updated map file)"
            else "pinch to zoom · drag to pan · double-tap for full view",
            color = EnactOnSurfaceDim, fontSize = 10.sp,
            modifier = Modifier.padding(top = 2.dp))
    }
    @Composable
    private fun CollapsibleSection(
        title: String,
        startExpanded: Boolean = false,
        content: @Composable () -> Unit
    ) {
        var expanded by rememberSaveable(title) { mutableStateOf(startExpanded) }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (expanded) "\u25BE  $title" else "\u25B8  $title",
                color = EnactGreen, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            content()
        }
    }

    @Composable
    private fun SettingRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)).background(EnactSurface)
                .padding(horizontal = 12.dp, vertical = 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = EnactOnSurface, fontSize = 13.sp)
            Switch(checked = value, onCheckedChange = onChange,
                modifier = Modifier.scale(0.8f))
        }
        Spacer(Modifier.height(4.dp))
    }

    // ---- About tab ----

    @Composable
    private fun AboutScreen() {
        Column(Modifier.fillMaxSize().padding(14.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.verticalGradient(listOf(EnactSurface, EnactDarkMid)))
                    .padding(24.dp),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Driver Behavior Monitor", fontSize = 26.sp,
                        fontWeight = FontWeight.Bold, color = EnactGreen)
                    Row {
                        Text("by RFSAT Limited — ", fontSize = 13.sp,
                            color = EnactOnSurface.copy(alpha = 0.7f))
                        Text("www.rfsat.com", fontSize = 13.sp, color = EnactLime,
                            modifier = Modifier.clickable {
                                startActivity(Intent(Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://www.rfsat.com")))
                            })
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Version ${BuildConfig.VERSION_NAME}",
                        fontSize = 12.sp, color = EnactLime.copy(alpha = 0.9f))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "DBM is a driver-awareness aid. It detects risky driver behaviour, " +
                "road hazards and road-regulation compliance, maintains a " +
                "timestamped evidential record with integrity hashes, and scores " +
                "overall driver compliance.",
                color = EnactOnSurfaceDim, fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Justify)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp)).background(EnactSurface)
                    .padding(12.dp)) {
                Text("Detected issues", color = EnactGreen, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                RiskType.entries
                    .filter { it.implemented }
                    .forEach { rt ->
                        Text("•  ${rt.description}",
                            color = EnactOnSurface, fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 1.dp))
                    }
            }
            Spacer(Modifier.height(12.dp))
            Text("Data processing and storage occurs ONLY on this device.",
                color = EnactOnSurfaceDim, fontSize = 13.sp)
            Text("NO data or information is transmitted to 3rd parties.",
                color = EnactOnSurfaceDim, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text("Copyright (c) RFSAT Limited, 2026",
                color = EnactOnSurfaceDim, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = { exitApp() },
                modifier = Modifier.fillMaxWidth()) {
                Text("Exit", fontSize = 14.sp, color = Color(0xFFE57373))
            }
        }
    }

    @Composable
    private fun PrivacyNotice(onAccept: () -> Unit) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = EnactSurface,
            title = { Text("In-vehicle recording notice", color = EnactGreen) },
            text = {
                Text("This app uses the phone's front camera to monitor the driver and " +
                    "the rear camera to monitor the road ahead, and reads vehicle speed " +
                    "from GPS, to detect risky driving conditions and rate compliance " +
                    "with road regulations. Detection events with image snapshots are " +
                    "stored only on this device and retained for 30 days. Inform all " +
                    "vehicle occupants that recording is active. This is a driver-" +
                    "awareness aid and not a substitute for attentive driving.",
                    color = EnactOnSurface)
            },
            confirmButton = { TextButton(onClick = onAccept) { Text("I understand") } }
        )
    }

    override fun onResume() {
        super.onResume()
        // Returning from another app: re-establish the camera binding, which
        // CameraX dropped when the activity was stopped. Without this the
        // previews come back blank. Safe no-op if cameras aren't set up yet.
        cameras?.resume()
        DLog.i(TAG, "MainActivity onResume (cameras=${cameras != null})")
    }

    override fun onDestroy() {
        cameras?.release()
        runCatching { unbindService(conn) }   // may already be unbound (Exit)
        super.onDestroy()
    }
}

// -----------------------------------------------------------------------------
// Simplified Europe coastline for the offline region-location preview. Derived
// from Natural Earth 110m land data (public domain), clipped to the display frame
// (lon -25..45, lat 34..72) and simplified to ~350 points across 13 landmasses
// (mainland + British Isles, Ireland, Iceland, Scandinavia via the mainland ring,
// Denmark, Italy's islands, Cyprus, Crete, Sicily, the N. Africa coast). Each
// inner list is one landmass as a closed polygon of (lon, lat) points. Coarse but
// a real coastline shape — draws instantly on Canvas with no map SDK or network.
private val EUROPE_LAND: List<List<Pair<Float, Float>>> = listOf(
    listOf(
        35.8f to 36.3f, 36.2f to 36.7f, 34.7f to 36.8f, 34.0f to 36.2f, 32.5f to 36.1f,
        31.7f to 36.6f, 30.6f to 36.7f, 29.7f to 36.1f, 28.7f to 36.7f, 27.6f to 36.7f,
        26.3f to 38.2f, 26.8f to 39.0f, 26.2f to 39.5f, 27.3f to 40.4f, 28.8f to 40.5f,
        29.2f to 41.2f, 31.1f to 41.1f, 33.5f to 42.0f, 35.2f to 42.0f, 38.3f to 40.9f,
        40.4f to 41.0f, 41.6f to 41.5f, 41.5f to 42.6f, 36.7f to 45.2f, 37.4f to 45.4f,
        38.2f to 46.2f, 37.7f to 46.6f, 39.1f to 47.3f, 35.0f to 46.3f, 35.0f to 45.7f,
        36.5f to 45.5f, 36.3f to 45.1f, 33.9f to 44.4f, 33.3f to 44.6f, 33.5f to 45.0f,
        32.5f to 45.3f, 33.6f to 45.9f, 33.3f to 46.1f, 31.7f to 46.3f, 31.7f to 46.7f,
        30.7f to 46.6f, 29.6f to 45.0f, 28.8f to 44.9f, 28.6f to 43.7f, 27.7f to 42.6f,
        28.1f to 41.6f, 29.0f to 41.3f, 28.8f to 41.1f, 27.6f to 41.0f, 26.4f to 40.2f,
        26.1f to 40.8f, 24.9f to 40.9f, 23.7f to 40.7f, 24.4f to 40.1f, 23.9f to 40.0f,
        22.8f to 40.5f, 22.6f to 40.3f, 23.4f to 39.2f, 23.0f to 39.0f, 24.0f to 38.2f,
        24.0f to 37.7f, 23.1f to 37.9f, 23.4f to 37.4f, 22.8f to 37.3f, 23.2f to 36.4f,
        21.7f to 36.8f, 21.1f to 38.3f, 19.4f to 40.3f, 19.5f to 41.7f, 16.0f to 43.5f,
        15.2f to 44.2f, 14.9f to 45.1f, 14.3f to 45.2f, 14.0f to 44.8f, 13.7f to 45.1f,
        13.9f to 45.6f, 12.3f to 45.4f, 12.6f to 44.1f, 15.1f to 42.0f, 15.9f to 42.0f,
        16.2f to 41.7f, 15.9f to 41.5f, 18.5f to 40.2f, 18.3f to 39.8f, 16.9f to 40.4f,
        16.4f to 39.8f, 17.2f to 39.4f, 17.1f to 38.9f, 16.1f to 38.0f, 15.7f to 37.9f,
        16.1f to 39.0f, 15.4f to 40.0f, 12.1f to 41.7f, 10.5f to 42.9f, 10.2f to 43.9f,
        8.9f to 44.4f, 6.5f to 43.1f, 4.6f to 43.4f, 3.1f to 43.1f, 3.0f to 41.9f,
        2.1f to 41.2f, 0.8f to 41.0f, 0.1f to 40.1f, -0.3f to 39.3f, 0.1f to 38.7f,
        -0.7f to 37.6f, -1.4f to 37.4f, -2.1f to 36.7f, -4.4f to 36.7f, -5.4f to 35.9f,
        -5.9f to 36.0f, -6.5f to 36.9f, -8.9f to 36.9f, -8.8f to 38.3f, -9.5f to 38.7f,
        -8.8f to 40.8f, -9.0f to 42.6f, -9.4f to 43.0f, -8.0f to 43.7f, -1.9f to 43.4f,
        -1.4f to 44.0f, -1.2f to 46.0f, -3.0f to 47.6f, -4.5f to 48.0f, -4.6f to 48.7f,
        -1.6f to 48.6f, -1.9f to 49.8f, -1.0f to 49.3f, 1.3f to 50.1f, 1.6f to 50.9f,
        3.8f to 51.6f, 4.7f to 53.1f, 7.1f to 53.7f, 8.1f to 53.5f, 8.8f to 54.0f,
        8.1f to 55.5f, 8.1f to 56.5f, 8.5f to 57.1f, 10.6f to 57.7f, 10.3f to 56.9f,
        10.9f to 56.5f, 9.7f to 55.5f, 9.9f to 54.6f, 11.0f to 54.4f, 10.9f to 54.0f,
        12.5f to 54.5f, 14.1f to 53.8f, 17.6f to 54.9f, 19.7f to 54.4f, 19.9f to 54.9f,
        21.3f to 55.2f, 21.1f to 56.8f, 21.6f to 57.4f, 22.5f to 57.8f, 23.3f to 57.0f,
        24.1f to 57.0f, 24.4f to 58.4f, 23.4f to 58.6f, 23.3f to 59.2f, 25.9f to 59.6f,
        28.0f to 59.5f, 29.1f to 60.0f, 28.1f to 60.5f, 22.9f to 59.8f, 21.3f to 60.7f,
        21.5f to 61.7f, 21.1f to 62.6f, 21.5f to 63.2f, 25.4f to 65.1f, 25.3f to 65.5f,
        23.9f to 66.0f, 22.2f to 65.7f, 21.2f to 65.0f, 21.4f to 64.4f, 17.8f to 62.7f,
        17.1f to 61.3f, 18.8f to 60.1f, 17.9f to 59.0f, 16.8f to 58.7f, 15.9f to 56.1f,
        14.7f to 56.2f, 14.1f to 55.4f, 12.9f to 55.4f, 10.4f to 59.5f, 8.4f to 58.3f,
        7.0f to 58.1f, 5.7f to 58.6f, 5.0f to 62.0f, 10.5f to 64.5f, 14.8f to 67.8f,
        19.2f to 69.8f, 23.0f to 70.2f, 24.5f to 71.0f, 28.2f to 71.2f, 31.3f to 70.5f,
        30.0f to 70.2f, 31.1f to 69.6f, 32.1f to 69.9f, 33.8f to 69.3f, 36.5f to 69.1f,
        41.1f to 67.5f, 41.1f to 66.8f, 38.4f to 66.0f, 33.2f to 66.6f, 34.8f to 65.9f,
        34.9f to 64.4f, 37.0f to 63.8f, 37.1f to 64.3f, 36.5f to 64.8f, 37.2f to 65.1f,
        39.6f to 64.5f, 40.4f to 64.8f, 39.8f to 65.5f, 42.1f to 66.5f, 43.9f to 66.1f,
        44.5f to 66.8f, 43.7f to 67.4f, 44.2f to 68.0f, 43.5f to 68.6f, 45.0f to 68.4f,
        45.0f to 34.0f, 35.5f to 34.0f, 36.1f to 35.8f, 35.8f to 36.3f
    ),
    listOf(
        -2.0f to 57.7f, -3.1f to 56.0f, -2.1f to 55.9f, -1.1f to 54.6f, -0.4f to 54.5f,
        0.5f to 52.9f, 1.7f to 52.7f, 1.6f to 52.1f, 1.1f to 51.8f, 1.4f to 51.3f,
        0.6f to 50.8f, -3.0f to 50.7f, -3.6f to 50.2f, -5.8f to 50.2f, -3.4f to 51.4f,
        -5.0f to 51.6f, -5.3f to 52.0f, -4.2f to 52.3f, -4.8f to 52.8f, -4.6f to 53.5f,
        -3.1f to 53.4f, -2.9f to 54.0f, -3.6f to 54.6f, -4.8f to 54.8f, -5.1f to 55.1f,
        -4.7f to 55.5f, -5.0f to 55.8f, -5.6f to 55.3f, -5.6f to 56.3f, -6.1f to 56.8f,
        -5.8f to 57.8f, -5.0f to 58.6f, -3.0f to 58.6f, -4.1f to 57.6f, -2.0f to 57.7f
    ),
    listOf(
        -18.7f to 63.5f, -22.8f to 64.0f, -21.8f to 64.4f, -24.0f to 64.9f, -22.2f to 65.1f,
        -22.2f to 65.4f, -24.3f to 65.6f, -23.7f to 66.3f, -22.1f to 66.4f, -20.6f to 65.7f,
        -19.1f to 66.3f, -17.8f to 66.0f, -16.2f to 66.5f, -14.5f to 66.5f, -14.7f to 65.8f,
        -13.6f to 65.1f, -14.9f to 64.4f, -18.7f to 63.5f
    ),
    listOf(
        -5.9f to 35.8f, -4.6f to 35.3f, -2.2f to 35.2f, 1.5f to 36.6f, 5.3f to 36.7f,
        6.3f to 37.1f, 8.4f to 36.9f, 9.5f to 37.4f, 10.2f to 37.2f, 10.2f to 36.7f,
        11.1f to 36.9f, 10.6f to 36.4f, 10.9f to 35.7f, 10.3f to 34.0f, -7.1f to 34.0f,
        -5.9f to 35.8f
    ),
    listOf(
        -9.7f to 53.9f, -6.7f to 55.2f, -5.7f to 54.6f, -6.2f to 53.9f, -6.0f to 53.2f,
        -6.8f to 52.3f, -8.6f to 51.7f, -10.0f to 51.8f, -9.2f to 52.9f, -9.7f to 53.9f
    ),
    listOf(
        8.4f to 39.2f, 8.2f to 41.0f, 9.2f to 41.2f, 9.8f to 40.5f, 9.7f to 39.2f,
        8.8f to 38.9f, 8.4f to 39.2f
    ),
    listOf(
        -23.5f to 70.5f, -25.0f to 71.2f, -25.0f to 72.0f, -23.3f to 72.0f, -22.1f to 71.5f,
        -21.8f to 70.7f, -23.5f to 70.5f
    ),
    listOf(
        32.5f to 34.7f, 32.3f to 35.1f, 34.6f to 35.7f, 33.9f to 35.2f, 34.0f to 35.0f,
        32.5f to 34.7f
    ),
    listOf(
        26.3f to 35.3f, 26.2f to 35.0f, 24.7f to 34.9f, 23.5f to 35.3f, 23.7f to 35.7f,
        26.3f to 35.3f
    ),
    listOf(
        15.1f to 36.6f, 12.4f to 37.6f, 12.6f to 38.1f, 15.5f to 38.2f, 15.1f to 36.6f
    ),
    listOf(
        9.4f to 43.0f, 9.6f to 42.2f, 9.2f to 41.4f, 8.5f to 42.3f, 9.4f to 43.0f
    ),
    listOf(
        12.7f to 55.6f, 12.1f to 54.8f, 10.9f to 55.8f, 12.4f to 56.1f, 12.7f to 55.6f
    ),
    listOf(
        -25.0f to 70.2f, -22.3f to 70.1f, -25.0f to 69.3f, -25.0f to 70.2f
    ),
)