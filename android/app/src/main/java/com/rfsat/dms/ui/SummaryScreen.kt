package com.rfsat.dms.ui

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rfsat.dms.ComplianceState
import com.rfsat.dms.RiskType
import com.rfsat.dms.data.EventDao
import com.rfsat.dms.ui.theme.EnactError
import com.rfsat.dms.ui.theme.EnactGreen
import com.rfsat.dms.ui.theme.EnactOnSurface
import com.rfsat.dms.ui.theme.EnactOnSurfaceDim
import com.rfsat.dms.ui.theme.EnactSurface
import com.rfsat.dms.ui.theme.EnactWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Violations summary over time: total per category, overall counts, the live
 * compliance score, the period covered, and a control to reset all counters.
 */
@Composable
fun SummaryScreen(
    dao: EventDao,
    complianceState: ComplianceState,
    onResetCounters: () -> Unit,
) {
    val counts by dao.countsByType().collectAsStateWithLifecycle(initialValue = emptyList())
    val total by dao.totalCount().collectAsStateWithLifecycle(initialValue = 0)
    val firstMs by dao.firstEventMs().collectAsStateWithLifecycle(initialValue = null)
    var confirm by remember { mutableStateOf(false) }
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy", Locale.UK) }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = EnactSurface,
            title = { Text("Reset all counters?", color = EnactGreen) },
            text = { Text("This permanently deletes all recorded violations and " +
                "resets the compliance score. Saved video recordings are not affected.",
                color = EnactOnSurface) },
            confirmButton = { TextButton(onClick = { onResetCounters(); confirm = false }) {
                Text("Reset", color = EnactError) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }

    // Map type name -> count for ordered display by catalogue
    val countMap = counts.associate { it.type to it.n }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // Header: score + totals + period
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Overall compliance", color = EnactOnSurfaceDim, fontSize = 12.sp)
                Text("${complianceState.score}/100",
                    color = when { complianceState.score >= 80 -> EnactGreen
                                   complianceState.score >= 50 -> EnactWarning
                                   else -> EnactError },
                    fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Total violations", color = EnactOnSurfaceDim, fontSize = 12.sp)
                Text("$total", color = EnactOnSurface, fontSize = 30.sp,
                    fontWeight = FontWeight.Bold)
            }
        }
        firstMs?.let {
            Text("Since ${dateFmt.format(Date(it))}", color = EnactOnSurfaceDim,
                fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }

        Spacer(8)
        Text("Totals by category", color = EnactGreen, fontSize = 14.sp,
            fontWeight = FontWeight.Bold)
        Spacer(4)
        Row(Modifier.fillMaxWidth()) {
            Text("Category", Modifier.weight(0.6f), color = EnactGreen, fontSize = 11.sp)
            Text("Severity", Modifier.weight(0.25f), color = EnactGreen, fontSize = 11.sp)
            Text("Count", Modifier.weight(0.15f), color = EnactGreen, fontSize = 11.sp)
        }
        HorizontalDivider()

        val rows = RiskType.entries.filter { it.implemented && it != RiskType.NODE_OFFLINE }
            .map { it to (countMap[it.name] ?: 0) }
            .sortedByDescending { it.second }

        LazyColumn(Modifier.weight(1f)) {
            items(rows) { (rt, n) ->
                val sev = when (rt) {
                    RiskType.RED_LIGHT_CROSSING, RiskType.DOUBLE_LINE_CROSSING,
                    RiskType.MICROSLEEP -> "Serious"
                    else -> "Warning"
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(rt.description, Modifier.weight(0.6f),
                        color = if (n > 0) EnactOnSurface else EnactOnSurfaceDim,
                        fontSize = 11.sp)
                    Text(sev, Modifier.weight(0.25f), fontSize = 11.sp,
                        color = if (sev == "Serious") EnactError else EnactWarning)
                    Text("$n", Modifier.weight(0.15f), fontSize = 12.sp,
                        fontWeight = if (n > 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (n > 0) EnactOnSurface else EnactOnSurfaceDim)
                }
                HorizontalDivider(color = EnactOnSurfaceDim.copy(alpha = 0.12f))
            }
        }

        OutlinedButton(onClick = { confirm = true },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Reset all counters", color = EnactError)
        }
    }
}

@Composable
private fun Spacer(h: Int) =
    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = h.dp))
