package com.rfsat.dms.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rfsat.dms.data.EventDao
import com.rfsat.dms.data.EventEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Violation history browser: all recorded events, newest first, grouped by
 * day, filterable by severity, with a detail view showing the evidential
 * snapshot, full metadata and the SHA-256 integrity hash.
 */
@Composable
fun HistoryScreen(dao: EventDao, onBack: () -> Unit) {
    val events by dao.latest(1000).collectAsStateWithLifecycle(initialValue = emptyList())
    var filter by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<EventEntity?>(null) }

    val dayFmt = remember { SimpleDateFormat("EEE d MMM yyyy", Locale.UK) }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.UK) }

    selected?.let { e -> DetailDialog(e, timeFmt) { selected = null } }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Violation history (${events.size})", fontSize = 20.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(null, "CRITICAL", "WARNING", "INFO").forEach { f ->
                    FilterChip(selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f ?: "All", fontSize = 12.sp) })
                }
                TextButton(onClick = onBack) { Text("Back") }
            }
        }

        val shown = events.filter { filter == null || it.severity == filter }
        val grouped = shown.groupBy { dayFmt.format(Date(it.timestampMs)) }

        LazyColumn(Modifier.fillMaxSize()) {
            grouped.forEach { (day, dayEvents) ->
                item(key = "h-$day") {
                    Text(day, fontSize = 14.sp, color = Color.Gray,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    HorizontalDivider()
                }
                items(dayEvents, key = { it.id }) { e ->
                    Row(Modifier.fillMaxWidth()
                        .clickable { selected = e }
                        .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(e.type.replace('_', ' '),
                                color = severityColor(e.severity), fontSize = 14.sp)
                            Text(e.detail.ifEmpty { e.cameraRole },
                                fontSize = 11.sp, color = Color.Gray)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(timeFmt.format(Date(e.timestampMs)), fontSize = 12.sp)
                            Text(if (e.evidencePath != null) "📷 evidence" else "—",
                                fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailDialog(e: EventEntity, timeFmt: SimpleDateFormat, onClose: () -> Unit) {
    val fullFmt = remember { SimpleDateFormat("d MMM yyyy HH:mm:ss.SSS", Locale.UK) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(e.type.replace('_', ' '), color = severityColor(e.severity)) },
        text = {
            Column {
                e.evidencePath?.let { path ->
                    val f = File(path)
                    if (f.exists()) {
                        val bmp = remember(path) { BitmapFactory.decodeFile(path) }
                        bmp?.let {
                            Image(it.asImageBitmap(), contentDescription = "evidence",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth)
                        }
                    } else Text("Evidence file no longer present (retention expired)",
                        fontSize = 12.sp, color = Color.Gray)
                }
                Text("Time: ${fullFmt.format(Date(e.timestampMs))}", fontSize = 12.sp)
                Text("Camera: ${e.cameraRole}   Severity: ${e.severity}   " +
                     "Confidence: %.2f".format(e.confidence), fontSize = 12.sp)
                if (e.detail.isNotEmpty()) Text("Detail: ${e.detail}", fontSize = 12.sp)
                e.evidenceSha256?.let {
                    Text("SHA-256: $it", fontSize = 9.sp, color = Color.Gray)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

private fun severityColor(s: String) = when (s) {
    "CRITICAL" -> com.rfsat.dms.ui.theme.EnactError
    "WARNING" -> com.rfsat.dms.ui.theme.EnactWarning
    else -> com.rfsat.dms.ui.theme.EnactOnSurfaceDim
}
