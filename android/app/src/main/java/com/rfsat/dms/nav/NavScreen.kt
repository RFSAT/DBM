package com.rfsat.dms.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Text
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Navigation screen. Presentation MODE settings (base view, overlays, map layer,
 * own-location icon, windshield, theme) now live in the app Settings screen and
 * persist across runs via NavSettings; this screen reads them. Route/destination/
 * waypoint state lives in a NavRouter held by the Activity, so it survives tab
 * switches. This composable focuses on the live map/camera view + the route panel
 * + a few on-map action buttons (recenter, orientation, map layer).
 *
 * @param router retained router (route/destination/waypoints/navigating status).
 * @param settings persistent nav settings (mode selections).
 * @param recenterState bump to request a one-shot recenter on the user.
 * @param mapOverlayProvider returns speed-limit/parking/camera features near a
 *        point for the map overlay (from the offline map), or null.
 */
@Composable
fun NavScreen(
    router: NavRouter,
    settings: NavSettings,
    recenterState: MutableState<Int>,
    positionFlow: StateFlow<Pair<Double, Double>?>? = null,
    speedKmhFlow: StateFlow<Int>? = null,
    headingFlow: StateFlow<Float>? = null,
    speedLimitProvider: (() -> Int?)? = null,
    mapOverlayProvider: ((Double, Double) -> MapOverlayData?)? = null,
    cameraWarningFlow: StateFlow<String?>? = null,   // same as Detector's warning
    hazardWarningFlow: StateFlow<String?>? = null,   // level crossing / speed bump
    cameraArContent: (@Composable (Modifier) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val routing by router.state.collectAsState()

    // Persistent mode selections (read live; Settings writes them).
    var base by remember { mutableStateOf(settings.base) }
    var mapLayer by remember { mutableStateOf(settings.mapLayer) }
    var orientation by remember { mutableStateOf(settings.orientation) }
    val ownIcon = settings.ownIcon
    val overlays = settings.overlays
    val mirror = settings.windshieldMirror
    val enabledPois = settings.enabledPois

    val livePos = positionFlow?.collectAsState()?.value
    val liveSpeedKmh = speedKmhFlow?.collectAsState()?.value ?: 0
    val heading = headingFlow?.collectAsState()?.value ?: 0f

    // Current speed limit (same value + setting as the Detector roundel), polled
    // as the position updates so it shows whether or not a route is active.
    var currentLimitKmh by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(livePos?.first?.let { (it * 500).toInt() },
                   livePos?.second?.let { (it * 500).toInt() }) {
        currentLimitKmh = speedLimitProvider?.invoke()
    }

    LaunchedEffect(livePos, routing.phase) {
        val p = livePos
        if (p != null && routing.phase == RoutingPhase.NAVIGATING) {
            router.onPosition(GeoPoint(p.first, p.second),
                liveSpeedKmh / 3.6f, speedLimitProvider?.invoke())
        }
    }

    // Map-data overlay: query around the MAP CENTRE when the user pans the map,
    // but around the GPS position while navigating (so it follows the drive).
    var mapCenter by remember { mutableStateOf<GeoPoint?>(null) }
    var overlayData by remember { mutableStateOf<MapOverlayData?>(null) }
    val overlayAnchor: GeoPoint? =
        if (routing.phase == RoutingPhase.NAVIGATING)
            livePos?.let { GeoPoint(it.first, it.second) }
        else mapCenter ?: livePos?.let { GeoPoint(it.first, it.second) }
    LaunchedEffect(overlayAnchor?.lat?.let { (it * 200).toInt() },
                   overlayAnchor?.lon?.let { (it * 200).toInt() }, enabledPois) {
        val a = overlayAnchor
        overlayData = if (enabledPois.isNotEmpty() && a != null)
            mapOverlayProvider?.invoke(a.lat, a.lon)?.copy(enabled = enabledPois)
        else null
    }

    val guidance = routing.guidance
    val ownGeo = livePos?.let { GeoPoint(it.first, it.second) }

    Box(Modifier.fillMaxSize().background(EnactDark)
            .then(if (mirror) Modifier.scale(scaleX = -1f, scaleY = 1f) else Modifier)) {

        val routePts = routing.route?.points
        when (base) {
            BaseView.ARROW_ONLY -> ArrowView(guidance, big = true)
            BaseView.CAMERA_AR -> CameraArBase(guidance, cameraArContent)
            BaseView.MAP_2D_TOPDOWN, BaseView.MAP_2D_PERSPECTIVE, BaseView.MAP_3D -> {
                val tilt = when (base) {
                    BaseView.MAP_2D_PERSPECTIVE -> 45.0
                    BaseView.MAP_3D -> 62.0
                    else -> 0.0
                }
                MapLibreBase(
                    route = routePts, ownLocation = ownGeo, ownIcon = ownIcon,
                    destination = routing.destination, tiltDegrees = tilt,
                    styleSpec = MapStyles.styleFor(mapLayer),
                    orientation = orientation, headingDeg = heading.toDouble(),
                    recenterKey = recenterState.value, mapData = overlayData,
                    onCenterChanged = { mapCenter = it },
                    modifier = Modifier.fillMaxSize())
            }
        }

        // overlays (any combination), on any base
        if (Overlay.ARROW_MANEUVER in overlays && base != BaseView.ARROW_ONLY
            && base != BaseView.CAMERA_AR)
            ArrowView(guidance, big = false)
        if (Overlay.SPEED_LIMIT in overlays)
            SpeedLimitOverlay(currentLimitKmh)
        if (Overlay.RIBBON_HUD in overlays)
            RibbonHud(guidance)
        // Current speed with units, high-contrast, always visible (bottom-left).
        CurrentSpeedOverlay(liveSpeedKmh, currentLimitKmh)

        // on-map action buttons (right side). The MODE button is always present
        // (cycles all five views like the orientation/layer buttons); the
        // recenter/orientation/layer buttons appear only on the map bases.
        val onMap = base == BaseView.MAP_2D_TOPDOWN ||
                base == BaseView.MAP_2D_PERSPECTIVE || base == BaseView.MAP_3D
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) {
            // view-mode cycle: 2D -> 2D perspective -> 3D -> Camera -> Arrow
            RoundBtn(when (base) {
                BaseView.MAP_2D_TOPDOWN -> "2D"
                BaseView.MAP_2D_PERSPECTIVE -> "2½"
                BaseView.MAP_3D -> "3D"
                BaseView.CAMERA_AR -> "\uD83D\uDCF7"   // camera
                BaseView.ARROW_ONLY -> "\u2191"        // arrow
            }) {
                base = when (base) {
                    BaseView.MAP_2D_TOPDOWN -> BaseView.MAP_2D_PERSPECTIVE
                    BaseView.MAP_2D_PERSPECTIVE -> BaseView.MAP_3D
                    BaseView.MAP_3D -> BaseView.CAMERA_AR
                    BaseView.CAMERA_AR -> BaseView.ARROW_ONLY
                    BaseView.ARROW_ONLY -> BaseView.MAP_2D_TOPDOWN
                }
                settings.base = base
            }
            if (onMap) {
                Spacer(Modifier.height(8.dp))
                RoundBtn("◎") { recenterState.value = recenterState.value + 1 }
                Spacer(Modifier.height(8.dp))
                RoundBtn(when (orientation) {
                    MapOrientation.NORTH_UP -> "N"
                    MapOrientation.HEADING_UP -> "▲"
                    MapOrientation.FREE -> "↻"
                }) {
                    orientation = when (orientation) {
                        MapOrientation.NORTH_UP -> MapOrientation.HEADING_UP
                        MapOrientation.HEADING_UP -> MapOrientation.FREE
                        MapOrientation.FREE -> MapOrientation.NORTH_UP
                    }
                    settings.orientation = orientation
                }
                Spacer(Modifier.height(8.dp))
                RoundBtn(when (mapLayer) {
                    MapLayer.STREET -> "S"
                    MapLayer.SATELLITE -> "◱"
                    MapLayer.TERRAIN -> "△"
                }) {
                    mapLayer = when (mapLayer) {
                        MapLayer.STREET -> MapLayer.SATELLITE
                        MapLayer.SATELLITE -> MapLayer.TERRAIN
                        MapLayer.TERRAIN -> MapLayer.STREET
                    }
                    settings.mapLayer = mapLayer
                }
            }
        }

        // route panel (top)
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            RoutePanel(routing,
                onSearch = { q -> scope.launch { router.search(q) } },
                onPick = { label, pt -> router.chooseDestination(label, pt) },
                onAddWaypoint = { router.beginAddWaypoint() },
                onRemoveWaypoint = { i -> router.removeWaypoint(i) },
                onGo = { scope.launch {
                    router.calculateRoute(livePos?.let { GeoPoint(it.first, it.second) }) } },
                onStop = { router.stop() })
            // Speed-camera-ahead warning — the SAME warning the Detector shows,
            // from the same StateFlow and the same "hazard_speed_cameras" setting
            // (so it's null/hidden when that option is off).
            val camWarn = cameraWarningFlow?.collectAsState()?.value
            if (camWarn != null) {
                Row(Modifier.padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(EnactWarning.copy(alpha = 0.92f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("\uD83D\uDCF7", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(camWarn, color = EnactDark, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold)
                }
            }
            // Level-crossing / speed-bump approach warning (its own banner/icon).
            val hazWarn = hazardWarningFlow?.collectAsState()?.value
            if (hazWarn != null) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(EnactError.copy(alpha = 0.92f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("\u26A0", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(hazWarn, color = androidx.compose.ui.graphics.Color.White,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun RoundBtn(label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape)
            .background(EnactDarkMid.copy(alpha = 0.92f))
            .clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(label, color = EnactGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

// -------- route panel --------------------------------------------------------

@Composable
private fun RoutePanel(
    routing: RoutingState,
    onSearch: (String) -> Unit,
    onPick: (String, GeoPoint) -> Unit,
    onAddWaypoint: () -> Unit,
    onRemoveWaypoint: (Int) -> Unit,
    onGo: () -> Unit,
    onStop: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(8.dp)
            .clip(RoundedCornerShape(12.dp)).background(EnactDarkMid.copy(alpha = 0.95f))
            .padding(10.dp)) {
        if (routing.phase == RoutingPhase.NAVIGATING) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Navigating to", color = EnactOnSurfaceDim, fontSize = 11.sp)
                    Text(routing.destinationLabel.ifBlank { "destination" },
                        color = EnactGreen, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1)
                    routing.route?.let {
                        val via = if (routing.waypoints.isNotEmpty())
                            " · ${routing.waypoints.size} stop(s)" else ""
                        Text("%.1f km · ~%d min".format(
                            it.totalMeters / 1000, (it.totalSeconds / 60).toInt()) + via,
                            color = EnactOnSurfaceDim, fontSize = 11.sp)
                    }
                }
                NavChip("Stop") { onStop() }
            }
            return@Column
        }
        Text(if (routing.addingWaypoint) "Add a stop" else "Where to?",
            color = EnactLime, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        // Compact single-line address field (BasicTextField so we control the
        // height — Material TextField is ~56dp tall and crowded the map).
        androidx.compose.foundation.text.BasicTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = EnactOnSurface, fontSize = 14.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(EnactGreen),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)).background(EnactSurface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            decorationBox = { inner ->
                if (query.isEmpty())
                    Text("Search address or place", fontSize = 13.sp,
                        color = EnactOnSurfaceDim)
                inner()
            })
        if (routing.phase == RoutingPhase.SEARCHING)
            Text("Searching…", color = EnactOnSurfaceDim, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
        routing.searchResults.take(6).forEach { (label, pt) ->
            Text(label, color = EnactOnSurface, fontSize = 13.sp, maxLines = 2,
                modifier = Modifier.fillMaxWidth().clickable { onPick(label, pt) }
                    .padding(vertical = 6.dp))
        }
        routing.waypoints.forEachIndexed { i, (label, _) ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("↳ $label", color = EnactOnSurfaceDim, fontSize = 12.sp,
                    maxLines = 1, modifier = Modifier.weight(1f))
                Text("✕", color = EnactError, fontSize = 14.sp,
                    modifier = Modifier.clickable { onRemoveWaypoint(i) }
                        .padding(horizontal = 6.dp))
            }
        }
        if (routing.destination != null && routing.searchResults.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(routing.destinationLabel, color = EnactGreen, fontSize = 13.sp,
                    maxLines = 1, modifier = Modifier.weight(1f))
                NavChip("+ Stop") { onAddWaypoint() }
                Spacer(Modifier.width(6.dp))
                NavChip(if (routing.phase == RoutingPhase.ROUTING) "…" else "Go") { onGo() }
            }
        }
        routing.error?.let {
            Text(it, color = EnactWarning, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// -------- renderers ----------------------------------------------------------

@Composable
private fun ArrowView(g: Guidance?, big: Boolean) {
    val step = g?.nextStep
    Column(Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (big) Arrangement.Center else Arrangement.Top) {
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
                    lineTo(cx + len * 0.45f, cy - len * 0.35f); close()
                }, EnactGreen)
            }
        }
        Spacer(Modifier.height(if (big) 20.dp else 6.dp))
        Text(step?.instruction ?: "No route", color = EnactOnSurface,
            fontSize = if (big) 24.sp else 15.sp, fontWeight = FontWeight.Bold)
        if (g != null && step != null)
            Text("in ${g.distanceToNext.toInt()} m", color = EnactOnSurfaceDim,
                fontSize = if (big) 18.sp else 13.sp)
        if (g?.offRoute == true)
            Text("Off route — recalculating…", color = EnactWarning, fontSize = 14.sp)
    }
}

@Composable
private fun CameraArBase(g: Guidance?, cameraContent: (@Composable (Modifier) -> Unit)?) {
    val proj = remember { ArProjection() }
    val bend = when (g?.nextStep?.maneuver) {
        Maneuver.TURN_LEFT, Maneuver.SLIGHT_LEFT, Maneuver.SHARP_LEFT -> -0.25
        Maneuver.TURN_RIGHT, Maneuver.SLIGHT_RIGHT, Maneuver.SHARP_RIGHT -> 0.25
        else -> 0.0
    }
    Box(Modifier.fillMaxSize().background(EnactDark)) {
        if (cameraContent != null) cameraContent(Modifier.fillMaxSize())
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val dists = (3..60 step 3).map { it.toDouble() }
            val pts = proj.projectPath(dists, bendDegPerM = bend)
                .map { Offset(it.first * size.width, it.second * size.height) }
            if (pts.size >= 2) {
                for (i in 0 until pts.size - 1) {
                    val t = i.toFloat() / (pts.size - 1)
                    drawPath(Path().apply {
                        moveTo(pts[i].x, pts[i].y); lineTo(pts[i + 1].x, pts[i + 1].y)
                    }, EnactGreen.copy(alpha = 0.85f - t * 0.5f),
                        style = Stroke(width = (40f * (1f - t)) + 8f))
                }
                // Clear, large arrowhead at the far end (was too small before).
                val a = pts[pts.size - 2]; val b = pts[pts.size - 1]
                val ang = kotlin.math.atan2(b.y - a.y, b.x - a.x)
                val hl = size.minDimension * 0.12f     // scales with the view
                val spread = 0.6
                drawPath(Path().apply {
                    moveTo(b.x, b.y)
                    lineTo(b.x - hl * kotlin.math.cos(ang - spread).toFloat(),
                           b.y - hl * kotlin.math.sin(ang - spread).toFloat())
                    lineTo(b.x - (hl * 0.55f) * kotlin.math.cos(ang).toFloat(),
                           b.y - (hl * 0.55f) * kotlin.math.sin(ang).toFloat())
                    lineTo(b.x - hl * kotlin.math.cos(ang + spread).toFloat(),
                           b.y - hl * kotlin.math.sin(ang + spread).toFloat())
                    close()
                }, EnactGreen)
            }
        }
        if (g?.nextStep != null)
            Text(g.nextStep.instruction, color = EnactOnSurface, fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp))
    }
}

@Composable
private fun BoxScope.CurrentSpeedOverlay(speedKmh: Int, limitKmh: Int?) {
    // Current speed with units, in a high-contrast pill so it's legible over both
    // the (light) map and the camera feed. Turns amber when over the limit.
    val over = limitKmh != null && limitKmh > 0 && speedKmh > limitKmh + 2
    Row(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Color(0xCC000000))   // dark, ~80% opaque
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom) {
        Text("$speedKmh",
            color = if (over) EnactWarning else androidx.compose.ui.graphics.Color.White,
            fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(4.dp))
        Text("km/h",
            color = if (over) EnactWarning else androidx.compose.ui.graphics.Color(0xFFCFE8DD),
            fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp))
    }
}

@Composable
private fun BoxScope.SpeedLimitOverlay(limitKmh: Int?) {
    // Only show when we actually have a limit (roundel option on + value known).
    if (limitKmh == null || limitKmh <= 0) return
    Row(Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // Speed-limit roundel (white disc + red ring + value) — same style as the
        // Detector's sign roundel.
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                drawCircle(androidx.compose.ui.graphics.Color.White, r, center)
                drawCircle(androidx.compose.ui.graphics.Color(0xFFD32F2F), r, center,
                    style = Stroke(width = r * 0.30f))
            }
            Text("$limitKmh", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.Black)
        }
    }
}

@Composable
private fun BoxScope.RibbonHud(g: Guidance?) {
    // Thin low-clutter "next maneuver" strip for at-a-glance guidance (and for
    // windshield reflection). Only shown while navigating — hidden when there's
    // no route so it doesn't sit as an empty dark bar.
    val step = g?.nextStep ?: return
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(46.dp)
            .padding(horizontal = 24.dp).padding(bottom = 76.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(EnactGreen.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center) {
        Text("${step.instruction}  ·  ${g.distanceToNext.toInt()} m",
            color = EnactDark, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NavChip(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(EnactGreen.copy(alpha = 0.20f))
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(label, color = EnactGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
