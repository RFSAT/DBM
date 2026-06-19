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
import androidx.compose.runtime.rememberCoroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object { private const val TAG = "MainActivity" }

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
    private var privacyAccepted = false
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
        permLauncher.launch(perms.toTypedArray())
        setContent { DbmTheme { Surface(Modifier.fillMaxSize()) { Root() } } }
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

    private val tabs = listOf("Detector", "Summary", "History", "Log", "Settings", "About")

    @Composable
    private fun Root() {
        var accepted by remember { mutableStateOf(privacyAccepted) }
        if (!accepted) {
            PrivacyNotice {
                accepted = true
                privacyAccepted = true
                maybeStart()
            }
            return
        }

        var tab by remember { mutableIntStateOf(0) }
        Column(Modifier.fillMaxSize().background(EnactDark).safeDrawingPadding()) {
            ScrollableTabRow(
                selectedTabIndex = tab,
                containerColor = EnactDarkMid,
                contentColor = EnactGreen,
                edgePadding = 8.dp,
                indicator = { pos ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(pos[tab]), color = EnactGreen)
                }
            ) {
                tabs.forEachIndexed { i, name ->
                    Tab(selected = tab == i, onClick = { tab = i },
                        selectedContentColor = EnactGreen,
                        unselectedContentColor = EnactOnSurfaceDim,
                        text = { Text(name, fontSize = 13.sp) })
                }
            }
            when (tab) {
                0 -> DetectorScreen()
                1 -> SummaryScreen(
                        dao = DmsDatabase.get(this@MainActivity).events(),
                        complianceState = (service?.scorer?.state
                            ?: MutableStateFlow(ComplianceState())).collectAsState().value,
                        onResetCounters = { service?.resetCounters() })
                2 -> HistoryScreen(dao = DmsDatabase.get(this@MainActivity).events(),
                        onBack = { tab = 0 })
                3 -> LogScreen()
                4 -> SettingsScreen()
                5 -> AboutScreen()
            }
        }
    }

    // ---- Detector: cameras side by side, detections underneath ----

    @Composable
    private fun DetectorScreen() {
        val portrait = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
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
        Box(modifier.clip(RoundedCornerShape(14.dp)).background(EnactSurface)) {
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
                    val pB = Offset(mx(l.xBottom), my(1f))
                    val pT = Offset(mx(l.xTop), my(result.roiTopFrac))
                    when (l.kind) {
                        LaneLine.Kind.DASHED ->
                            drawLine(col, pB, pT, strokeWidth = 7f,
                                pathEffect = androidx.compose.ui.graphics.PathEffect
                                    .dashPathEffect(floatArrayOf(22f, 18f), 0f))
                        LaneLine.Kind.SOLID ->
                            drawLine(col, pB, pT, strokeWidth = 9f)
                        LaneLine.Kind.DOUBLE_SOLID -> {
                            // two parallel thick lines
                            val dx = 7f
                            drawLine(col, Offset(pB.x - dx, pB.y), Offset(pT.x - dx, pT.y),
                                strokeWidth = 7f)
                            drawLine(col, Offset(pB.x + dx, pB.y), Offset(pT.x + dx, pT.y),
                                strokeWidth = 7f)
                        }
                    }
                }
            }
            // Large, driver-visible speed-limit sign in the lower-right of the
            // road view. Sized ~3-4x the small status-strip roundel so it is
            // readable at a glance while driving.
            if (role == CameraRole.FRONT) {
                val scState by (service?.scorer?.state
                    ?: MutableStateFlow(ComplianceState())).collectAsState()
                val lim = if (analysing) scState.activeSpeedLimitKmh else null
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
            }
            Text(role.label, color = EnactOnSurface, fontSize = 11.sp,
                modifier = Modifier.padding(8.dp)
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
                onClick = {
                    // Fully exit: stop analysis, unbind and stop the foreground
                    // service (otherwise it keeps the app alive), then remove the
                    // task. Without stopping the service, Exit only closes the UI.
                    service?.pauseAnalysis()
                    cameras?.release()
                    runCatching { unbindService(conn) }
                    stopService(Intent(this@MainActivity, MonitorService::class.java))
                    finishAndRemoveTask()
                },
                modifier = Modifier.weight(1f).height(btnHeight),
                contentPadding = tightPad) {
                Text("Exit", fontSize = 13.sp, color = Color(0xFFE57373))
            }
        }
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
            Spacer(Modifier.height(14.dp))
            Text("Detection elements", color = EnactGreen, fontSize = 15.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            DetectionElementRow("Road signs (speed limits)", "det_signs")
            DetectionElementRow("Lane markings (overlay)", "det_lanes")
            DetectionElementRow("Single/double line-crossing events", "det_lane_cross")
            DetectionElementRow("Hard-shoulder driving", "det_shoulder")
            DetectionElementRow("Road objects (vehicles, pedestrians…)", "det_objects")
            DetectionElementRow("Unsafe following distance", "det_distance")
            DetectionElementRow("Traffic lights (red / amber crossing)", "det_lights")
            DetectionElementRow("Driver state (eyes, gaze, mirrors)", "det_driver")
            DetectionElementRow(
                "Read lead-vehicle plate on serious hazard (stored locally only)",
                "capture_plate", default = false)
            Spacer(Modifier.height(14.dp))
            Text("Self-calibration", color = EnactGreen, fontSize = 15.sp,
                fontWeight = FontWeight.Bold)
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
            Spacer(Modifier.height(14.dp))
            Text("Following distance", color = EnactGreen, fontSize = 15.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            StoppingDistanceSlider()
            MirrorIntervalSliders()
            MapManagerSection()
            LaneCalibrationSliders()
            Spacer(Modifier.height(14.dp))
            Text("Video recording", color = EnactGreen, fontSize = 15.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            VideoRecordingRow()
            Spacer(Modifier.height(14.dp))
            Text("Compliance score weights", color = EnactGreen, fontSize = 15.sp,
                fontWeight = FontWeight.Bold)
            Text("Points deducted per occurrence (scaled by severity).",
                color = EnactOnSurfaceDim, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            RiskType.entries.filter { it.implemented }.forEach { rt ->
                WeightSlider(rt)
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

    /** Lower-left overlay: non-speed road signs (no-turn, no-entry, warnings…)
     *  recently seen, kept on screen ~3 s after they leave the frame so the
     *  driver can register turn restrictions etc. at lights or junctions. */
    @Composable
    private fun androidx.compose.foundation.layout.BoxScope.RecentSignsOverlay() {
        val sgns by (service?.recognisedSigns
            ?: MutableStateFlow(emptyList<com.rfsat.dms.RecognisedSign>()))
            .collectAsState()
        // remember name -> last-seen time; drop speed-limit (shown as roundel)
        val seen = remember { mutableStateMapOf<String, Long>() }
        val now = System.currentTimeMillis()
        sgns.forEach { s ->
            if (s.classId != SignDetector.SPEED_LIMIT_ID && s.score >= 0.5f)
                seen[s.name] = now
        }
        // recompose periodically so expired signs disappear
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
                active.forEach { name ->
                    Box(Modifier.padding(top = 4.dp)
                            .size(77.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.92f)),
                        contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()
                                .padding(4.dp)) {
                            // generic red-ring regulatory disc behind the label
                            val r = size.minDimension / 2f
                            drawCircle(Color(0xFFD32F2F), r, center, style = Stroke(width = r * 0.18f))
                        }
                        Text(shortSignLabel(name), fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, color = Color.Black,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }
    }

    /** Compact label for the small sign chip. */
    private fun shortSignLabel(name: String): String = when {
        name.contains("U-turn", true) -> "NO\nU-TURN"
        name.contains("left", true) -> "NO\nLEFT"
        name.contains("right", true) -> "NO\nRIGHT"
        name.contains("straight", true) -> "NO\nSTRAIGHT"
        name.contains("overtak", true) -> "NO\nOVERTAKE"
        name.contains("entry", true) -> "NO\nENTRY"
        name.contains("stop", true) -> "STOP"
        name.contains("yield", true) || name.contains("give way", true) -> "YIELD"
        else -> name.uppercase().take(10)
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
        val indexUrl = "https://www.rfsat.com/products/maps/index.json"

        fun refresh() {
            busy = true; note = "Checking rfsat.com…"
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val cat = downloader.fetchCatalog(indexUrl)
                runOnUiThread {
                    busy = false
                    if (cat == null) note = "Could not reach the map server."
                    else { statuses = repo.statusFor(cat); note =
                        "${statuses.size} regions • updated ${cat.updated}"
                        currentCatalog = cat }
                }
            }
        }

        fun startDownload(r: com.rfsat.dms.maps.MapRegion) {
            val cat = currentCatalog ?: return
            busy = true; mapImportStatus.value = "Starting download…"
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                downloader.download(cat, r) { p ->
                    val msg = when (p) {
                        is com.rfsat.dms.maps.MapDownloader.Progress.Downloading ->
                            "Downloading ${r.name}: %d / %d MB".format(
                                p.bytes / 1_000_000, p.total / 1_000_000)
                        com.rfsat.dms.maps.MapDownloader.Progress.Verifying ->
                            "Verifying ${r.name}…"
                        com.rfsat.dms.maps.MapDownloader.Progress.Done ->
                            "${r.name} ready. Restart monitoring to load it."
                        is com.rfsat.dms.maps.MapDownloader.Progress.Failed ->
                            "Download failed: ${p.reason}"
                    }
                    runOnUiThread { mapImportStatus.value = msg }
                }
                runOnUiThread { busy = false; refresh() }
            }
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

            for (st in statuses) {
                val r = st.region
                val label = when (st.state) {
                    com.rfsat.dms.maps.MapState.INSTALLED -> "Installed v${r.version}"
                    com.rfsat.dms.maps.MapState.UPDATE_AVAILABLE ->
                        "Update available (v${r.version}, ${r.dataDate})"
                    com.rfsat.dms.maps.MapState.NOT_INSTALLED ->
                        "%.0f MB".format(r.sizeBytes / 1e6)
                    com.rfsat.dms.maps.MapState.UNSUPPORTED_SCHEMA -> "Needs app update"
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${r.country} • ${r.name}", color = EnactOnSurface, fontSize = 12.sp)
                        Text(label, color = EnactOnSurfaceDim, fontSize = 10.sp)
                    }
                    when (st.state) {
                        com.rfsat.dms.maps.MapState.NOT_INSTALLED,
                        com.rfsat.dms.maps.MapState.UPDATE_AVAILABLE ->
                            androidx.compose.material3.TextButton(
                                enabled = !busy,
                                onClick = { pending = r }) {
                                Text(if (st.state == com.rfsat.dms.maps.MapState.UPDATE_AVAILABLE)
                                    "Update" else "Download")
                            }
                        com.rfsat.dms.maps.MapState.INSTALLED ->
                            androidx.compose.material3.TextButton(
                                onClick = { repo.delete(r); refresh() }) { Text("Delete") }
                        else -> {}
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    private var currentCatalog: com.rfsat.dms.maps.MapCatalog? = null

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
    private fun LaneCalibrationSliders() {
        val prefs = remember { getSharedPreferences("dbm", MODE_PRIVATE) }
        var horizon by remember { mutableStateOf(prefs.getFloat("lane_horizon", 0f)) }
        var center by remember { mutableStateOf(prefs.getFloat("lane_center", 0f)) }
        Column(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)).background(EnactSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Lane detection — mount calibration", color = EnactOnSurface, fontSize = 13.sp)
            Text("Adjust if lane tracking is off due to how the phone is " +
                "tilted/mounted. Horizon: move the road area up or down. " +
                "Centre: shift left/right if the mount is not centred.",
                color = EnactOnSurfaceDim, fontSize = 11.sp)
            Text("Horizon: ${"%+.2f".format(horizon)} (move road area up/down)",
                color = EnactOnSurface, fontSize = 12.sp)
            Slider(value = horizon, onValueChange = { horizon = it },
                onValueChangeFinished = {
                    service?.setLaneCalibration(horizon, center)
                        ?: prefs.edit().putFloat("lane_horizon", horizon).apply()
                },
                valueRange = -0.4f..0.4f, steps = 31)
            Text("Centre: ${"%+.2f".format(center)}", color = EnactOnSurface, fontSize = 12.sp)
            Slider(value = center, onValueChange = { center = it },
                onValueChangeFinished = {
                    service?.setLaneCalibration(horizon, center)
                        ?: prefs.edit().putFloat("lane_center", center).apply()
                },
                valueRange = -0.2f..0.2f, steps = 15)
        }
        Spacer(Modifier.height(8.dp))
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
