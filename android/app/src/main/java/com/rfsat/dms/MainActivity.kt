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
import com.rfsat.dms.capture.PhoneCameraManager
import com.rfsat.dms.data.DmsDatabase
import com.rfsat.dms.service.MonitorService
import com.rfsat.dms.ui.HistoryScreen
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
        ).also { it.start() }
    }

    // ------------------------------------------------------------------ UI

    private val tabs = listOf("Detector", "History", "Log", "Settings", "About")

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
                1 -> HistoryScreen(dao = DmsDatabase.get(this@MainActivity).events(),
                        onBack = { tab = 0 })
                2 -> LogScreen()
                3 -> SettingsScreen()
                4 -> AboutScreen()
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
        val result by (service?.results?.get(role)
            ?: MutableStateFlow(AnalysisResult())).collectAsState()
        Box(modifier.clip(RoundedCornerShape(14.dp)).background(EnactSurface)) {
            AndroidView(
                factory = {
                    // The PreviewView outlives the Detector tab's composition.
                    // Returning to the tab re-adds it; it must be detached from
                    // the previous (disposed) parent first or the surface stays
                    // blank/frozen.
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                    view
                },
                modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                result.detections.forEach { d ->
                    drawRect(if (d.risky) Color(0xFFE57373) else EnactGreen,
                        topLeft = Offset(d.left * size.width, d.top * size.height),
                        size = Size((d.right - d.left) * size.width,
                                    (d.bottom - d.top) * size.height),
                        style = Stroke(3f))
                }
                result.laneLines.forEach { l ->
                    val col = when (l.kind) {
                        LaneLine.Kind.DOUBLE_SOLID -> Color(0xFFE57373)
                        LaneLine.Kind.SOLID -> EnactWarning
                        LaneLine.Kind.DASHED -> EnactLime
                    }
                    drawLine(col,
                        Offset(l.xBottom * size.width, size.height),
                        Offset(l.xTop * size.width, size.height * 0.55f),
                        strokeWidth = 4f)
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
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(e.type.replace('_', ' ') +
                            if (e.detail.isNotEmpty()) " — ${e.detail}" else "",
                            color = when (e.severity) {
                                "CRITICAL" -> Color(0xFFE57373)
                                "WARNING" -> EnactWarning
                                else -> EnactOnSurfaceDim
                            }, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(fmt.format(Date(e.timestampMs)),
                            color = EnactOnSurfaceDim, fontSize = 11.sp)
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
            DetectionElementRow("Driver state (eyes, gaze, mirrors)", "det_driver")
            Spacer(Modifier.height(14.dp))
            Text("Evidence retention: 30 days (records older than this are " +
                "pruned automatically).", color = EnactOnSurfaceDim, fontSize = 12.sp)
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
    private fun SettingRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)).background(EnactSurface)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = EnactOnSurface, fontSize = 14.sp)
            Switch(checked = value, onCheckedChange = onChange)
        }
        Spacer(Modifier.height(8.dp))
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
                    Text("by RFSAT Limited — www.rfsat.com", fontSize = 13.sp,
                        color = EnactLime,
                        modifier = Modifier.clickable {
                            startActivity(Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://www.rfsat.com")))
                        })
                    Spacer(Modifier.height(4.dp))
                    Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                        fontSize = 12.sp, color = EnactLime.copy(alpha = 0.9f))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "DBM is a driver-awareness aid. It detects risky driver behaviour, " +
                "road hazards and road-regulation compliance, maintains a " +
                "timestamped evidential record with integrity hashes, and scores " +
                "overall driver compliance.",
                color = EnactOnSurfaceDim, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp)).background(EnactSurface)
                    .padding(12.dp)) {
                Text("Detected issues", color = EnactGreen, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                RiskType.entries
                    .filter { it != RiskType.NODE_OFFLINE }
                    .forEach { rt ->
                        Text("•  ${rt.description}",
                            color = EnactOnSurface, fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 1.dp))
                    }
            }
            Spacer(Modifier.height(12.dp))
            Text("All processing and storage occur on this device; nothing is " +
                "transmitted.", color = EnactOnSurfaceDim, fontSize = 13.sp)
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
