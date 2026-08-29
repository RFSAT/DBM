package com.rfsat.dms.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.dms.ui.theme.*

/**
 * Self-contained Navigation screen. The ONLY integration point with the rest of
 * the app is that MainActivity renders this from its tab dispatch. Everything
 * else (state, providers, rendering) lives in the com.rfsat.dms.nav package.
 *
 * v1 skeleton: base-view selector + combinable overlay toggles + windshield
 * mirror + a working ARROW renderer driven by a synthesized stub route. Map
 * bases (2D/perspective/3D) and camera-AR show a labelled placeholder until the
 * MapLibre and camera integrations land (v2/v4) — the selection model and all
 * the surrounding UI are already final, so those slot in without UI churn.
 */
@Composable
fun NavScreen() {
    var state by remember { mutableStateOf(NavState()) }
    var showModes by remember { mutableStateOf(true) }

    // Synthesized demo guidance so the arrow/text renderers are alive in v1.
    val guidance = remember {
        val from = GeoPoint(53.349, -6.260); val to = GeoPoint(53.360, -6.240)
        val route = StubProvider.demoRoute(from, to)
        RouteEngine(route).update(from, 13.9f, 50)
    }

    val mirror = state.transform.windshieldMirror
    Box(Modifier.fillMaxSize().background(EnactDark)
            // Windshield reflection: mirror horizontally so the reflected image
            // reads correctly off the glass with the phone flat on the dash.
            .then(if (mirror) Modifier.scale(scaleX = -1f, scaleY = 1f) else Modifier)) {

        // ---- BASE VIEW (exactly one) ----------------------------------------
        when (state.base) {
            BaseView.ARROW_ONLY -> ArrowView(guidance, big = true)
            BaseView.CAMERA_AR -> PlaceholderBase("Camera (AR) view",
                "Front-camera road view with the arrow drawn over the road ahead — " +
                "arrives with the camera integration. The arrow overlay already works.")
            BaseView.MAP_2D_TOPDOWN -> PlaceholderBase("2D map",
                "Interactive map (MapLibre) — online tiles now, offline later.")
            BaseView.MAP_2D_PERSPECTIVE -> PlaceholderBase("2D perspective",
                "Tilted bird's-eye map — MapLibre camera pitch.")
            BaseView.MAP_3D -> PlaceholderBase("3D map",
                "3D terrain + extruded buildings — MapLibre.")
        }

        // ---- VISUAL OVERLAYS (any combination) ------------------------------
        if (Overlay.ARROW_MANEUVER in state.overlays && state.base != BaseView.ARROW_ONLY)
            ArrowView(guidance, big = false)
        if (Overlay.SPEED_LIMIT in state.overlays)
            SpeedLimitOverlay(guidance)
        if (Overlay.RIBBON_HUD in state.overlays)
            RibbonHud(guidance)
        if (Overlay.LANE_GUIDANCE in state.overlays)
            LaneOverlay(guidance)
        if (Overlay.JUNCTION_VIEW in state.overlays)
            JunctionOverlay(guidance)
        // VOICE / HAPTIC are non-visual; wired to the guidance stream in v2.

        // ---- controls -------------------------------------------------------
        Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Chip(if (showModes) "Hide modes \u25B4" else "Modes \u25BE") {
                showModes = !showModes }
        }
        if (showModes) {
            ModePanel(state, onState = { state = it },
                modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

// -------- base views ---------------------------------------------------------

/** The arrow/text renderer — works with no map SDK. Big = full base view. */
@Composable
private fun ArrowView(g: Guidance, big: Boolean) {
    val step = g.nextStep
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (big) Arrangement.Center else Arrangement.Top
    ) {
        if (big) Spacer(Modifier.height(24.dp))
        androidx.compose.foundation.Canvas(
            Modifier.size(if (big) 200.dp else 96.dp)
        ) {
            val cx = size.width / 2; val cy = size.height / 2
            val deg = when (step?.maneuver) {
                Maneuver.TURN_LEFT, Maneuver.SLIGHT_LEFT -> -60f
                Maneuver.SHARP_LEFT -> -100f
                Maneuver.TURN_RIGHT, Maneuver.SLIGHT_RIGHT -> 60f
                Maneuver.SHARP_RIGHT -> 100f
                Maneuver.UTURN -> 180f
                else -> 0f
            }
            rotate(deg, pivot = Offset(cx, cy)) {
                val len = size.minDimension * 0.34f
                val p = Path().apply {
                    moveTo(cx, cy + len)
                    lineTo(cx, cy - len)
                }
                drawPath(p, EnactGreen, style = Stroke(width = size.minDimension * 0.09f))
                // arrowhead
                val head = Path().apply {
                    moveTo(cx, cy - len * 1.15f)
                    lineTo(cx - len * 0.45f, cy - len * 0.35f)
                    lineTo(cx + len * 0.45f, cy - len * 0.35f)
                    close()
                }
                drawPath(head, EnactGreen)
            }
        }
        Spacer(Modifier.height(if (big) 24.dp else 8.dp))
        Text(step?.instruction ?: "Arrived",
            color = EnactOnSurface, fontSize = if (big) 26.sp else 16.sp,
            fontWeight = FontWeight.Bold)
        Text("in ${g.distanceToNext.toInt()} m",
            color = EnactOnSurfaceDim, fontSize = if (big) 18.sp else 13.sp)
        if (g.offRoute)
            Text("Off route — recalculating…", color = EnactWarning, fontSize = 14.sp)
    }
}

@Composable
private fun PlaceholderBase(title: String, detail: String) {
    Column(Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(title, color = EnactGreen, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(detail, color = EnactOnSurfaceDim, fontSize = 14.sp)
    }
}

// -------- overlays -----------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.BoxScope.SpeedLimitOverlay(g: Guidance) {
    Row(Modifier.align(Alignment.TopEnd).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(28.dp))
                .background(EnactOnSurface), contentAlignment = Alignment.Center) {
            Text("${g.speedLimitKmh ?: "--"}", color = EnactError,
                fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        val kmh = g.currentSpeedMps?.let { (it * 3.6f).toInt() }
        Text(kmh?.let { "$it" } ?: "--", color = EnactOnSurface,
            fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.RibbonHud(g: Guidance) {
    // Thin low-clutter road-ahead ribbon, ideal for windshield reflection.
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(56.dp)
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp)).background(EnactSurface.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center) {
        Text("${g.nextStep?.instruction ?: "Arrived"}  •  ${g.distanceToNext.toInt()} m",
            color = EnactLime, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.LaneOverlay(g: Guidance) {
    Text("Lane guidance", color = EnactOnSurfaceDim, fontSize = 12.sp,
        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.JunctionOverlay(g: Guidance) {
    Text("Junction view", color = EnactOnSurfaceDim, fontSize = 12.sp,
        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
}

// -------- mode selection panel ----------------------------------------------

@Composable
private fun ModePanel(state: NavState, onState: (NavState) -> Unit, modifier: Modifier) {
    Column(modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .background(EnactDarkMid)
            .padding(12.dp)
            .verticalScroll(rememberScrollState())) {
        Text("Base view", color = EnactLime, fontSize = 12.sp,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        FlowRowSimple {
            BaseView.values().forEach { b ->
                Selectable(b.label, selected = state.base == b) {
                    onState(state.withBase(b)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Overlays (combine freely)", color = EnactLime, fontSize = 12.sp,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        FlowRowSimple {
            Overlay.values().forEach { o ->
                Selectable(o.label, selected = o in state.overlays) {
                    onState(state.toggleOverlay(o)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Display", color = EnactLime, fontSize = 12.sp,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        FlowRowSimple {
            Selectable("Windshield mirror",
                selected = state.transform.windshieldMirror) {
                onState(state.withMirror(!state.transform.windshieldMirror)) }
            NavTheme.values().forEach { t ->
                Selectable(t.name.lowercase().replaceFirstChar { it.uppercase() },
                    selected = state.transform.theme == t) {
                    onState(state.withTheme(t)) }
            }
        }
    }
}

/** A shaded, clearly-bounded selectable chip (used for base + overlays). */
@Composable
private fun Selectable(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.padding(3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) EnactGreen.copy(alpha = 0.22f) else EnactSurface)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, color = if (selected) EnactGreen else EnactOnSurfaceDim,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(EnactDarkMid)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, color = EnactGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** Minimal wrap layout (avoids a dependency on the experimental FlowRow). */
@Composable
private fun FlowRowSimple(content: @Composable () -> Unit) {
    // Simple two-per-consideration: Compose lacks a stable FlowRow here, so use a
    // Column of Rows would need measuring; instead lean on horizontal scroll for
    // v1 to keep it dependency-free and predictable.
    Row(Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())) {
        content()
    }
}
