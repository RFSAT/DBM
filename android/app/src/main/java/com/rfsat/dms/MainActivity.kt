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
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            DLog.i(TAG, "permission results: " + res.entries.joinToString {
                "${it.key.substringAfterLast('.')}=${it.value}" })
            permissionsOk = res[Manifest.permission.CAMERA] == true
            if (permissionsOk) maybeStart()
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
        startForegroundService(Intent(this, MonitorService::class.java))
        bindService(Intent(this, MonitorService::class.java), conn, Context.BIND_AUTO_CREATE)
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
        setContent { DbmTheme { Surface(Modifier.fillMaxSize()) { Root() } } }
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
                Column(Modifier.fillMaxWidth().weight(2.42f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CameraCard(CameraRole.DRIVER, interiorView,
                        Modifier.weight(1f).fillMaxWidth())
                    CameraCard(CameraRole.FRONT, roadView,
                        Modifier.weight(1f).fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth().weight(1.21f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CameraCard(CameraRole.DRIVER, interiorView, Modifier.weight(1f))
                    CameraCard(CameraRole.FRONT, roadView, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(6.dp))
            DetectionPanel(Modifier.weight(1f))
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
            Text("Compliance ${st.score}/100",
                fontWeight = FontWeight.Bold, fontSize = 18.sp,
                color = when { st.score >= 80 -> EnactGreen
                               st.score >= 50 -> EnactWarning
                               else -> Color(0xFFE57373) })
            Text("${st.currentSpeedKmh} km/h" +
                when (st.speedSource) {
                    SpeedSource.GPS -> " GPS"; SpeedSource.VISUAL -> " est."; else -> " --"
                } + (st.activeSpeedLimitKmh?.let { "  · limit $it" } ?: ""),
                fontSize = 14.sp,
                color = if (st.speedSource == SpeedSource.VISUAL) EnactWarning
                        else EnactOnSurface)
            Text(mode, fontSize = 11.sp, color = EnactOnSurfaceDim)
        }
    }

    @Composable
    private fun CameraCard(role: CameraRole, view: PreviewView, modifier: Modifier) {
        val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
        val result by (service?.results?.get(role)
            ?: MutableStateFlow(AnalysisResult())).collectAsState()
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
                val frameAr = 4f / 3f
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
                    val l = mx(d.left); val tp = my(d.top)
                    val r = mx(d.right); val bt = my(d.bottom)
                    drawRect(col, topLeft = Offset(l, tp),
                        size = Size(r - l, bt - tp), style = Stroke(3f))
                    // label chip above the box
                    val lbl = d.detClass.display
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
                    val pT = Offset(mx(l.xTop), my(0.55f))
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
            Text(role.label, color = EnactOnSurface, fontSize = 11.sp,
                modifier = Modifier.padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(EnactDarkMid.copy(alpha = 0.8f))
                    .padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }

    @Composable
    private fun DetectionPanel(modifier: Modifier) {
        val dao = remember { DmsDatabase.get(this).events() }
        val events by dao.latest(30).collectAsStateWithLifecycle(initialValue = emptyList())
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
    private fun DetectionElementRow(label: String, key: String) {
        val prefs = remember { getSharedPreferences("dbm", MODE_PRIVATE) }
        var on by remember { mutableStateOf(prefs.getBoolean(key, true)) }
        SettingRow(label, on) {
            on = it
            service?.setElement(key, it) ?: prefs.edit().putBoolean(key, it).apply()
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
            Text("Data processing and storage occurs ONLY on this device, " +
                "nothing is transmitted.", color = EnactOnSurfaceDim, fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Justify)
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

    override fun onDestroy() {
        cameras?.release()
        unbindService(conn)
        super.onDestroy()
    }
}
