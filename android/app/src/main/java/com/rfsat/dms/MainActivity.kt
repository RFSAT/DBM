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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
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
import com.rfsat.dms.ui.theme.EnactSurfaceVar
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

    private val tabs = listOf("About", "Detector", "Summary", "History", "OBD", "Log", "Settings", "Navigation")

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
                edgePadding = 4.dp,
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
                    val sel = tab == i
                    Tab(selected = sel, onClick = { tab = i },
                        selectedContentColor = EnactGreen,
                        unselectedContentColor = EnactOnSurfaceDim,
                        text = {
                            // Each tab sits in its own shaded, rounded pill so
                            // items are clearly distinguishable and can sit closer
                            // together. The selected pill is tinted with the accent.
                            Box(Modifier
                                    .padding(horizontal = 2.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) EnactGreen.copy(alpha = 0.20f)
                                                else EnactSurface)
                                    .padding(horizontal = 10.dp, vertical = 5.dp)) {
                                Text(name, fontSize = 13.sp,
                                    color = if (sel) EnactGreen else EnactOnSurfaceDim,
                                    fontWeight = if (sel) FontWeight.Bold
                                                 else FontWeight.Normal)
                            }
                        })
                }
                // Exit: an action, not a screen. Never "selected"; tapping it
                // fully shuts the app down (stops the foreground service and
                // releases cameras) via the shared exitApp(). Tinted to read as
                // an action rather than another tab.
                Tab(selected = false, onClick = { exitApp() },
                    selectedContentColor = EnactWarning,
                    unselectedContentColor = EnactWarning,
                    text = {
                        Box(Modifier
                                .padding(horizontal = 2.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(EnactWarning.copy(alpha = 0.16f))
                                .padding(horizontal = 10.dp, vertical = 5.dp)) {
                            Text("Exit", fontSize = 13.sp, color = EnactWarning,
                                fontWeight = FontWeight.Bold)
                        }
                    })
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
                7 -> {
                    val prefs0 = getSharedPreferences("dbm", MODE_PRIVATE)
                    val gKey = prefs0.getString("google_maps_key", null)
                    com.rfsat.dms.nav.NavScreen(
                        positionFlow = service?.speed?.position,
                        speedKmhFlow = service?.speed?.speedKmh,
                        headingFlow = service?.speed?.heading,
                        googleApiKey = gKey,
                        cameraArContent = { mod -> NavCameraAr(mod) })
                }
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

    /**
     * Road-camera feed for the navigation camera-AR view. Reuses the SAME
     * roadView PreviewView and detection stream as the Detector's CameraCard, but
     * renders the detections COMPACTLY — small labelled chips docked along the
     * TOP edge — so they don't cover the road, the route, or the AR arrow drawn
     * over the lower part of the frame. Signs/lights/vehicles are summarised as
     * little coloured tags rather than full-size boxes.
     */
    @Composable
    private fun NavCameraAr(modifier: Modifier) {
        val analysing by (service?.analysing
            ?: MutableStateFlow(true)).collectAsState()
        val liveResult by (service?.results?.get(CameraRole.ROAD)
            ?: MutableStateFlow(AnalysisResult())).collectAsState()
        val result = if (analysing) liveResult else AnalysisResult()
        Box(modifier) {
            AndroidView(
                factory = {
                    (roadView.parent as? android.view.ViewGroup)?.removeView(roadView)
                    roadView
                },
                modifier = Modifier.fillMaxSize())
            // Compact detection chips docked top-centre, wrapping horizontally, so
            // the road and route stay clear. Cap the count to avoid clutter.
            val tags = result.detections
                .filter { it.detClass == DetClass.SIGN || it.detClass == DetClass.LIGHT ||
                          it.risky }
                .take(6)
            if (tags.isNotEmpty()) {
                Row(Modifier.align(androidx.compose.ui.Alignment.TopCenter)
                        .padding(top = 132.dp)
                        .horizontalScroll(rememberScrollState())) {
                    tags.forEach { d ->
                        val col = when {
                            d.risky -> Color(0xFFE57373)
                            d.detClass == DetClass.SIGN -> Color(0xFF26C6DA)
                            d.detClass == DetClass.LIGHT -> Color(0xFFEF5350)
                            else -> EnactGreen
                        }
                        Box(Modifier.padding(horizontal = 3.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(col.copy(alpha = 0.85f))
                                .padding(horizontal = 6.dp, vertical = 3.dp)) {
                            Text(if (d.detClass == DetClass.OTHER) d.labelText
                                 else d.detClass.display,
                                color = Color.White, fontSize = 10.sp)
                        }
                    }
                }
            }
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
            CollapsibleSection("Offline maps") {
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
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalMinimumInteractiveComponentSize
                            provides androidx.compose.ui.unit.Dp.Unspecified) {
                    androidx.compose.material3.TextButton(
                        enabled = !anyDownloadingHere,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 32.dp),
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
                    Column(Modifier.fillMaxWidth().padding(top = 2.dp, start = 10.dp)) {
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
                                .size(24.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(EnactGreen.copy(alpha = 0.18f))
                                .clickable { info = r },
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            Text("\u24D8", color = EnactGreen, fontSize = 16.sp)
                        }
                        // Fixed-width action area so the info icon to its left
                        // keeps the SAME horizontal position across all states
                        // (Download / Update / … / Delete have different widths).
                        // Fixed-width action area (fits "Delete") so buttons align
                        // in a column down the list and the info icon stays put.
                        Box(Modifier.width(76.dp),
                            contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.material3.LocalMinimumInteractiveComponentSize
                                provides androidx.compose.ui.unit.Dp.Unspecified) {
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
                                    modifier = Modifier.heightIn(min = 32.dp),
                                    onClick = { pending = r }) {
                                    Text(if (isDownloadingThis) "…"
                                        else if (st.state == com.rfsat.dms.maps.MapState.UPDATE_AVAILABLE)
                                            "Update" else "Get", fontSize = 12.sp)
                                }
                            com.rfsat.dms.maps.MapState.INSTALLED ->
                                androidx.compose.material3.TextButton(
                                    contentPadding = tightPad,
                                    modifier = Modifier.heightIn(min = 32.dp),
                                    onClick = { toDelete = r }) {
                                    Text("Delete", color = Color(0xFFE57373),
                                        fontSize = 12.sp)
                                }
                            else -> {}
                        }
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
        // Separator above every section header so that, when several sections are
        // expanded at once, each one has a clear top boundary and its content
        // doesn't blend into the next header.
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.HorizontalDivider(
            color = EnactSurfaceVar, thickness = 1.dp)
        // Header row sits in a subtly tinted band so it reads as a distinct
        // section start, not just another line of content.
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(EnactDarkMid)
                .clickable { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (expanded) "\u25BE  $title" else "\u25B8  $title",
                color = EnactGreen, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            content()
            Spacer(Modifier.height(6.dp))
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
// from Natural Earth 50m land data (public domain), clipped to the display frame
// (lon -25..45, lat 34..72) and simplified to ~1500 points across 30 landmasses.
// Higher resolution than before so region borders line up with the coastline.
// Each inner list is one landmass as a closed polygon of (lon, lat) points —
// draws instantly on Canvas with no map SDK, tiles, or network.
private val EUROPE_LAND: List<List<Pair<Float, Float>>> = listOf(
    listOf(
        17.96f to 59.36f, 18.62f to 59.33f, 18.41f to 59.29f, 18.29f to 59.11f,
        16.98f to 58.65f, 16.21f to 58.64f, 16.92f to 58.49f, 16.65f to 58.43f,
        16.77f to 58.21f, 16.56f to 57.81f, 16.65f to 57.5f, 16.48f to 57.27f,
        16.53f to 57.07f, 15.92f to 56.17f, 14.71f to 56.13f, 14.75f to 56.03f,
        14.56f to 56.05f, 14.22f to 55.83f, 14.34f to 55.53f, 14.17f to 55.4f,
        12.89f to 55.41f, 12.97f to 55.75f, 12.47f to 56.29f, 12.8f to 56.26f,
        12.66f to 56.44f, 12.86f to 56.45f, 12.88f to 56.62f, 12.42f to 56.91f,
        11.88f to 57.68f, 11.73f to 57.72f, 11.7f to 57.97f, 11.45f to 58.12f,
        11.43f to 58.34f, 11.25f to 58.37f, 11.15f to 58.99f, 11.39f to 59.07f,
        10.83f to 59.18f, 10.64f to 59.39f, 10.6f to 59.76f, 10.57f to 59.59f,
        10.4f to 59.52f, 10.43f to 59.28f, 10.18f to 59.01f, 9.84f to 58.96f,
        9.56f to 59.11f, 9.66f to 58.97f, 8.17f to 58.15f, 7.47f to 58.02f, 7.0f to 58.02f,
        6.88f to 58.15f, 6.73f to 58.07f, 6.56f to 58.12f, 6.66f to 58.26f, 6.05f to 58.38f,
        5.59f to 58.62f, 5.61f to 59.01f, 6.1f to 58.87f, 6.36f to 59.0f, 6.1f to 58.95f,
        5.89f to 59.1f, 5.95f to 59.3f, 6.42f to 59.55f, 5.17f to 59.16f, 5.24f to 59.56f,
        5.47f to 59.71f, 5.77f to 59.66f, 6.22f to 59.82f, 5.73f to 59.86f, 6.35f to 60.35f,
        6.57f to 60.36f, 6.53f to 60.15f, 6.72f to 60.42f, 7.0f to 60.51f, 6.15f to 60.35f,
        5.15f to 59.64f, 5.21f to 60.09f, 5.69f to 60.12f, 5.29f to 60.21f, 5.14f to 60.45f,
        5.65f to 60.69f, 5.12f to 60.64f, 5.01f to 61.04f, 6.78f to 61.14f, 7.04f to 60.95f,
        7.04f to 61.09f, 7.6f to 61.21f, 7.35f to 61.3f, 7.44f to 61.43f, 7.33f to 61.37f,
        7.28f to 61.18f, 6.6f to 61.29f, 6.38f to 61.13f, 5.32f to 61.11f, 4.99f to 61.38f,
        5.34f to 61.49f, 4.91f to 61.81f, 4.99f to 61.9f, 6.02f to 61.79f, 6.73f to 61.87f,
        5.1f to 62.03f, 5.14f to 62.16f, 5.36f to 62.15f, 5.72f to 62.38f, 6.21f to 62.35f,
        6.69f to 62.47f, 6.14f to 62.41f, 6.35f to 62.61f, 7.57f to 62.55f, 7.69f to 62.59f,
        7.54f to 62.67f, 8.1f to 62.73f, 6.73f to 62.72f, 6.94f to 62.93f, 7.57f to 63.1f,
        8.1f to 63.09f, 8.62f to 62.85f, 8.16f to 63.16f, 8.64f to 63.34f, 8.36f to 63.5f,
        8.58f to 63.6f, 9.14f to 63.59f, 9.16f to 63.46f, 9.7f to 63.62f, 10.02f to 63.39f,
        10.76f to 63.46f, 10.73f to 63.62f, 11.37f to 63.8f, 11.18f to 63.9f,
        11.46f to 64.0f, 11.31f to 64.05f, 10.91f to 63.92f, 11.05f to 63.85f,
        10.93f to 63.77f, 10.06f to 63.51f, 9.57f to 63.71f, 10.57f to 64.42f,
        11.63f to 64.81f, 11.3f to 64.75f, 11.49f to 64.98f, 12.16f to 65.18f,
        12.51f to 65.1f, 12.92f to 65.34f, 12.42f to 65.18f, 12.13f to 65.28f,
        12.27f to 65.57f, 12.69f to 65.9f, 13.03f to 65.96f, 12.78f to 66.1f,
        14.03f to 66.3f, 13.12f to 66.23f, 13.07f to 66.43f, 13.21f to 66.64f,
        13.96f to 66.79f, 13.65f to 66.91f, 14.11f to 67.12f, 15.42f to 67.2f,
        14.44f to 67.27f, 14.96f to 67.57f, 15.59f to 67.35f, 15.69f to 67.52f,
        15.25f to 67.6f, 15.3f to 67.77f, 14.78f to 67.67f, 14.8f to 67.81f,
        15.13f to 67.97f, 15.62f to 67.95f, 15.29f to 68.04f, 16.01f to 68.23f,
        16.31f to 67.88f, 16.26f to 68.0f, 16.39f to 68.09f, 16.2f to 68.32f,
        17.55f to 68.43f, 16.51f to 68.53f, 17.39f to 68.8f, 17.7f to 69.1f, 18.1f to 69.16f,
        18.08f to 69.33f, 18.26f to 69.47f, 18.86f to 69.31f, 18.61f to 69.49f,
        18.99f to 69.56f, 19.2f to 69.75f, 19.69f to 69.8f, 19.64f to 69.42f,
        19.96f to 69.82f, 20.32f to 69.95f, 20.34f to 69.62f, 20.11f to 69.34f,
        20.49f to 69.54f, 20.74f to 69.52f, 20.53f to 69.69f, 20.62f to 69.91f,
        21.16f to 69.89f, 21.43f to 70.01f, 21.97f to 69.83f, 21.8f to 70.07f,
        21.36f to 70.23f, 22.68f to 70.37f, 23.35f to 69.98f, 23.38f to 70.25f,
        24.42f to 70.7f, 24.26f to 70.83f, 24.66f to 71.0f, 25.77f to 70.85f,
        25.21f to 70.49f, 24.99f to 70.22f, 25.04f to 70.11f, 26.51f to 70.91f,
        26.73f to 70.85f, 26.56f to 70.67f, 26.64f to 70.64f, 26.59f to 70.41f,
        26.99f to 70.51f, 27.31f to 70.8f, 27.55f to 70.8f, 27.24f to 70.95f,
        27.33f to 71.0f, 28.14f to 71.04f, 28.39f to 70.98f, 28.38f to 70.87f,
        27.9f to 70.68f, 28.27f to 70.67f, 28.19f to 70.25f, 28.83f to 70.86f,
        29.74f to 70.65f, 30.07f to 70.7f, 30.21f to 70.54f, 30.96f to 70.34f,
        30.26f to 70.12f, 28.78f to 70.15f, 29.6f to 69.98f, 29.69f to 69.74f,
        30.09f to 69.72f, 30.24f to 69.86f, 30.43f to 69.72f, 31.55f to 69.7f,
        32.0f to 69.81f, 31.98f to 69.95f, 33.01f to 69.72f, 32.92f to 69.6f,
        32.09f to 69.63f, 32.38f to 69.48f, 33.0f to 69.47f, 32.98f to 69.37f,
        33.45f to 69.43f, 33.33f to 69.15f, 33.14f to 69.07f, 33.68f to 69.31f,
        35.86f to 69.19f, 37.73f to 68.69f, 38.43f to 68.36f, 39.57f to 68.07f,
        39.82f to 68.06f, 39.81f to 68.15f, 40.38f to 67.83f, 40.97f to 67.71f,
        41.13f to 67.27f, 41.36f to 67.21f, 41.19f to 66.83f, 40.1f to 66.3f,
        38.65f to 66.07f, 35.51f to 66.4f, 34.82f to 66.61f, 34.48f to 66.55f,
        34.45f to 66.65f, 33.15f to 66.84f, 32.85f to 67.02f, 32.93f to 67.09f,
        31.9f to 67.16f, 32.5f to 67.0f, 32.46f to 66.92f, 32.86f to 66.72f,
        33.18f to 66.68f, 33.22f to 66.53f, 33.66f to 66.44f, 33.36f to 66.33f,
        34.11f to 66.23f, 34.69f to 65.95f, 34.78f to 65.77f, 34.41f to 65.4f,
        34.8f to 64.99f, 34.95f to 64.76f, 34.87f to 64.56f, 35.04f to 64.44f,
        35.65f to 64.38f, 36.36f to 64.0f, 37.37f to 63.82f, 37.97f to 63.95f,
        38.06f to 64.09f, 37.95f to 64.32f, 37.18f to 64.41f, 36.58f to 64.79f,
        36.53f to 64.94f, 36.79f to 64.99f, 36.88f to 65.17f, 39.76f to 64.58f,
        40.06f to 64.77f, 40.44f to 64.78f, 39.8f to 65.35f, 39.82f to 65.6f,
        40.69f to 65.96f, 41.48f to 66.12f, 42.21f to 66.52f, 43.23f to 66.42f,
        43.65f to 66.25f, 43.54f to 66.12f, 44.1f to 66.01f, 44.1f to 66.24f,
        44.49f to 66.67f, 44.43f to 66.94f, 44.29f to 67.1f, 43.78f to 67.25f,
        44.23f to 68.0f, 44.2f to 68.25f, 43.33f to 68.67f, 44.05f to 68.55f,
        45.0f to 68.58f, 45.0f to 67.51f, 44.9f to 67.41f, 45.0f to 67.33f, 45.0f to 34.0f,
        35.59f to 34.0f, 35.65f to 34.25f, 35.98f to 34.55f, 35.9f to 35.42f,
        35.76f to 35.57f, 35.96f to 36.0f, 35.81f to 36.31f, 36.19f to 36.66f,
        36.05f to 36.91f, 35.39f to 36.58f, 34.7f to 36.82f, 33.69f to 36.18f,
        32.79f to 36.04f, 31.35f to 36.8f, 30.64f to 36.87f, 30.45f to 36.27f,
        29.69f to 36.16f, 29.22f to 36.32f, 28.97f to 36.72f, 28.3f to 36.81f,
        28.02f to 36.63f, 28.08f to 36.75f, 27.45f to 36.71f, 28.01f to 36.83f,
        28.24f to 37.03f, 27.26f to 36.98f, 27.3f to 37.13f, 27.54f to 37.16f,
        27.07f to 37.66f, 27.22f to 37.73f, 27.23f to 37.98f, 26.29f to 38.28f,
        26.42f to 38.37f, 26.44f to 38.64f, 26.67f to 38.34f, 27.14f to 38.45f,
        26.91f to 38.48f, 26.76f to 38.71f, 27.01f to 38.89f, 26.81f to 38.96f,
        26.85f to 39.12f, 26.68f to 39.29f, 26.9f to 39.55f, 26.11f to 39.47f,
        26.18f to 39.99f, 26.74f to 40.4f, 27.28f to 40.46f, 27.73f to 40.33f,
        27.85f to 40.38f, 27.73f to 40.48f, 27.87f to 40.51f, 27.99f to 40.49f,
        27.96f to 40.37f, 29.01f to 40.39f, 28.79f to 40.53f, 29.84f to 40.74f,
        29.11f to 40.94f, 29.15f to 41.22f, 31.25f to 41.11f, 32.31f to 41.73f,
        33.28f to 42.0f, 34.75f to 41.96f, 35.01f to 42.06f, 35.15f to 42.03f,
        35.12f to 41.89f, 35.3f to 41.73f, 36.05f to 41.68f, 36.41f to 41.27f,
        36.78f to 41.36f, 37.07f to 41.18f, 38.38f to 40.92f, 39.43f to 41.11f,
        40.27f to 40.96f, 41.41f to 41.42f, 41.7f to 41.71f, 41.76f to 41.97f,
        41.42f to 42.74f, 39.87f to 43.47f, 38.72f to 44.29f, 38.18f to 44.42f,
        37.85f to 44.7f, 37.5f to 44.7f, 37.2f to 44.97f, 36.63f to 45.15f, 36.94f to 45.29f,
        36.72f to 45.37f, 36.87f to 45.43f, 37.21f to 45.27f, 37.65f to 45.38f,
        37.61f to 45.56f, 37.93f to 46.0f, 38.08f to 45.93f, 38.18f to 46.09f,
        38.49f to 46.09f, 37.91f to 46.41f, 37.77f to 46.64f, 38.5f to 46.66f,
        38.44f to 46.81f, 39.27f to 47.04f, 39.2f to 47.27f, 37.54f to 47.07f,
        36.79f to 46.71f, 35.83f to 46.62f, 35.2f to 46.17f, 35.01f to 46.11f,
        35.28f to 46.28f, 35.23f to 46.44f, 34.85f to 46.19f, 35.02f to 45.7f,
        35.46f to 45.32f, 36.17f to 45.45f, 36.58f to 45.39f, 36.39f to 45.07f,
        35.47f to 45.1f, 35.09f to 44.8f, 34.47f to 44.72f, 33.91f to 44.39f,
        33.45f to 44.55f, 33.61f to 44.91f, 33.56f to 45.1f, 32.51f to 45.4f,
        33.66f to 45.95f, 33.59f to 46.1f, 33.2f to 46.18f, 32.48f to 46.08f,
        31.83f to 46.28f, 32.01f to 46.43f, 31.55f to 46.55f, 32.36f to 46.47f,
        32.58f to 46.62f, 32.04f to 46.64f, 31.94f to 46.98f, 31.76f to 47.21f,
        31.91f to 46.93f, 31.87f to 46.65f, 31.53f to 46.66f, 31.56f to 46.78f,
        31.4f to 46.63f, 30.8f to 46.55f, 30.22f to 45.87f, 29.63f to 45.72f,
        29.73f to 45.34f, 29.56f to 44.84f, 29.05f to 44.76f, 29.1f to 44.98f,
        28.93f to 44.97f, 28.81f to 44.57f, 28.89f to 44.57f, 28.65f to 44.3f,
        28.56f to 43.5f, 28.47f to 43.39f, 28.13f to 43.4f, 27.93f to 43.19f,
        27.89f to 42.75f, 27.48f to 42.47f, 27.98f to 42.05f, 28.2f to 41.55f,
        29.06f to 41.23f, 28.96f to 41.01f, 28.17f to 41.08f, 27.5f to 40.97f,
        27.26f to 40.69f, 26.77f to 40.5f, 26.2f to 40.08f, 26.25f to 40.31f,
        26.79f to 40.63f, 26.11f to 40.61f, 25.86f to 40.84f, 25.1f to 40.99f,
        24.79f to 40.86f, 24.48f to 40.95f, 24.08f to 40.72f, 23.76f to 40.75f,
        23.87f to 40.42f, 24.21f to 40.33f, 24.34f to 40.15f, 23.91f to 40.36f,
        23.73f to 40.33f, 23.97f to 40.11f, 23.95f to 39.97f, 23.66f to 40.22f,
        23.43f to 40.26f, 23.63f to 39.92f, 22.9f to 40.4f, 22.92f to 40.59f,
        22.63f to 40.5f, 22.59f to 40.04f, 23.33f to 39.17f, 23.15f to 39.1f,
        23.16f to 39.26f, 22.92f to 39.31f, 22.89f to 39.17f, 23.07f to 39.04f,
        22.57f to 38.87f, 23.25f to 38.66f, 24.02f to 38.14f, 24.02f to 37.68f,
        23.5f to 38.03f, 23.04f to 37.88f, 23.49f to 37.44f, 23.16f to 37.33f,
        22.73f to 37.54f, 23.16f to 36.45f, 22.72f to 36.79f, 22.43f to 36.48f,
        22.08f to 37.03f, 21.96f to 36.99f, 21.89f to 36.74f, 21.58f to 37.08f,
        21.68f to 37.39f, 21.12f to 37.89f, 21.4f to 38.2f, 21.66f to 38.18f,
        21.82f to 38.33f, 22.92f to 37.96f, 22.89f to 38.05f, 23.12f to 38.07f,
        23.15f to 38.18f, 22.42f to 38.44f, 21.47f to 38.32f, 21.33f to 38.49f,
        21.18f to 38.35f, 20.77f to 38.87f, 21.11f to 38.9f, 21.12f to 39.03f,
        20.78f to 39.01f, 20.3f to 39.33f, 19.85f to 40.04f, 19.4f to 40.28f,
        19.34f to 40.66f, 19.58f to 41.79f, 19.19f to 41.95f, 18.65f to 42.44f,
        17.05f to 43.01f, 17.72f to 42.85f, 16.9f to 43.39f, 15.99f to 43.52f,
        15.19f to 44.17f, 15.12f to 44.26f, 15.47f to 44.27f, 14.98f to 44.6f,
        14.85f to 45.08f, 14.55f to 45.3f, 14.31f to 45.34f, 13.86f to 44.84f,
        13.52f to 45.48f, 13.78f to 45.63f, 13.63f to 45.77f, 13.21f to 45.77f,
        12.27f to 45.45f, 12.23f to 45.24f, 12.52f to 44.97f, 12.25f to 44.72f,
        12.4f to 44.22f, 13.56f to 43.57f, 14.01f to 42.69f, 14.54f to 42.24f,
        15.17f to 41.93f, 16.16f to 41.9f, 15.9f to 41.51f, 17.95f to 40.66f,
        18.46f to 40.22f, 18.34f to 39.82f, 18.08f to 39.94f, 17.87f to 40.28f,
        17.4f to 40.34f, 17.18f to 40.5f, 16.93f to 40.46f, 16.52f to 39.75f,
        17.11f to 39.38f, 17.17f to 39.0f, 16.62f to 38.8f, 16.55f to 38.41f,
        16.06f to 37.94f, 15.65f to 38.03f, 15.88f to 38.61f, 16.2f to 38.76f,
        16.21f to 38.94f, 15.69f to 39.99f, 14.95f to 40.24f, 14.95f to 40.47f,
        14.77f to 40.67f, 14.34f to 40.6f, 14.46f to 40.73f, 14.04f to 40.81f,
        13.73f to 41.24f, 13.09f to 41.24f, 12.63f to 41.47f, 11.64f to 42.29f,
        11.14f to 42.39f, 11.17f to 42.54f, 10.71f to 42.94f, 10.51f to 42.97f,
        10.52f to 43.2f, 10.19f to 43.95f, 8.77f to 44.42f, 8.0f to 43.88f, 7.26f to 43.7f,
        6.57f to 43.2f, 6.12f to 43.07f, 5.41f to 43.23f, 5.06f to 43.44f, 4.71f to 43.37f,
        4.05f to 43.59f, 3.26f to 43.19f, 3.04f to 42.84f, 3.31f to 42.29f, 3.15f to 42.16f,
        3.25f to 41.94f, 2.08f to 41.29f, 1.03f to 41.06f, 0.71f to 40.82f, 0.89f to 40.72f,
        0.6f to 40.61f, -0.33f to 39.52f, -0.2f to 39.06f, 0.2f to 38.76f, -0.52f to 38.32f,
        -0.81f to 37.77f, -0.72f to 37.63f, -1.33f to 37.56f, -1.64f to 37.39f,
        -2.11f to 36.78f, -4.37f to 36.72f, -4.67f to 36.51f, -5.17f to 36.42f,
        -5.36f to 36.13f, -5.63f to 36.03f, -6.04f to 36.19f, -6.38f to 36.64f,
        -6.22f to 36.91f, -6.4f to 36.83f, -6.86f to 37.28f, -7.83f to 37.01f,
        -8.6f to 37.12f, -9.0f to 37.03f, -8.81f to 37.43f, -8.88f to 38.45f,
        -8.67f to 38.42f, -8.8f to 38.52f, -9.21f to 38.45f, -9.25f to 38.66f,
        -9.02f to 38.75f, -8.79f to 39.08f, -9.14f to 38.74f, -9.48f to 38.8f,
        -9.37f to 39.34f, -8.84f to 40.12f, -8.68f to 40.75f, -8.76f to 41.7f,
        -8.89f to 41.76f, -8.78f to 41.94f, -8.88f to 41.95f, -8.89f to 42.11f,
        -8.69f to 42.27f, -8.82f to 42.29f, -8.73f to 42.41f, -8.81f to 42.64f,
        -9.03f to 42.59f, -8.93f to 42.8f, -9.24f to 42.98f, -9.18f to 43.17f,
        -8.36f to 43.4f, -8.26f to 43.58f, -7.7f to 43.76f, -7.06f to 43.55f,
        -5.85f to 43.65f, -4.52f to 43.42f, -3.6f to 43.52f, -3.05f to 43.37f,
        -2.88f to 43.45f, -1.99f to 43.35f, -1.48f to 43.56f, -1.25f to 44.56f,
        -1.08f to 44.69f, -1.15f to 44.76f, -1.25f to 44.67f, -1.08f to 45.53f,
        -0.83f to 45.38f, -0.69f to 45.09f, -0.55f to 45.0f, -0.79f to 45.47f,
        -1.2f to 45.71f, -1.03f to 45.74f, -1.15f to 46.31f, -1.79f to 46.51f,
        -2.09f to 46.87f, -2.02f to 47.04f, -2.15f to 47.22f, -1.74f to 47.22f,
        -2.5f to 47.31f, -2.43f to 47.47f, -2.77f to 47.51f, -2.79f to 47.63f,
        -4.31f to 47.82f, -4.68f to 48.04f, -4.33f to 48.17f, -4.58f to 48.29f,
        -4.24f to 48.3f, -4.72f to 48.36f, -4.72f to 48.54f, -3.23f to 48.84f,
        -2.69f to 48.54f, -2.45f to 48.65f, -2.0f to 48.58f, -1.91f to 48.7f,
        -1.38f to 48.65f, -1.57f to 48.81f, -1.58f to 49.2f, -1.86f to 49.68f,
        -1.26f to 49.68f, -1.14f to 49.39f, -0.16f to 49.3f, 0.42f to 49.45f,
        0.13f to 49.51f, 0.19f to 49.7f, 1.41f to 50.09f, 1.59f to 50.25f, 1.58f to 50.74f,
        1.77f to 50.94f, 3.43f to 51.39f, 4.23f to 51.39f, 3.45f to 51.54f, 4.27f to 51.47f,
        4.0f to 51.6f, 4.18f to 51.61f, 3.95f to 51.81f, 4.03f to 51.93f, 4.48f to 52.31f,
        4.77f to 52.94f, 5.06f to 52.96f, 5.53f to 53.27f, 6.82f to 53.44f, 7.2f to 53.28f,
        7.05f to 53.38f, 7.21f to 53.65f, 7.63f to 53.7f, 8.01f to 53.69f, 8.2f to 53.43f,
        8.33f to 53.61f, 8.5f to 53.39f, 8.62f to 53.88f, 9.21f to 53.86f, 9.59f to 53.6f,
        9.78f to 53.55f, 9.31f to 53.86f, 8.98f to 53.93f, 8.91f to 54.26f, 8.64f to 54.29f,
        8.96f to 54.54f, 8.68f to 54.79f, 8.62f to 55.42f, 8.13f to 55.6f, 8.16f to 56.61f,
        8.67f to 56.5f, 8.89f to 56.74f, 9.2f to 56.7f, 9.25f to 57.01f, 9.11f to 57.04f,
        8.77f to 56.73f, 8.27f to 56.75f, 8.62f to 57.11f, 9.43f to 57.17f, 9.96f to 57.58f,
        10.61f to 57.74f, 10.44f to 57.56f, 10.52f to 57.24f, 10.3f to 57.0f,
        10.28f to 56.62f, 10.93f to 56.44f, 10.75f to 56.24f, 10.32f to 56.21f,
        10.18f to 55.87f, 9.9f to 55.84f, 10.02f to 55.76f, 9.59f to 55.49f, 9.67f to 55.27f,
        9.45f to 55.04f, 9.69f to 55.0f, 9.75f to 54.81f, 10.02f to 54.67f, 9.87f to 54.47f,
        10.73f to 54.32f, 11.01f to 54.38f, 11.06f to 54.28f, 10.85f to 54.01f,
        11.4f to 53.94f, 12.11f to 54.17f, 12.58f to 54.47f, 13.03f to 54.41f,
        13.45f to 54.14f, 13.72f to 54.15f, 13.95f to 53.8f, 14.58f to 53.64f,
        14.56f to 53.82f, 13.93f to 53.88f, 13.83f to 54.13f, 14.38f to 53.92f,
        16.19f to 54.29f, 16.56f to 54.55f, 17.26f to 54.73f, 18.09f to 54.84f,
        18.76f to 54.68f, 18.44f to 54.74f, 18.84f to 54.37f, 19.41f to 54.39f,
        19.86f to 54.63f, 19.97f to 54.92f, 20.68f to 55.1f, 21.11f to 55.62f,
        21.03f to 55.35f, 20.59f to 54.98f, 21.19f to 54.94f, 21.24f to 55.46f,
        21.06f to 55.81f, 21.03f to 56.64f, 21.73f to 57.57f, 22.55f to 57.72f,
        23.29f to 57.09f, 23.65f to 56.97f, 24.38f to 57.25f, 24.3f to 57.78f,
        24.53f to 58.35f, 24.11f to 58.27f, 23.77f to 58.36f, 23.51f to 58.66f,
        23.68f to 58.79f, 23.5f to 58.79f, 23.43f to 58.92f, 23.49f to 59.2f,
        24.08f to 59.29f, 24.05f to 59.37f, 24.38f to 59.47f, 25.44f to 59.52f,
        25.51f to 59.64f, 27.89f to 59.41f, 28.06f to 59.55f, 28.06f to 59.78f,
        28.33f to 59.69f, 28.52f to 59.85f, 28.95f to 59.83f, 29.15f to 60.0f,
        30.12f to 59.87f, 30.17f to 59.96f, 29.72f to 60.2f, 29.07f to 60.19f,
        28.64f to 60.38f, 28.49f to 60.54f, 28.62f to 60.49f, 28.65f to 60.61f,
        28.51f to 60.68f, 26.53f to 60.41f, 26.57f to 60.62f, 26.38f to 60.42f,
        25.96f to 60.47f, 26.04f to 60.34f, 25.76f to 60.27f, 25.66f to 60.33f,
        24.45f to 60.02f, 23.46f to 59.99f, 23.02f to 59.82f, 23.2f to 60.02f,
        22.91f to 60.21f, 22.75f to 60.06f, 22.46f to 60.03f, 22.58f to 60.38f,
        21.44f to 60.6f, 21.36f to 60.97f, 21.57f to 61.48f, 21.5f to 61.55f,
        21.61f to 61.59f, 21.26f to 61.99f, 21.34f to 62.28f, 21.1f to 62.62f,
        21.47f to 63.03f, 21.65f to 63.04f, 21.55f to 63.2f, 22.32f to 63.31f,
        22.24f to 63.44f, 22.53f to 63.65f, 23.6f to 64.04f, 24.56f to 64.8f,
        25.29f to 64.86f, 25.23f to 64.95f, 25.37f to 65.01f, 25.26f to 65.14f,
        25.35f to 65.48f, 24.67f to 65.67f, 24.63f to 65.86f, 23.1f to 65.74f,
        22.4f to 65.86f, 22.25f to 65.6f, 21.57f to 65.41f, 21.61f to 65.26f,
        21.41f to 65.32f, 21.57f to 65.13f, 21.14f to 64.81f, 21.52f to 64.46f,
        21.02f to 64.18f, 20.76f to 63.87f, 18.61f to 63.18f, 18.31f to 63.0f,
        18.5f to 62.99f, 18.46f to 62.9f, 18.17f to 62.79f, 17.88f to 62.87f,
        17.9f to 62.66f, 18.04f to 62.6f, 17.38f to 62.46f, 17.63f to 62.23f,
        17.37f to 61.87f, 17.47f to 61.68f, 17.2f to 61.72f, 17.13f to 61.58f,
        17.25f to 60.7f, 17.66f to 60.54f, 17.96f to 60.59f, 18.85f to 60.03f,
        18.97f to 59.76f, 17.96f to 59.36f
    ),
    listOf(
        -4.02f to 57.91f, -3.86f to 57.82f, -4.13f to 57.58f, -3.29f to 57.71f,
        -1.87f to 57.61f, -1.78f to 57.47f, -2.5f to 56.64f, -3.31f to 56.36f,
        -2.89f to 56.4f, -2.67f to 56.25f, -3.36f to 56.03f, -3.79f to 56.1f,
        -3.05f to 55.95f, -2.6f to 56.03f, -2.15f to 55.9f, -1.66f to 55.57f,
        -1.23f to 54.7f, -0.08f to 54.12f, -0.21f to 54.02f, 0.12f to 53.61f,
        -0.27f to 53.74f, -0.66f to 53.72f, -0.29f to 53.69f, 0.27f to 53.34f,
        0.36f to 53.16f, 0.05f to 52.91f, 0.28f to 52.81f, 0.56f to 52.97f, 1.06f to 52.96f,
        1.66f to 52.75f, 1.75f to 52.47f, 1.59f to 52.12f, 1.23f to 51.97f, 1.19f to 51.8f,
        0.75f to 51.73f, 0.9f to 51.69f, 0.89f to 51.57f, 0.42f to 51.47f, 1.41f to 51.36f,
        1.37f to 51.16f, 0.96f to 50.93f, 0.21f to 50.76f, -1.42f to 50.9f, -1.33f to 50.82f,
        -2.03f to 50.73f, -2.04f to 50.6f, -3.0f to 50.72f, -3.4f to 50.63f,
        -3.68f to 50.24f, -4.19f to 50.39f, -4.73f to 50.29f, -5.12f to 50.04f,
        -5.66f to 50.08f, -4.89f to 50.53f, -4.19f to 51.19f, -3.14f to 51.21f,
        -2.43f to 51.74f, -3.29f to 51.39f, -3.89f to 51.59f, -4.23f to 51.57f,
        -4.09f to 51.66f, -4.39f to 51.74f, -4.9f to 51.63f, -5.26f to 51.88f,
        -4.15f to 52.33f, -3.98f to 52.54f, -4.1f to 52.92f, -4.68f to 52.81f,
        -4.27f to 53.14f, -3.76f to 53.31f, -3.1f to 53.26f, -3.17f to 53.39f,
        -3.06f to 53.43f, -2.92f to 53.31f, -2.75f to 53.31f, -3.06f to 53.51f,
        -2.87f to 54.18f, -3.17f to 54.13f, -3.59f to 54.56f, -3.46f to 54.77f,
        -3.04f to 54.95f, -3.55f to 54.95f, -3.96f to 54.78f, -4.82f to 54.85f,
        -4.91f to 54.69f, -5.14f to 54.86f, -5.17f to 54.99f, -5.06f to 54.99f,
        -4.68f to 55.5f, -4.89f to 55.7f, -4.83f to 55.93f, -4.58f to 55.94f,
        -4.84f to 56.05f, -4.8f to 56.16f, -5.21f to 55.89f, -5.22f to 56.07f,
        -5.0f to 56.23f, -5.38f to 56.02f, -5.56f to 55.39f, -5.77f to 55.36f,
        -5.5f to 55.8f, -5.62f to 55.81f, -5.53f to 56.25f, -5.19f to 56.76f,
        -5.65f to 56.53f, -6.13f to 56.71f, -5.73f to 56.85f, -5.86f to 56.9f,
        -5.59f to 57.1f, -5.56f to 57.23f, -5.82f to 57.44f, -5.58f to 57.55f,
        -5.74f to 57.64f, -5.67f to 57.82f, -5.16f to 57.88f, -5.41f to 58.07f,
        -5.34f to 58.24f, -5.01f to 58.26f, -5.09f to 58.38f, -4.98f to 58.58f,
        -4.43f to 58.51f, -3.05f to 58.63f, -3.21f to 58.32f, -4.02f to 57.91f
    ),
    listOf(
        -14.6f to 66.38f, -15.12f to 66.1f, -14.7f to 66.02f, -14.83f to 65.76f,
        -14.39f to 65.79f, -14.32f to 65.68f, -14.47f to 65.58f, -14.17f to 65.64f,
        -13.62f to 65.52f, -13.8f to 65.35f, -13.64f to 65.28f, -13.75f to 65.19f,
        -13.56f to 65.12f, -13.6f to 65.04f, -13.85f to 64.99f, -14.04f to 64.74f,
        -14.39f to 64.75f, -14.43f to 64.54f, -14.63f to 64.42f, -15.83f to 64.18f,
        -16.64f to 63.87f, -17.82f to 63.71f, -17.95f to 63.54f, -18.65f to 63.41f,
        -20.2f to 63.56f, -20.49f to 63.69f, -20.41f to 63.81f, -20.65f to 63.74f,
        -21.11f to 63.94f, -22.65f to 63.83f, -22.7f to 64.08f, -22.19f to 64.04f,
        -21.46f to 64.38f, -22.05f to 64.31f, -21.9f to 64.39f, -22.0f to 64.41f,
        -21.95f to 64.51f, -21.59f to 64.63f, -22.11f to 64.53f, -22.47f to 64.79f,
        -23.82f to 64.74f, -24.03f to 64.86f, -21.89f to 65.05f, -21.76f to 65.17f,
        -22.51f to 65.2f, -21.84f to 65.45f, -22.9f to 65.58f, -23.9f to 65.41f,
        -24.48f to 65.53f, -24.25f to 65.61f, -23.86f to 65.54f, -24.09f to 65.78f,
        -23.62f to 65.68f, -23.29f to 65.75f, -23.83f to 65.85f, -23.52f to 65.88f,
        -23.78f to 66.02f, -23.43f to 66.02f, -23.6f to 66.11f, -23.45f to 66.18f,
        -23.02f to 65.98f, -22.66f to 66.03f, -22.62f to 65.87f, -22.44f to 65.91f,
        -22.45f to 66.07f, -22.95f to 66.21f, -22.48f to 66.27f, -23.12f to 66.34f,
        -22.89f to 66.44f, -22.43f to 66.43f, -21.41f to 66.03f, -21.52f to 65.97f,
        -21.31f to 65.9f, -21.37f to 65.74f, -21.66f to 65.72f, -21.36f to 65.58f,
        -21.43f to 65.47f, -21.13f to 65.27f, -20.8f to 65.64f, -20.45f to 65.57f,
        -20.36f to 66.03f, -20.21f to 66.1f, -19.49f to 65.77f, -19.38f to 66.08f,
        -18.85f to 66.18f, -18.14f to 65.73f, -18.3f to 66.16f, -17.91f to 66.14f,
        -17.55f to 65.96f, -17.15f to 66.2f, -16.49f to 66.2f, -16.54f to 66.45f,
        -16.25f to 66.52f, -15.99f to 66.51f, -15.54f to 66.23f, -14.6f to 66.38f
    ),
    listOf(
        -6.23f to 55.22f, -5.72f to 54.82f, -5.88f to 54.64f, -5.58f to 54.66f,
        -5.47f to 54.5f, -5.67f to 54.55f, -5.61f to 54.27f, -6.35f to 53.99f,
        -6.03f to 52.93f, -6.46f to 52.35f, -6.33f to 52.25f, -6.89f to 52.16f,
        -6.97f to 52.25f, -8.06f to 51.83f, -8.41f to 51.89f, -8.41f to 51.71f,
        -9.3f to 51.5f, -9.84f to 51.48f, -9.58f to 51.69f, -10.12f to 51.6f,
        -9.6f to 51.87f, -10.34f to 51.8f, -9.91f to 52.12f, -10.36f to 52.21f,
        -9.77f to 52.25f, -9.91f to 52.4f, -9.63f to 52.55f, -8.78f to 52.68f,
        -8.99f to 52.76f, -9.18f to 52.63f, -9.92f to 52.57f, -9.46f to 52.82f,
        -9.3f to 53.1f, -8.93f to 53.21f, -10.09f to 53.41f, -10.12f to 53.55f,
        -9.72f to 53.6f, -9.9f to 53.73f, -9.58f to 53.81f, -9.91f to 53.86f,
        -9.86f to 54.1f, -10.09f to 54.16f, -10.06f to 54.26f, -8.55f to 54.24f,
        -8.62f to 54.35f, -8.13f to 54.64f, -8.76f to 54.68f, -8.38f to 54.89f,
        -8.27f to 55.15f, -7.67f to 55.26f, -7.56f to 55.12f, -7.66f to 54.97f,
        -7.48f to 55.05f, -7.52f to 55.25f, -7.3f to 55.3f, -7.37f to 55.36f,
        -6.96f to 55.24f, -7.18f to 55.06f, -6.95f to 55.18f, -6.23f to 55.22f
    ),
    listOf(
        -5.28f to 35.9f, -5.25f to 35.61f, -4.63f to 35.21f, -3.39f to 35.21f,
        -2.97f to 35.41f, -2.84f to 35.13f, -2.02f to 35.09f, -0.43f to 35.86f,
        -0.05f to 35.83f, 0.31f to 36.16f, 0.97f to 36.44f, 2.59f to 36.6f, 2.97f to 36.78f,
        3.78f to 36.9f, 4.76f to 36.9f, 5.3f to 36.65f, 6.49f to 37.09f, 6.93f to 36.92f,
        7.24f to 36.97f, 7.2f to 37.09f, 7.43f to 37.06f, 7.91f to 36.86f, 8.82f to 37.0f,
        9.69f to 37.34f, 9.84f to 37.31f, 9.83f to 37.14f, 9.88f to 37.25f, 10.2f to 37.21f,
        10.29f to 36.78f, 10.41f to 36.73f, 10.95f to 37.06f, 11.05f to 37.07f,
        11.13f to 36.87f, 10.48f to 36.18f, 10.59f to 35.89f, 11.0f to 35.63f,
        11.12f to 35.24f, 10.69f to 34.68f, 10.12f to 34.28f, 10.08f to 34.0f,
        -6.87f to 34.0f, -6.35f to 34.78f, -5.92f to 35.79f, -5.28f to 35.9f
    ),
    listOf(
        -22.01f to 71.69f, -22.5f to 71.5f, -22.48f to 71.38f, -22.42f to 71.25f,
        -22.3f to 71.43f, -21.75f to 71.48f, -21.52f to 70.53f, -21.63f to 70.47f,
        -22.38f to 70.46f, -22.44f to 70.86f, -22.69f to 70.44f, -23.79f to 70.56f,
        -24.38f to 71.15f, -25.0f to 71.33f, -25.0f to 72.0f, -22.96f to 72.0f,
        -22.01f to 71.69f
    ),
    listOf(
        9.71f to 40.02f, 9.56f to 39.17f, 9.06f to 39.24f, 8.97f to 38.96f, 8.65f to 38.93f,
        8.42f to 39.21f, 8.55f to 39.84f, 8.41f to 39.92f, 8.47f to 40.29f, 8.19f to 40.65f,
        8.22f to 40.91f, 8.57f to 40.85f, 9.23f to 41.26f, 9.46f to 41.15f, 9.79f to 40.56f,
        9.64f to 40.27f, 9.71f to 40.02f
    ),
    listOf(
        12.24f to 55.54f, 12.41f to 55.29f, 12.09f to 55.19f, 12.05f to 54.82f,
        11.86f to 54.77f, 11.65f to 55.19f, 11.29f to 55.2f, 10.98f to 55.72f,
        11.32f to 55.75f, 11.63f to 55.96f, 11.69f to 55.73f, 11.82f to 55.7f,
        11.87f to 55.97f, 12.04f to 56.05f, 12.58f to 56.06f, 12.57f to 55.68f,
        12.24f to 55.54f
    ),
    listOf(
        16.28f to 68.87f, 16.48f to 68.8f, 16.52f to 68.63f, 15.98f to 68.4f,
        14.26f to 68.19f, 14.59f to 68.4f, 15.1f to 68.44f, 15.41f to 68.62f,
        15.56f to 68.87f, 15.44f to 68.92f, 15.48f to 69.04f, 15.89f to 69.28f,
        16.13f to 69.27f, 15.81f to 69.02f, 15.93f to 68.73f, 15.76f to 68.56f,
        16.28f to 68.87f
    ),
    listOf(
        15.11f to 37.38f, 15.29f to 37.01f, 15.11f to 36.69f, 14.78f to 36.71f,
        14.14f to 37.1f, 12.64f to 37.59f, 12.44f to 37.82f, 12.73f to 38.18f,
        12.9f to 38.03f, 13.29f to 38.19f, 13.79f to 37.98f, 15.63f to 38.27f,
        15.23f to 37.78f, 15.11f to 37.38f
    ),
    listOf(
        24.11f to 35.5f, 24.35f to 35.36f, 25.73f to 35.35f, 25.79f to 35.12f,
        26.32f to 35.32f, 26.17f to 35.02f, 24.8f to 34.93f, 24.71f to 35.09f,
        23.56f to 35.3f, 23.57f to 35.53f, 23.74f to 35.66f, 23.85f to 35.54f,
        24.17f to 35.6f, 24.11f to 35.5f
    ),
    listOf(
        -6.43f to 58.02f, -6.96f to 57.75f, -7.08f to 57.81f, -6.86f to 57.92f,
        -7.06f to 58.0f, -7.09f to 58.18f, -6.73f to 58.19f, -6.78f to 58.3f,
        -6.24f to 58.5f, -6.33f to 58.19f, -6.55f to 58.09f, -6.43f to 58.02f
    ),
    listOf(
        24.1f to 38.67f, 24.28f to 38.22f, 24.59f to 38.12f, 24.54f to 37.98f,
        24.21f to 38.12f, 24.04f to 38.39f, 23.65f to 38.44f, 23.25f to 38.8f,
        22.87f to 38.87f, 23.31f to 39.03f, 23.52f to 38.81f, 24.1f to 38.67f
    ),
    listOf(
        18.91f to 57.4f, 18.34f to 56.98f, 18.15f to 56.92f, 18.29f to 57.08f,
        18.11f to 57.27f, 18.14f to 57.56f, 18.54f to 57.83f, 18.96f to 57.9f,
        19.08f to 57.84f, 18.81f to 57.71f, 18.79f to 57.48f, 18.91f to 57.4f
    ),
    listOf(
        9.56f to 42.16f, 9.19f to 41.38f, 8.9f to 41.52f, 8.74f to 41.93f, 8.62f to 41.93f,
        8.7f to 42.1f, 8.57f to 42.36f, 8.81f to 42.61f, 9.31f to 42.71f, 9.42f to 43.02f,
        9.56f to 42.16f
    ),
    listOf(
        18.0f to 69.5f, 17.95f to 69.2f, 17.49f to 69.2f, 17.08f to 69.01f, 16.81f to 69.07f,
        16.97f to 69.14f, 17.0f to 69.36f, 17.36f to 69.38f, 17.23f to 69.48f,
        17.5f to 69.6f, 18.0f to 69.5f
    ),
    listOf(
        33.76f to 34.97f, 33.01f to 34.57f, 32.45f to 34.73f, 32.3f to 35.08f,
        32.88f to 35.18f, 32.94f to 35.39f, 33.61f to 35.35f, 34.56f to 35.66f,
        33.94f to 35.29f, 34.05f to 34.99f, 33.76f to 34.97f
    ),
    listOf(
        -22.21f to 70.11f, -23.03f to 69.9f, -23.05f to 69.79f, -23.87f to 69.74f,
        -23.74f to 69.59f, -24.3f to 69.59f, -24.3f to 69.44f, -25.0f to 69.28f,
        -25.0f to 70.31f, -22.21f to 70.11f
    ),
    listOf(
        23.32f to 58.45f, 22.37f to 58.22f, 22.08f to 57.94f, 21.99f to 58.0f,
        22.19f to 58.15f, 21.85f to 58.3f, 21.98f to 58.39f, 21.86f to 58.5f,
        22.55f to 58.63f, 23.32f to 58.45f
    ),
    listOf(
        -5.67f to 57.25f, -5.99f to 57.04f, -6.03f to 57.2f, -6.32f to 57.2f,
        -6.76f to 57.44f, -6.31f to 57.67f, -6.17f to 57.59f, -6.14f to 57.31f,
        -5.67f to 57.25f
    ),
    listOf(
        -1.05f to 60.44f, -1.36f to 59.91f, -1.29f to 60.15f, -1.66f to 60.26f,
        -1.37f to 60.33f, -1.55f to 60.52f, -1.3f to 60.61f, -1.29f to 60.47f,
        -1.05f to 60.44f
    ),
    listOf(
        19.61f to 70.02f, 18.78f to 69.58f, 18.06f to 69.6f, 18.67f to 69.78f,
        18.69f to 69.89f, 19.05f to 70.04f, 19.13f to 70.24f, 19.34f to 70.01f,
        19.61f to 70.02f
    ),
    listOf(
        13.16f to 54.36f, 13.24f to 54.64f, 13.42f to 54.7f, 13.66f to 54.56f,
        13.58f to 54.46f, 13.73f to 54.32f, 13.36f to 54.25f, 13.16f to 54.36f
    ),
    listOf(
        26.58f to 39.11f, 26.16f to 39.03f, 26.27f to 39.2f, 26.07f to 39.1f,
        25.84f to 39.2f, 26.35f to 39.38f, 26.58f to 39.11f
    ),
    listOf(
        3.46f to 39.7f, 3.07f to 39.3f, 2.37f to 39.61f, 3.2f to 39.96f, 3.15f to 39.79f,
        3.46f to 39.7f
    ),
    listOf(
        10.79f to 55.13f, 9.99f to 55.16f, 9.86f to 55.52f, 10.65f to 55.61f,
        10.82f to 55.32f, 10.79f to 55.13f
    ),
    listOf(
        15.22f to 68.62f, 14.37f to 68.71f, 14.8f to 68.79f, 15.1f to 69.01f,
        15.4f to 68.78f, 15.22f to 68.62f
    ),
    listOf(
        16.41f to 56.57f, 17.03f to 57.35f, 17.12f to 57.32f, 16.43f to 56.24f,
        16.41f to 56.57f
    ),
    listOf(
        22.54f to 58.69f, 22.06f to 58.94f, 22.65f to 59.09f, 23.01f to 58.83f,
        22.54f to 58.69f
    ),
    listOf(
        22.61f to 70.53f, 21.99f to 70.66f, 23.44f to 70.82f, 23.07f to 70.59f,
        22.61f to 70.53f
    ),
)