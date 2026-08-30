package com.rfsat.dms.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.dms.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Self-contained Navigation screen. Integration with the rest of the app is a
 * single call site in MainActivity plus two live StateFlows (position, speed)
 * that already exist on the SpeedMonitor. Everything else lives in the
 * com.rfsat.dms.nav package.
 *
 * This version adds REAL routing: enter a destination, calculate a route via the
 * online OSM provider (OSRM + Nominatim), and navigate with live guidance driven
 * by the phone's GNSS position. Map bases still show placeholders until MapLibre
 * is integrated; the arrow/text/ribbon renderers work now.
 *
 * @param positionFlow live (lat,lon) from SpeedMonitor, or null when unavailable.
 * @param speedKmhFlow live GNSS speed, or null.
 * @param speedLimitProvider optional lookup of the current speed limit (km/h)
 *        from the app's offline maps — wired later; returns null for now.
 */
@Composable
fun NavScreen(
    positionFlow: StateFlow<Pair<Double, Double>?>? = null,
    speedKmhFlow: StateFlow<Int>? = null,
    speedLimitProvider: (() -> Int?)? = null
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(NavState()) }
    var showModes by remember { mutableStateOf(false) }

    // Real router on the online OSM provider. (Swap provider for offline later.)
    val router = remember { NavRouter(OnlineOsmProvider()) }
    val routing by router.state.collectAsState()

    val livePos: Pair<Double, Double>? =
        positionFlow?.collectAsState()?.value
    val liveSpeedKmh: Int = speedKmhFlow?.collectAsState()?.value ?: 0

    // Feed live position into the router while navigating.
    LaunchedEffect(livePos, routing.phase) {
        val p = livePos
        if (p != null && routing.phase == RoutingPhase.NAVIGATING) {
            router.onPosition(GeoPoint(p.first, p.second),
                liveSpeedKmh / 3.6f, speedLimitProvider?.invoke())
        }
    }

    val guidance = routing.guidance
    val mirror = state.transform.windshieldMirror

    Box(Modifier.fillMaxSize().background(EnactDark)
            .then(if (mirror) Modifier.scale(scaleX = -1f, scaleY = 1f) else Modifier)) {

        // ---- BASE VIEW ------------------------------------------------------
        val routePts = routing.route?.points
        val centerPt = livePos?.let { GeoPoint(it.first, it.second) }
            ?: routePts?.firstOrNull()
        when (state.base) {
            BaseView.ARROW_ONLY -> ArrowView(guidance, big = true)
            BaseView.CAMERA_AR -> CameraArBase(guidance)
            BaseView.MAP_2D_TOPDOWN ->
                MapLibreBase(routePts, centerPt, tiltDegrees = 0.0,
                    modifier = Modifier.fillMaxSize())
            BaseView.MAP_2D_PERSPECTIVE ->
                MapLibreBase(routePts, centerPt, tiltDegrees = 45.0,
                    modifier = Modifier.fillMaxSize())
            BaseView.MAP_3D ->
                MapLibreBase(routePts, centerPt, tiltDegrees = 62.0,
                    modifier = Modifier.fillMaxSize())
        }

        // ---- VISUAL OVERLAYS (any combination) ------------------------------
        if (Overlay.ARROW_MANEUVER in state.overlays && state.base != BaseView.ARROW_ONLY)
            ArrowView(guidance, big = false)
        if (Overlay.SPEED_LIMIT in state.overlays)
            SpeedLimitOverlay(guidance, liveSpeedKmh)
        if (Overlay.RIBBON_HUD in state.overlays)
            RibbonHud(guidance)

        // ---- routing panel (top) + mode panel (bottom) ----------------------
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            RoutePanel(routing, onSearch = { q -> scope.launch { router.search(q) } },
                onPick = { label, pt -> router.chooseDestination(label, pt) },
                onGo = {
                    scope.launch {
                        router.calculateRoute(livePos?.let { GeoPoint(it.first, it.second) })
                    }
                },
                onStop = { router.stop() })
        }

        Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            Chip(if (showModes) "Hide modes \u25BE" else "Modes \u25B4") {
                showModes = !showModes }
        }
        if (showModes) {
            ModePanel(state, onState = { state = it },
                modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

// -------- routing panel ------------------------------------------------------

@Composable
private fun RoutePanel(
    routing: RoutingState,
    onSearch: (String) -> Unit,
    onPick: (String, GeoPoint) -> Unit,
    onGo: () -> Unit,
    onStop: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(8.dp)
            .clip(RoundedCornerShape(12.dp)).background(EnactDarkMid.copy(alpha = 0.95f))
            .padding(10.dp)) {
        if (routing.phase == RoutingPhase.NAVIGATING) {
            // Compact navigating header with a stop button.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Navigating to", color = EnactOnSurfaceDim, fontSize = 11.sp)
                    Text(routing.destinationLabel.ifBlank { "destination" },
                        color = EnactGreen, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1)
                    routing.route?.let {
                        Text("%.1f km · ~%d min".format(
                            it.totalMeters / 1000, (it.totalSeconds / 60).toInt()),
                            color = EnactOnSurfaceDim, fontSize = 11.sp)
                    }
                }
                Chip("Stop") { onStop() }
            }
            return@Column
        }

        Text("Where to?", color = EnactLime, fontSize = 12.sp,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        TextField(
            value = query, onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search address or place", fontSize = 13.sp,
                color = EnactOnSurfaceDim) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            modifier = Modifier.fillMaxWidth())
        if (routing.phase == RoutingPhase.SEARCHING)
            Text("Searching…", color = EnactOnSurfaceDim, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))

        // search results
        routing.searchResults.take(6).forEach { (label, pt) ->
            Text(label, color = EnactOnSurface, fontSize = 13.sp, maxLines = 2,
                modifier = Modifier.fillMaxWidth()
                    .clickable { onPick(label, pt) }
                    .padding(vertical = 6.dp))
        }

        // chosen destination + Go
        if (routing.destination != null && routing.searchResults.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(routing.destinationLabel, color = EnactGreen, fontSize = 13.sp,
                    maxLines = 1, modifier = Modifier.weight(1f))
                Chip(if (routing.phase == RoutingPhase.ROUTING) "…" else "Go") { onGo() }
            }
        }
        routing.error?.let {
            Text(it, color = EnactWarning, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// -------- base views ---------------------------------------------------------

@Composable
private fun ArrowView(g: Guidance?, big: Boolean) {
    val step = g?.nextStep
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (big) Arrangement.Center else Arrangement.Top
    ) {
        if (big) Spacer(Modifier.height(64.dp))
        androidx.compose.foundation.Canvas(Modifier.size(if (big) 180.dp else 88.dp)) {
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
                drawPath(Path().apply { moveTo(cx, cy + len); lineTo(cx, cy - len) },
                    EnactGreen, style = Stroke(width = size.minDimension * 0.09f))
                drawPath(Path().apply {
                    moveTo(cx, cy - len * 1.15f)
                    lineTo(cx - len * 0.45f, cy - len * 0.35f)
                    lineTo(cx + len * 0.45f, cy - len * 0.35f)
                    close()
                }, EnactGreen)
            }
        }
        Spacer(Modifier.height(if (big) 20.dp else 6.dp))
        Text(step?.instruction ?: "No route",
            color = EnactOnSurface, fontSize = if (big) 24.sp else 15.sp,
            fontWeight = FontWeight.Bold)
        if (g != null && step != null)
            Text("in ${g.distanceToNext.toInt()} m",
                color = EnactOnSurfaceDim, fontSize = if (big) 18.sp else 13.sp)
        if (g?.offRoute == true)
            Text("Off route — recalculating…", color = EnactWarning, fontSize = 14.sp)
    }
}

/**
 * Camera-AR base: draws the navigation path projected onto the road plane using
 * the geometric projection (ArProjection), so the arrow sits where the road is
 * on a straight, level road — no ML needed for this baseline. The LIVE camera
 * feed is composited behind this next (the camera is owned by the monitor
 * service); until then it's drawn over a dark backdrop so the projection is
 * visible. ML road segmentation will refine placement on hills/curves and mask
 * out non-road areas.
 */
@Composable
private fun CameraArBase(g: Guidance?) {
    val proj = remember { ArProjection() }
    // Bend the projected path toward the upcoming maneuver so the carpet leads
    // into the turn (sign only; magnitude is a gentle visual lead for the demo).
    val bend = when (g?.nextStep?.maneuver) {
        Maneuver.TURN_LEFT, Maneuver.SLIGHT_LEFT, Maneuver.SHARP_LEFT -> -0.25
        Maneuver.TURN_RIGHT, Maneuver.SLIGHT_RIGHT, Maneuver.SHARP_RIGHT -> 0.25
        else -> 0.0
    }
    Box(Modifier.fillMaxSize().background(EnactDark)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val dists = (3..60 step 3).map { it.toDouble() }
            val pts = proj.projectPath(dists, bendDegPerM = bend)
                .map { Offset(it.first * size.width, it.second * size.height) }
            if (pts.size >= 2) {
                // road "carpet": a thick tapering line from near to far
                for (i in 0 until pts.size - 1) {
                    val t = i.toFloat() / (pts.size - 1)
                    drawPath(Path().apply {
                        moveTo(pts[i].x, pts[i].y); lineTo(pts[i + 1].x, pts[i + 1].y)
                    }, EnactGreen.copy(alpha = 0.85f - t * 0.5f),
                        style = Stroke(width = (34f * (1f - t)) + 6f))
                }
                // arrowhead at the far end pointing along the last segment
                val a = pts[pts.size - 2]; val b = pts[pts.size - 1]
                val ang = kotlin.math.atan2(b.y - a.y, b.x - a.x)
                val hl = 26f
                drawPath(Path().apply {
                    moveTo(b.x, b.y)
                    lineTo(b.x - hl * kotlin.math.cos(ang - 0.5).toFloat(),
                           b.y - hl * kotlin.math.sin(ang - 0.5).toFloat())
                    lineTo(b.x - hl * kotlin.math.cos(ang + 0.5).toFloat(),
                           b.y - hl * kotlin.math.sin(ang + 0.5).toFloat())
                    close()
                }, EnactGreen)
            }
        }
        Text("Camera-AR (geometric projection) — live camera composites behind next",
            color = EnactOnSurfaceDim, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 92.dp))
        if (g?.nextStep != null)
            Text(g.nextStep.instruction, color = EnactOnSurface, fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp))
    }
}

// -------- overlays -----------------------------------------------------------

@Composable
private fun BoxScope.SpeedLimitOverlay(g: Guidance?, liveSpeedKmh: Int) {
    Row(Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(26.dp))
                .background(EnactOnSurface), contentAlignment = Alignment.Center) {
            Text("${g?.speedLimitKmh ?: "--"}", color = EnactError,
                fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text("$liveSpeedKmh", color = EnactOnSurface,
            fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BoxScope.RibbonHud(g: Guidance?) {
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(52.dp)
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp)).background(EnactSurface.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center) {
        Text("${g?.nextStep?.instruction ?: "No route"}  ·  ${g?.distanceToNext?.toInt() ?: 0} m",
            color = EnactLime, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

// -------- mode selection panel ----------------------------------------------

@Composable
private fun ModePanel(state: NavState, onState: (NavState) -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .background(EnactDarkMid).padding(12.dp)) {
        Text("Base view", color = EnactLime, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            BaseView.values().forEach { b ->
                Selectable(b.label, state.base == b) { onState(state.withBase(b)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Overlays (combine freely)", color = EnactLime, fontSize = 12.sp,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Overlay.values().forEach { o ->
                Selectable(o.label, o in state.overlays) { onState(state.toggleOverlay(o)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Display", color = EnactLime, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Selectable("Windshield mirror", state.transform.windshieldMirror) {
                onState(state.withMirror(!state.transform.windshieldMirror)) }
            NavTheme.values().forEach { t ->
                Selectable(t.name.lowercase().replaceFirstChar { it.uppercase() },
                    state.transform.theme == t) { onState(state.withTheme(t)) }
            }
        }
    }
}

@Composable
private fun Selectable(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.padding(3.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) EnactGreen.copy(alpha = 0.22f) else EnactSurface)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, color = if (selected) EnactGreen else EnactOnSurfaceDim,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(EnactGreen.copy(alpha = 0.20f))
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(label, color = EnactGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
