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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rfsat.dms.capture.PhoneCameraManager
import com.rfsat.dms.data.DmsDatabase
import com.rfsat.dms.service.MonitorService
import com.rfsat.dms.ui.HistoryScreen
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var service: MonitorService? = null
    private var cameras: PhoneCameraManager? = null
    private lateinit var interiorView: PreviewView
    private lateinit var roadView: PreviewView
    private val captureMode = MutableStateFlow("starting…")

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as MonitorService.LocalBinder).service
            maybeStart()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    private var permissionsOk = false
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            permissionsOk = res[Manifest.permission.CAMERA] == true
            if (permissionsOk) maybeStart()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        interiorView = PreviewView(this)
        roadView = PreviewView(this)
        startForegroundService(Intent(this, MonitorService::class.java))
        bindService(Intent(this, MonitorService::class.java), conn, Context.BIND_AUTO_CREATE)
        permLauncher.launch(arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS))
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    private fun maybeStart() {
        val svc = service ?: return
        if (!permissionsOk || cameras != null) return
        svc.onPermissionsGranted()
        cameras = PhoneCameraManager(this, this, interiorView, roadView,
            onFrame = { role, bmp, t -> svc.submitFrame(role, bmp, t) },
            onMode = { captureMode.value = it.name.lowercase() }
        ).also { it.start() }
    }

    // ------------------------------------------------------------------ UI

    @Composable
    private fun Screen() {
        var accepted by remember { mutableStateOf(false) }
        var showAbout by remember { mutableStateOf(false) }
        var showHistory by remember { mutableStateOf(false) }
        if (!accepted) { PrivacyNotice { accepted = true }; return }
        if (showAbout) { AboutDialog { showAbout = false } }
        if (showHistory) {
            HistoryScreen(dao = DmsDatabase.get(this).events(),
                onBack = { showHistory = false })
            return
        }

        Column(Modifier.fillMaxSize()) {
            ScoreHeader(onAbout = { showAbout = true },
                        onHistory = { showHistory = true })
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(2f), verticalArrangement = Arrangement.SpaceEvenly) {
                    StreamCard(CameraRole.DRIVER, interiorView, Modifier.weight(1f))
                    StreamCard(CameraRole.FRONT, roadView, Modifier.weight(1f))
                }
                EventLog(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }

    @Composable
    private fun ScoreHeader(onAbout: () -> Unit, onHistory: () -> Unit) {
        val st by (service?.scorer?.state
            ?: MutableStateFlow(ComplianceState())).collectAsState()
        val mode by captureMode.collectAsState()
        Row(Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Compliance: ${st.score}/100",
                color = when { st.score >= 80 -> Color(0xFF2E7D32)
                               st.score >= 50 -> Color(0xFFFFA000)
                               else -> Color.Red },
                fontSize = 20.sp)
            Text("${st.currentSpeedKmh} km/h" +
                when (st.speedSource) {
                    SpeedSource.GPS -> " GPS"
                    SpeedSource.VISUAL -> " est."
                    SpeedSource.NONE -> " --"
                } + (st.activeSpeedLimitKmh?.let { "  (limit $it)" } ?: ""),
                fontSize = 16.sp,
                color = if (st.speedSource == SpeedSource.VISUAL) Color(0xFFFFA000)
                        else Color.Unspecified)
            Text("cameras: $mode", fontSize = 12.sp, color = Color.Gray)
            Row {
                TextButton(onClick = onHistory) { Text("History") }
                TextButton(onClick = onAbout) { Text("About") }
            }
        }
    }

    @Composable
    private fun StreamCard(role: CameraRole, view: PreviewView, modifier: Modifier) {
        val result by (service?.results?.get(role)
            ?: MutableStateFlow(AnalysisResult())).collectAsState()
        Box(modifier.fillMaxWidth().padding(2.dp)) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                result.detections.forEach { d ->
                    drawRect(if (d.risky) Color.Red else Color.Green,
                        topLeft = Offset(d.left * size.width, d.top * size.height),
                        size = Size((d.right - d.left) * size.width,
                                    (d.bottom - d.top) * size.height),
                        style = Stroke(3f))
                }
                result.laneLines.forEach { l ->
                    val col = when (l.kind) {
                        LaneLine.Kind.DOUBLE_SOLID -> Color.Red
                        LaneLine.Kind.SOLID -> Color.Yellow
                        LaneLine.Kind.DASHED -> Color.Cyan
                    }
                    drawLine(col,
                        Offset(l.xBottom * size.width, size.height),
                        Offset(l.xTop * size.width, size.height * 0.55f),
                        strokeWidth = 4f)
                }
            }
            Text(role.label, color = Color.White, fontSize = 12.sp,
                modifier = Modifier.padding(6.dp))
        }
    }

    @Composable
    private fun EventLog(modifier: Modifier) {
        val dao = remember { DmsDatabase.get(this).events() }
        val events by dao.latest(100).collectAsStateWithLifecycle(initialValue = emptyList())
        val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.UK) }
        LazyColumn(modifier.padding(4.dp)) {
            items(events) { e ->
                val col = when (e.severity) {
                    "CRITICAL" -> Color.Red; "WARNING" -> Color(0xFFFFA000); else -> Color.Gray
                }
                Text("${fmt.format(Date(e.timestampMs))}  ${e.type}",
                    color = col, fontSize = 12.sp)
            }
        }
    }

    @Composable
    private fun AboutDialog(onClose: () -> Unit) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Driver Behavior Monitor (DBM)") },
            text = {
                Text(
                    "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n\n" +
                    "Driver-awareness aid: detects risky driver behaviour, road hazards " +
                    "and road-regulation compliance, and maintains a timestamped " +
                    "evidential event log.\n\n" +
                    "All processing and storage occur on this device. " +
                    "© RFSAT Limited.\n\n" +
                    "Versioning: major = new features, minor = corrections."
                )
            },
            confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
        )
    }

    @Composable
    private fun PrivacyNotice(onAccept: () -> Unit) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("In-vehicle recording notice") },
            text = {
                Text("This app uses the phone's front camera to monitor the driver and " +
                    "the rear camera to monitor the road ahead, and reads vehicle speed " +
                    "from GPS, to detect risky driving conditions and rate compliance " +
                    "with road regulations. Detection events with image snapshots are " +
                    "stored only on this device and retained for 30 days. Inform all " +
                    "vehicle occupants that recording is active. This is a driver-" +
                    "awareness aid and not a substitute for attentive driving.")
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
