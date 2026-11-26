package com.rfsat.dms.nav

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.RotateGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * MapLibre map base for navigation. Key behaviours:
 *  - FOLLOW LOCK ([follow]): the camera keeps the own-location icon in the same
 *    spot and the map scrolls under it, which is what a driver wants. The lock is
 *    the caller's state, not the map's: a pan or a rotate gesture is reported
 *    back ([onUserPan], [onUserRotate]) and the CALLER decides to drop the lock
 *    or switch orientation, so the camera and the buttons can never disagree
 *    about what mode the map is in.
 *  - With the lock off, the user's pan/zoom/rotate is never overridden: the
 *    camera is set once on first load and thereafter only on an explicit request
 *    (recenter, reset, zoom button, or a tilt/orientation change). Ordinary
 *    recomposition never moves it.
 *  - Always shows an own-location marker (icon chosen in settings) that tracks
 *    the live position whether or not the camera follows it.
 *  - Shows a destination marker when navigating.
 *  - Draws the route, plus optional map-data overlays (speed limits / parking /
 *    cameras) supplied by the caller as GeoJSON feature lists.
 *  - Orientation: NORTH_UP pins bearing 0; HEADING_UP turns the map to the
 *    vehicle's course; FREE leaves the bearing wherever the user put it.
 */
@Composable
fun MapLibreBase(
    route: List<GeoPoint>?,
    ownLocation: GeoPoint?,
    ownIcon: OwnLocationIcon,
    destination: GeoPoint?,
    tiltDegrees: Double,
    styleSpec: String,
    orientation: MapOrientation,
    headingDeg: Double,
    recenterKey: Int,               // increment to request a recenter-on-user
    mapData: MapOverlayData?,       // speed limits / parking / cameras, or null
    follow: Boolean = false,        // keep the camera on the vehicle
    speedKmh: Int = 0,              // used to hold the bearing when stopped
    lookAhead: Boolean = false,     // put the vehicle below centre while following
    resetKey: Int = 0,              // increment to reset zoom/tilt/bearing
    zoomTicks: Int = 0,             // signed, cumulative: +1 per zoom-in tap
    onUserPan: (() -> Unit)? = null,       // user dragged the map
    onUserRotate: (() -> Unit)? = null,    // user rotated the map
    onCenterChanged: ((GeoPoint) -> Unit)? = null,  // reports map center when idle
    onMapTap: ((GeoPoint) -> Unit)? = null,         // reports a tapped point
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    val mapHolder = remember { mutableStateOf<MapLibreMap?>(null) }
    val styleHolder = remember { mutableStateOf<Style?>(null) }
    val loadedStyle = remember { mutableStateOf<String?>(null) }
    val didInitialCamera = remember { mutableStateOf(false) }
    val lastRecenterKey = remember { mutableStateOf(recenterKey) }
    // Tracks the applied tilt so a 2D/2½D/3D switch is detected and applied.
    val lastTilt = remember { mutableStateOf(tiltDegrees) }
    val lastOrientation = remember { mutableStateOf(orientation) }
    val lastResetKey = remember { mutableStateOf(resetKey) }
    val lastZoomTicks = remember { mutableStateOf(zoomTicks) }
    // True between gesture begin and end. While the finger is down the follow
    // lock must not push the camera, or the map fights the gesture for a frame.
    val interacting = remember { mutableStateOf(false) }
    // Bearing last applied by HEADING_UP, held while stopped: a parked vehicle's
    // GNSS course is noise, and without this the map slowly spins at every
    // traffic light.
    val heldBearing = remember { mutableStateOf(headingDeg) }
    // The latest callbacks, so the listeners registered once below always call
    // the current lambdas rather than the ones from first composition.
    val panCb = remember { mutableStateOf(onUserPan) }
    val rotateCb = remember { mutableStateOf(onUserRotate) }
    panCb.value = onUserPan; rotateCb.value = onUserRotate

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            mapView.onStop(); mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier) { mv ->
        val map = mapHolder.value
        if (map == null) {
            mv.getMapAsync { m ->
                mapHolder.value = m
                m.uiSettings.isZoomGesturesEnabled = true
                m.uiSettings.isRotateGesturesEnabled = true
                m.uiSettings.isTiltGesturesEnabled = true
                m.uiSettings.isScrollGesturesEnabled = true
                m.uiSettings.isCompassEnabled = true
                // Hide MapLibre's logo strip (the dark bar that appears at the
                // bottom). Attribution stays as the small "i" button (kept for OSM
                // licence compliance), nudged in from the bottom-left corner.
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.isAttributionEnabled = true
                onCenterChanged?.let { cb ->
                    m.addOnCameraIdleListener {
                        val t = m.cameraPosition.target
                        if (t != null) cb(GeoPoint(t.latitude, t.longitude))
                    }
                }
                // Hand gestures back to the caller. These fire ONLY for real
                // finger gestures, never for the camera animations this file
                // runs, so following cannot trigger its own unlock.
                m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                    override fun onMoveBegin(d: MoveGestureDetector) {
                        interacting.value = true
                        panCb.value?.invoke()      // dragging the map drops the lock
                    }
                    override fun onMove(d: MoveGestureDetector) {}
                    override fun onMoveEnd(d: MoveGestureDetector) {
                        interacting.value = false
                    }
                })
                m.addOnRotateListener(object : MapLibreMap.OnRotateListener {
                    override fun onRotateBegin(d: RotateGestureDetector) {
                        interacting.value = true
                        rotateCb.value?.invoke()   // turning the map means FREE
                    }
                    override fun onRotate(d: RotateGestureDetector) {}
                    override fun onRotateEnd(d: RotateGestureDetector) {
                        interacting.value = false
                    }
                })
                // Tap on the map -> report the point so the caller can look up a
                // POI there and show its details.
                onMapTap?.let { cb ->
                    m.addOnMapClickListener { pt ->
                        cb(GeoPoint(pt.latitude, pt.longitude))
                        false      // don't consume; keep normal map behaviour
                    }
                }
                loadStyle(m, styleSpec) { style ->
                    styleHolder.value = style; loadedStyle.value = styleSpec
                    ensureLayers(style, context)
                    updateData(style, route, ownLocation, ownIcon, destination, mapData)
                    // initial camera ONCE (centre on user or route start)
                    val c = ownLocation ?: route?.firstOrNull()
                    if (c != null && !didInitialCamera.value) {
                        m.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(c.lat, c.lon)).zoom(DEFAULT_ZOOM)
                            .tilt(tiltDegrees).bearing(bearingFor(orientation, headingDeg))
                            // The view is usually not laid out yet, so this is
                            // zero here; the first follow update applies it.
                            .padding(followPadding(mv, follow, lookAhead, tiltDegrees))
                            .build()
                        didInitialCamera.value = true
                    }
                }
            }
        } else {
            // style change -> reload; re-add layers + data afterwards
            if (loadedStyle.value != styleSpec) {
                loadStyle(map, styleSpec) { style ->
                    styleHolder.value = style; loadedStyle.value = styleSpec
                    ensureLayers(style, context)
                    updateData(style, route, ownLocation, ownIcon, destination, mapData)
                }
            } else {
                styleHolder.value?.let {
                    updateData(it, route, ownLocation, ownIcon, destination, mapData)
                }
            }
            // The camera moves only for a reason, in this order of priority:
            // reset, recenter, zoom button, tilt change, orientation change, and
            // finally the follow lock. Ordinary recomposition moves nothing, so
            // with the lock off the user's pan/zoom/rotate survives untouched.
            val resetRequested = resetKey != lastResetKey.value
            val recenterRequested = recenterKey != lastRecenterKey.value
            val orientationChanged = orientation != lastOrientation.value
            val tiltChanged = kotlin.math.abs(tiltDegrees - lastTilt.value) > 0.5
            val zoomDelta = zoomTicks - lastZoomTicks.value

            // Hold the bearing while essentially stopped: a parked vehicle's GNSS
            // course wanders, and a map that slowly spins at a red light is worse
            // than one that waits for the car to move.
            val bearingNow = if (orientation == MapOrientation.HEADING_UP) {
                if (speedKmh >= HEADING_MIN_KMH) { heldBearing.value = headingDeg; headingDeg }
                else heldBearing.value
            } else bearingFor(orientation, headingDeg)

            val cur = map.cameraPosition
            when {
                resetRequested -> {
                    // Back to the defaults for the current view: the vehicle, the
                    // default zoom, the mode's tilt, and the orientation's bearing.
                    val c = ownLocation ?: route?.firstOrNull()
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(c?.let { LatLng(it.lat, it.lon) }
                                ?: cur.target ?: LatLng(0.0, 0.0))
                            .zoom(DEFAULT_ZOOM).tilt(tiltDegrees)
                            .bearing(bearingNow)
                            .padding(followPadding(mv, follow, lookAhead, tiltDegrees))
                            .build()))
                    lastResetKey.value = resetKey
                }
                recenterRequested -> {
                    val c = ownLocation ?: route?.firstOrNull()
                    if (c != null) {
                        map.animateCamera(CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(c.lat, c.lon)).zoom(RECENTER_ZOOM)
                                .tilt(tiltDegrees).bearing(bearingNow)
                                .padding(followPadding(mv, follow, lookAhead, tiltDegrees))
                                .build()))
                    }
                    lastRecenterKey.value = recenterKey
                }
                zoomDelta != 0 -> {
                    // Zoom buttons. zoomBy keeps the current target, so a locked
                    // map stays locked and a panned one stays where it was.
                    map.animateCamera(CameraUpdateFactory.zoomBy(zoomDelta.toDouble()), 250)
                    lastZoomTicks.value = zoomTicks
                }
                tiltChanged -> {
                    // Mode switched (2D / 2½D / 3D): apply the new tilt right away,
                    // keeping the user's current target, zoom and bearing.
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(cur.target ?: LatLng(0.0, 0.0)).zoom(cur.zoom)
                            .tilt(tiltDegrees)
                            .bearing(if (orientation == MapOrientation.FREE) cur.bearing
                                     else bearingNow)
                            .padding(followPadding(mv, follow, lookAhead, tiltDegrees))
                            .build()))
                }
                orientationChanged && orientation != MapOrientation.FREE -> {
                    // keep current target/zoom, only change bearing
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(cur.target ?: LatLng(0.0, 0.0)).zoom(cur.zoom)
                            .tilt(cur.tilt).bearing(bearingNow).build()))
                }
                // FOLLOW: one combined update per fix, so position and bearing
                // move together instead of chasing each other. Skipped while a
                // finger is on the map, and skipped when the camera is already
                // there — recomposition is driven partly by the camera-idle
                // callback, so an unconditional ease here would re-trigger
                // itself and the map would never settle.
                follow && ownLocation != null && !interacting.value &&
                    needsFollowUpdate(cur, ownLocation, bearingNow, orientation) -> {
                    val b = CameraPosition.Builder()
                        .target(LatLng(ownLocation.lat, ownLocation.lon))
                        .zoom(cur.zoom).tilt(cur.tilt)
                        .padding(followPadding(mv, true, lookAhead, tiltDegrees))
                    // In FREE the user owns the bearing, so following must not
                    // touch it — only the position.
                    b.bearing(if (orientation == MapOrientation.FREE) cur.bearing else bearingNow)
                    map.easeCamera(CameraUpdateFactory.newCameraPosition(b.build()),
                        FOLLOW_EASE_MS)
                }
                // Not following, but heading-up still has to track the course.
                orientation == MapOrientation.HEADING_UP && !interacting.value &&
                    kotlin.math.abs(angleDelta(cur.bearing, bearingNow)) > BEARING_EPS_DEG -> {
                    map.moveCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(cur.target ?: LatLng(0.0, 0.0)).zoom(cur.zoom)
                            .tilt(cur.tilt).bearing(bearingNow).build()))
                }
            }
            lastOrientation.value = orientation
            lastTilt.value = tiltDegrees
            // Keep these in step even when another branch won, so a later change
            // is measured against what was actually asked for.
            lastResetKey.value = resetKey
            lastRecenterKey.value = recenterKey
            lastZoomTicks.value = zoomTicks
        }
    }
}

private fun bearingFor(o: MapOrientation, headingDeg: Double) = when (o) {
    MapOrientation.NORTH_UP -> 0.0
    MapOrientation.HEADING_UP -> headingDeg
    MapOrientation.FREE -> 0.0     // only used at first load; then user controls
}

/**
 * Whether a follow update is worth issuing: the camera is more than
 * [FOLLOW_MIN_MOVE_M] from the vehicle, or the bearing is stale. Without this
 * the map re-animates to where it already is, forever, because finishing an
 * animation fires the idle callback that causes the next recomposition.
 */
private fun needsFollowUpdate(
    cur: CameraPosition, own: GeoPoint, bearingNow: Double, o: MapOrientation,
): Boolean {
    val t = cur.target ?: return true
    val dLat = (own.lat - t.latitude) * 111_320.0
    val dLon = (own.lon - t.longitude) * 111_320.0 *
        kotlin.math.cos(Math.toRadians(own.lat))
    if (kotlin.math.hypot(dLat, dLon) > FOLLOW_MIN_MOVE_M) return true
    return o != MapOrientation.FREE &&
        kotlin.math.abs(angleDelta(cur.bearing, bearingNow)) > BEARING_EPS_DEG
}

/** Signed smallest difference between two bearings, -180..180. */
private fun angleDelta(from: Double, to: Double): Double {
    var d = (to - from) % 360.0
    if (d > 180.0) d -= 360.0
    if (d < -180.0) d += 360.0
    return d
}

/**
 * Camera padding as {left, top, right, bottom} in pixels. Padding shifts the
 * camera's target away from the centre of the view, which is how the vehicle is
 * put below the middle to show more road ahead: padding the BOTTOM moves the
 * target down. Returns zeros unless following with look-ahead on, so by default
 * the icon sits exactly in the middle of the map, and a tilted view gets a
 * bigger shift because that is where the horizon wastes the most screen.
 */
private fun followPadding(
    view: MapView, follow: Boolean, lookAhead: Boolean, tiltDegrees: Double,
): DoubleArray {
    if (!follow || !lookAhead) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    val h = view.height.toDouble()
    if (h <= 0.0) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    val frac = if (tiltDegrees > 20.0) LOOK_AHEAD_TILTED else LOOK_AHEAD_FLAT
    return doubleArrayOf(0.0, 0.0, 0.0, h * frac)
}

/** Zoom used by a view reset. */
private const val DEFAULT_ZOOM = 15.0
/** Zoom used when centring on the vehicle. */
private const val RECENTER_ZOOM = 16.0
/** Below this speed the map holds its bearing instead of following the course. */
private const val HEADING_MIN_KMH = 5
/** Don't re-issue a rotation for less than this, to avoid per-frame jitter. */
private const val BEARING_EPS_DEG = 1.5
/** Follow animation length; comfortably shorter than the 1 Hz fix interval. */
private const val FOLLOW_EASE_MS = 450
/** Below this camera-to-vehicle gap a follow update is not worth an animation
 *  (roughly a GNSS jitter radius, and about a pixel at street zoom). */
private const val FOLLOW_MIN_MOVE_M = 1.5
/** Share of the view height the vehicle is pushed down by, flat and tilted. */
private const val LOOK_AHEAD_FLAT = 0.22
private const val LOOK_AHEAD_TILTED = 0.34

private const val ROUTE_SRC = "dbm-route"; private const val ROUTE_LYR = "dbm-route-l"
private const val ROUTE_CASING = "dbm-route-c"
private const val OWN_SRC = "dbm-own"; private const val OWN_LYR = "dbm-own-l"
private const val DEST_SRC = "dbm-dest"; private const val DEST_LYR = "dbm-dest-l"
private const val LIMIT_SRC = "dbm-lim"; private const val LIMIT_LYR = "dbm-lim-l"
private const val PARK_SRC = "dbm-park"; private const val PARK_LYR = "dbm-park-l"
private const val CAM_SRC = "dbm-cam"; private const val CAM_LYR = "dbm-cam-l"
private const val FUEL_SRC = "dbm-fuel"; private const val FUEL_LYR = "dbm-fuel-l"
private const val CHG_SRC = "dbm-chg"; private const val CHG_LYR = "dbm-chg-l"
private const val HOSP_SRC = "dbm-hosp"; private const val HOSP_LYR = "dbm-hosp-l"
private const val REST_SRC = "dbm-rest"; private const val REST_LYR = "dbm-rest-l"
private const val TOLL_SRC = "dbm-toll"; private const val TOLL_LYR = "dbm-toll-l"
private const val BORDER_SRC = "dbm-border"; private const val BORDER_LYR = "dbm-border-l"
private const val LEVELX_SRC = "dbm-levelx"; private const val LEVELX_LYR = "dbm-levelx-l"
private const val BUMP_SRC = "dbm-bump"; private const val BUMP_LYR = "dbm-bump-l"
private const val ICON_FUEL = "dbm-ic-fuel"
private const val ICON_CHG = "dbm-ic-chg"
private const val ICON_HOSP = "dbm-ic-hosp"
private const val ICON_REST = "dbm-ic-rest"
private const val ICON_TOLL = "dbm-ic-toll"
private const val ICON_BORDER = "dbm-ic-border"
private const val ICON_LEVELX = "dbm-ic-levelx"
private const val ICON_BUMP = "dbm-ic-bump"
private const val ICON_PARKING = "dbm-ic-parking"
private const val ICON_CAMERA = "dbm-ic-camera"
private const val ICON_OWN_BLUE_DOT = "dbm-ic-own-dot"
private const val ICON_OWN_CAR = "dbm-ic-own-car"
private const val ICON_OWN_PED = "dbm-ic-own-ped"
private const val ICON_OWN_ARROW = "dbm-ic-own-arrow"

private fun loadStyle(map: MapLibreMap, spec: String, onLoaded: (Style) -> Unit) {
    val b = if (MapStyles.isInlineJson(spec)) Style.Builder().fromJson(spec)
            else Style.Builder().fromUri(spec)
    map.setStyle(b) { onLoaded(it) }
}

/**
 * Register the parking and camera icons as style images (drawn programmatically,
 * so no asset files are bundled). A rounded blue "P" for parking, a red disc with
 * a simple camera glyph for speed cameras. Idempotent — only adds once per style.
 */
private fun registerOverlayIcons(style: Style, ctx: android.content.Context?) {
    if (style.getImage(ICON_PARKING) == null)
        style.addImage(ICON_PARKING, parkingBitmap())
    if (style.getImage(ICON_CAMERA) == null)
        style.addImage(ICON_CAMERA, cameraBitmap())
    if (style.getImage(ICON_OWN_BLUE_DOT) == null)
        style.addImage(ICON_OWN_BLUE_DOT, ownDotBitmap())
    if (style.getImage(ICON_OWN_CAR) == null)
        style.addImage(ICON_OWN_CAR, ownCarBitmap())
    if (style.getImage(ICON_OWN_PED) == null)
        style.addImage(ICON_OWN_PED, ownPedBitmap())
    if (style.getImage(ICON_OWN_ARROW) == null)
        style.addImage(ICON_OWN_ARROW, ownArrowBitmap())
    if (style.getImage(ICON_FUEL) == null)
        style.addImage(ICON_FUEL, glyphBitmap("\u26FD", "#2F6096"))   // fuel pump
    // Real brand LOGOS when supplied as drawables (see FuelBrands.drawableName);
    // otherwise a brand-coloured chip; unbranded stations use ICON_FUEL above.
    // Register a brand icon ONLY when its logo drawable is actually present.
    // Brands without artwork (and every unrecognised brand) then resolve to the
    // generic distributor icon, because the per-feature icon lookup falls back
    // to ICON_FUEL when the named image doesn't exist in the style.
    for (k in FuelBrands.allKeys()) {
        val id = FuelBrands.iconId(k)
        if (style.getImage(id) != null) { FuelBrands.markAvailable(k); continue }
        val logo = ctx?.let { FuelBrands.loadLogo(it, k) } ?: continue
        style.addImage(id, logo)
        FuelBrands.markAvailable(k)
    }
    if (style.getImage(ICON_CHG) == null)
        style.addImage(ICON_CHG, badgeBitmap("\u26A1", "#4CAF50"))   // boxed, lighter green
    if (style.getImage(ICON_HOSP) == null)
        style.addImage(ICON_HOSP, badgeBitmap("H", "#1565C0"))        // hospital H
    if (style.getImage(ICON_REST) == null)
        style.addImage(ICON_REST, glyphBitmap("\u2615", "#5A5148"))   // rest area
    if (style.getImage(ICON_TOLL) == null)
        style.addImage(ICON_TOLL, glyphBitmap("\u20AC", "#7A6A2F"))   // toll (€)
    if (style.getImage(ICON_BORDER) == null)
        style.addImage(ICON_BORDER, glyphBitmap("\u2691", "#4C4573"))  // border flag
    if (style.getImage(ICON_LEVELX) == null)
        style.addImage(ICON_LEVELX, glyphBitmap("\u2715", "#B03030"))  // level crossing X
    if (style.getImage(ICON_BUMP) == null)
        style.addImage(ICON_BUMP, glyphBitmap("\u2229", "#A9662B"))    // speed bump ∩
}

/** A small rounded badge with a glyph/letter centred on it — used for the extra
 *  POI icons (fuel, charging, hospital, rest). */
/** Box-free POI glyph: the symbol itself in its colour, with a soft white halo
 *  so it stays legible over any map background. Used for most POIs; only parking
 *  (its own bitmap) and hospital (a blue box) keep a filled background. */
private fun glyphBitmap(glyph: String, colorHex: String): Bitmap {
    val s = 64; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.textSize = 42f; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
    val fm = p.fontMetrics
    val y = s / 2f - (fm.ascent + fm.descent) / 2f
    // white halo behind the glyph for contrast on light or dark map tiles
    p.style = Paint.Style.STROKE; p.strokeWidth = 6f
    p.color = AndroidColor.WHITE; p.alpha = 220
    c.drawText(glyph, s / 2f, y, p)
    // the glyph itself
    p.style = Paint.Style.FILL; p.alpha = 255
    p.color = AndroidColor.parseColor(colorHex)
    c.drawText(glyph, s / 2f, y, p)
    return bmp
}

private fun badgeBitmap(glyph: String, colorHex: String): Bitmap {
    // Muted map badge: white glyph on a desaturated background, drawn at 64px so
    // it stays crisp when scaled. Slightly translucent with a thin light outline
    // so it reads as part of the map rather than shouting over it.
    val s = 64; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = AndroidColor.parseColor(colorHex)
    p.alpha = 225                                  // ~88% opaque
    c.drawRoundRect(RectF(5f, 5f, s - 5f, s - 5f), 14f, 14f, p)
    // thin, soft outline (was a heavy pure-white stroke)
    p.color = AndroidColor.parseColor("#E8EEF2"); p.alpha = 180
    p.style = Paint.Style.STROKE; p.strokeWidth = 2f
    c.drawRoundRect(RectF(5f, 5f, s - 5f, s - 5f), 14f, 14f, p)
    p.style = Paint.Style.FILL; p.color = AndroidColor.WHITE; p.alpha = 255
    p.textSize = 36f; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
    val fm = p.fontMetrics
    c.drawText(glyph, s / 2f, s / 2f - (fm.ascent + fm.descent) / 2f, p)
    return bmp
}

private fun ownDotBitmap(): Bitmap {
    val s = 44; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    // soft accuracy halo, white ring, blue core (classic GPS dot)
    p.color = AndroidColor.argb(60, 46, 125, 255)
    c.drawCircle(s / 2f, s / 2f, s / 2f - 2f, p)
    p.color = AndroidColor.WHITE; c.drawCircle(s / 2f, s / 2f, 11f, p)
    p.color = AndroidColor.parseColor("#2E7DFF"); c.drawCircle(s / 2f, s / 2f, 8f, p)
    return bmp
}

private fun ownCarBitmap(): Bitmap {
    // Cleaner top-down car: tapered nose, cabin greenhouse, wheels. Points up.
    val s = 60; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val blue = AndroidColor.parseColor("#2E7DFF")
    // wheels (dark rounded rects at the corners)
    p.color = AndroidColor.parseColor("#202124")
    c.drawRoundRect(RectF(12f, 18f, 18f, 28f), 3f, 3f, p)
    c.drawRoundRect(RectF(42f, 18f, 48f, 28f), 3f, 3f, p)
    c.drawRoundRect(RectF(12f, 34f, 18f, 44f), 3f, 3f, p)
    c.drawRoundRect(RectF(42f, 34f, 48f, 44f), 3f, 3f, p)
    // body: a tapered shape (narrower at the nose) via a path
    p.color = blue
    val body = android.graphics.Path().apply {
        moveTo(30f, 6f)                     // nose tip
        cubicTo(38f, 8f, 44f, 14f, 44f, 24f)
        lineTo(44f, 44f)
        cubicTo(44f, 51f, 38f, 54f, 30f, 54f)
        cubicTo(22f, 54f, 16f, 51f, 16f, 44f)
        lineTo(16f, 24f)
        cubicTo(16f, 14f, 22f, 8f, 30f, 6f)
        close()
    }
    c.drawPath(body, p)
    // cabin/greenhouse (lighter) + windshield split
    p.color = AndroidColor.parseColor("#BFDBFF")
    c.drawRoundRect(RectF(21f, 20f, 39f, 40f), 5f, 5f, p)
    // windshield (white) at the front of the cabin
    p.color = AndroidColor.WHITE
    val wind = android.graphics.Path().apply {
        moveTo(22f, 22f); lineTo(38f, 22f); lineTo(35f, 15f); lineTo(25f, 15f); close()
    }
    c.drawPath(wind, p)
    return bmp
}

private fun ownPedBitmap(): Bitmap {
    // Blue pedestrian figure, no surrounding circle, smaller head.
    val s = 44; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val blue = AndroidColor.parseColor("#2E7DFF")
    // thin white outline behind the figure for contrast on any map
    fun figure(col: Int, sw: Float) {
        p.color = col; p.style = Paint.Style.FILL
        c.drawCircle(22f, 11f, 3f, p)                         // head (smaller)
        p.style = Paint.Style.STROKE; p.strokeWidth = sw
        p.strokeCap = Paint.Cap.ROUND
        c.drawLine(22f, 15f, 22f, 27f, p)                    // body
        c.drawLine(22f, 18f, 16f, 24f, p); c.drawLine(22f, 18f, 28f, 24f, p)  // arms
        c.drawLine(22f, 27f, 17f, 36f, p); c.drawLine(22f, 27f, 27f, 36f, p)  // legs
    }
    figure(AndroidColor.WHITE, 7f)   // white halo
    figure(blue, 4f)                 // blue figure
    return bmp
}

private fun ownArrowBitmap(): Bitmap {
    val s = 44; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = AndroidColor.parseColor("#2E7DFF")
    c.drawCircle(s / 2f, s / 2f, s / 2f - 3f, p)
    p.color = AndroidColor.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f
    c.drawCircle(s / 2f, s / 2f, s / 2f - 3f, p)
    // upward navigation chevron
    p.style = Paint.Style.FILL; p.color = AndroidColor.WHITE
    val path = android.graphics.Path().apply {
        moveTo(22f, 12f); lineTo(31f, 32f); lineTo(22f, 26f); lineTo(13f, 32f); close()
    }
    c.drawPath(path, p)
    return bmp
}

private fun parkingBitmap(): Bitmap {
    val s = 48
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = AndroidColor.parseColor("#1565C0")
    c.drawRoundRect(RectF(4f, 4f, s - 4f, s - 4f), 10f, 10f, p)
    p.color = AndroidColor.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 3f
    c.drawRoundRect(RectF(4f, 4f, s - 4f, s - 4f), 10f, 10f, p)
    p.style = Paint.Style.FILL; p.color = AndroidColor.WHITE
    p.textSize = 30f; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
    val fm = p.fontMetrics
    c.drawText("P", s / 2f, s / 2f - (fm.ascent + fm.descent) / 2f, p)
    return bmp
}

private fun cameraBitmap(): Bitmap {
    val s = 48
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = AndroidColor.parseColor("#D32F2F")
    c.drawCircle(s / 2f, s / 2f, s / 2f - 4f, p)
    p.color = AndroidColor.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 3f
    c.drawCircle(s / 2f, s / 2f, s / 2f - 4f, p)
    p.style = Paint.Style.FILL; p.color = AndroidColor.WHITE
    c.drawRoundRect(RectF(13f, 20f, 35f, 34f), 3f, 3f, p)
    c.drawRect(RectF(19f, 16f, 27f, 21f), p)
    p.color = AndroidColor.parseColor("#D32F2F")
    c.drawCircle(24f, 27f, 4.5f, p)
    return bmp
}

private fun ensureLayers(style: Style, ctx: android.content.Context? = null) {
    if (style.getSource(ROUTE_SRC) == null) {
        style.addSource(GeoJsonSource(ROUTE_SRC))
        style.addLayer(LineLayer(ROUTE_CASING, ROUTE_SRC).withProperties(
            PropertyFactory.lineColor("#0B3D91"), PropertyFactory.lineWidth(9f)))
        style.addLayer(LineLayer(ROUTE_LYR, ROUTE_SRC).withProperties(
            PropertyFactory.lineColor("#1A73E8"), PropertyFactory.lineWidth(5f)))
    }
    // map-data overlays (drawn under the markers)
    if (style.getSource(LIMIT_SRC) == null) {
        style.addSource(GeoJsonSource(LIMIT_SRC))
        style.addLayer(LineLayer(LIMIT_LYR, LIMIT_SRC).withProperties(
            PropertyFactory.lineColor("#FFCA28"), PropertyFactory.lineWidth(2.5f),
            PropertyFactory.lineOpacity(0.7f)))
    }
    // Parking + camera get recognizable ICONS (a "P" and a camera glyph),
    // registered as style images and drawn via SymbolLayers.
    registerOverlayIcons(style, ctx)
    if (style.getSource(PARK_SRC) == null) {
        style.addSource(GeoJsonSource(PARK_SRC))
        style.addLayer(SymbolLayer(PARK_LYR, PARK_SRC).withProperties(
            PropertyFactory.iconImage(ICON_PARKING),
            PropertyFactory.iconSize(1.2f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true)))
    }
    if (style.getSource(CAM_SRC) == null) {
        style.addSource(GeoJsonSource(CAM_SRC))
        style.addLayer(SymbolLayer(CAM_LYR, CAM_SRC).withProperties(
            PropertyFactory.iconImage(ICON_CAMERA),
            PropertyFactory.iconSize(1.2f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true)))
    }
    // extra POI layers (fuel / charging / hospital / rest area)
    for ((src, lyr, icon) in listOf(
        Triple(CHG_SRC, CHG_LYR, ICON_CHG),
        Triple(HOSP_SRC, HOSP_LYR, ICON_HOSP),
        Triple(REST_SRC, REST_LYR, ICON_REST),
        Triple(TOLL_SRC, TOLL_LYR, ICON_TOLL),
        Triple(BORDER_SRC, BORDER_LYR, ICON_BORDER),
        Triple(LEVELX_SRC, LEVELX_LYR, ICON_LEVELX),
        Triple(BUMP_SRC, BUMP_LYR, ICON_BUMP))) {
        if (style.getSource(src) == null) {
            style.addSource(GeoJsonSource(src))
            style.addLayer(SymbolLayer(lyr, src).withProperties(
                PropertyFactory.iconImage(icon),
                PropertyFactory.iconSize(1.3f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)))
        }
    }
    // Fuel gets its OWN layer: the icon is chosen PER FEATURE from the "icon"
    // property, so each station shows its brand chip (or the generic pump).
    if (style.getSource(FUEL_SRC) == null) {
        style.addSource(GeoJsonSource(FUEL_SRC))
        style.addLayer(SymbolLayer(FUEL_LYR, FUEL_SRC).withProperties(
            PropertyFactory.iconImage(
                org.maplibre.android.style.expressions.Expression.get("icon")),
            // ~10% smaller than the other POI badges: brand logos read larger
            // than a glyph at the same nominal size, so they looked oversized.
            PropertyFactory.iconSize(1.17f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true)))
    }
    // markers on top
    if (style.getSource(DEST_SRC) == null) {
        style.addSource(GeoJsonSource(DEST_SRC))
        style.addLayer(CircleLayer(DEST_LYR, DEST_SRC).withProperties(
            PropertyFactory.circleColor("#E57373"), PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f)))
    }
    if (style.getSource(OWN_SRC) == null) {
        style.addSource(GeoJsonSource(OWN_SRC))
        // own-location marker as a real icon (blue dot / car / pedestrian / arrow),
        // chosen in Settings. iconImage is set in updateData to match the choice.
        style.addLayer(SymbolLayer(OWN_LYR, OWN_SRC).withProperties(
            PropertyFactory.iconImage(ICON_OWN_BLUE_DOT),
            PropertyFactory.iconSize(1.7f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconRotationAlignment("map")))
    }
}

private fun updateData(
    style: Style, route: List<GeoPoint>?, own: GeoPoint?, ownIcon: OwnLocationIcon,
    dest: GeoPoint?, mapData: MapOverlayData?
) {
    style.getSourceAs<GeoJsonSource>(ROUTE_SRC)?.setGeoJson(
        if (route != null && route.size >= 2)
            FeatureCollection.fromFeature(Feature.fromGeometry(
                LineString.fromLngLats(route.map { Point.fromLngLat(it.lon, it.lat) })))
        else FeatureCollection.fromFeatures(emptyList()))

    style.getSourceAs<GeoJsonSource>(OWN_SRC)?.setGeoJson(
        if (own != null) FeatureCollection.fromFeature(
            Feature.fromGeometry(Point.fromLngLat(own.lon, own.lat)))
        else FeatureCollection.fromFeatures(emptyList()))
    // switch the own-location icon to the one chosen in Settings
    style.getLayerAs<SymbolLayer>(OWN_LYR)?.setProperties(
        PropertyFactory.iconImage(when (ownIcon) {
            OwnLocationIcon.BLUE_DOT -> ICON_OWN_BLUE_DOT
            OwnLocationIcon.CAR -> ICON_OWN_CAR
            OwnLocationIcon.PEDESTRIAN -> ICON_OWN_PED
            OwnLocationIcon.ARROW -> ICON_OWN_ARROW
        }))

    style.getSourceAs<GeoJsonSource>(DEST_SRC)?.setGeoJson(
        if (dest != null) FeatureCollection.fromFeature(
            Feature.fromGeometry(Point.fromLngLat(dest.lon, dest.lat)))
        else FeatureCollection.fromFeatures(emptyList()))

    // map-data overlays — each drawn only if its POI type is enabled
    val en = mapData?.enabled ?: emptySet()
    val empty = FeatureCollection.fromFeatures(emptyList())
    style.getSourceAs<GeoJsonSource>(LIMIT_SRC)?.setGeoJson(
        if (PoiType.SPEED_LIMITS in en && mapData != null)
            FeatureCollection.fromFeatures(mapData.speedLimitLines.map { seg ->
                Feature.fromGeometry(LineString.fromLngLats(
                    seg.map { Point.fromLngLat(it.lon, it.lat) })) })
        else empty)
    style.getSourceAs<GeoJsonSource>(PARK_SRC)?.setGeoJson(
        if (PoiType.PARKING in en && mapData != null)
            FeatureCollection.fromFeatures(mapData.parking.map {
                Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })
        else empty)
    // EVERY camera in view is drawn, whichever direction it measures — the
    // direction filter belongs to the approach warning (CameraMonitor), not to
    // the map, where seeing the cameras on both carriageways is the point.
    style.getSourceAs<GeoJsonSource>(CAM_SRC)?.setGeoJson(
        if (PoiType.SPEED_CAMERAS in en && mapData != null)
            FeatureCollection.fromFeatures(mapData.cameras.map {
                Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })
        else empty)
    fun pointFc(on: Boolean, pts: List<GeoPoint>?) =
        if (on && pts != null)
            FeatureCollection.fromFeatures(pts.map {
                Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })
        else empty
    // Fuel: each feature carries the icon id for its brand (or the generic pump),
    // which the data-driven layer resolves per station.
    style.getSourceAs<GeoJsonSource>(FUEL_SRC)?.setGeoJson(
        if (PoiType.FUEL in en && mapData != null) {
            val src = mapData.fuelBrands.ifEmpty {
                mapData.fuel.map { it to null as String? }
            }
            FeatureCollection.fromFeatures(src.map { (pt, brand) ->
                // Only use a brand icon whose logo is actually registered —
                // naming a missing image would draw NOTHING for that station.
                val key = FuelBrands.keyFor(brand)?.takeIf { FuelBrands.hasLogo(it) }
                Feature.fromGeometry(Point.fromLngLat(pt.lon, pt.lat)).also { f ->
                    f.addStringProperty("icon",
                        if (key != null) FuelBrands.iconId(key) else ICON_FUEL)
                }
            })
        } else empty)
    style.getSourceAs<GeoJsonSource>(CHG_SRC)?.setGeoJson(
        pointFc(PoiType.CHARGING in en, mapData?.charging))
    style.getSourceAs<GeoJsonSource>(HOSP_SRC)?.setGeoJson(
        pointFc(PoiType.HOSPITAL in en, mapData?.hospital))
    style.getSourceAs<GeoJsonSource>(REST_SRC)?.setGeoJson(
        pointFc(PoiType.REST_AREA in en, mapData?.restArea))
    style.getSourceAs<GeoJsonSource>(TOLL_SRC)?.setGeoJson(
        pointFc(PoiType.TOLL_BOOTH in en, mapData?.tollBooth))
    style.getSourceAs<GeoJsonSource>(BORDER_SRC)?.setGeoJson(
        pointFc(PoiType.BORDER_CONTROL in en, mapData?.borderControl))
    style.getSourceAs<GeoJsonSource>(LEVELX_SRC)?.setGeoJson(
        pointFc(PoiType.LEVEL_CROSSING in en, mapData?.levelCrossing))
    style.getSourceAs<GeoJsonSource>(BUMP_SRC)?.setGeoJson(
        pointFc(PoiType.SPEED_BUMP in en, mapData?.speedBump))
}

/**
 * Brand-coloured fuel markers.
 *
 * We deliberately do NOT bundle company logos: those are trademarks, and
 * sourcing properly-licensed artwork per brand is a legal question rather than a
 * technical one. Instead each major brand gets its recognisable house COLOUR
 * plus its initial — which is what a driver actually picks out at a glance, is
 * free of licensing issues, works offline and costs no APK size.
 *
 * Unknown/absent brands fall back to the neutral pump glyph.
 */
internal object FuelBrands {
    // brand (lowercased, as OSM writes it) -> (background, letter)
    // EXACTLY the brands we ship a logo for (res/drawable/fuel_logo_<key>.png).
    // Anything not listed here — or listed but with no drawable present — falls
    // back to the generic distributor icon. Keep this in step with the artwork.
    private val table: List<Triple<String, String, String>> = listOf(
        // --- Greek market (logos shipped) ---------------------------------
        Triple("aegean", "#00A0DF", "AE"),      // Aegean Oil
        Triple("coral", "#D4231E", "C"),        // Coral (Shell licensee in GR)
        Triple("cyclon", "#004B93", "CY"),
        Triple("elin", "#0B7A3B", "ELIN"),
        Triple("eteka", "#C8102E", "ET"),
        Triple("jetoil", "#E8621F", "JO"),
        Triple("kaoil", "#0B7A3B", "K"),
        Triple("silk oil", "#8E44AD", "SO"),
        // --- rest ---------------------------------------------------------
        Triple("agip", "#F5C518", "A"),
        Triple("aral", "#0B3F8C", "A"),
        Triple("avia", "#C8102E", "A"),
        Triple("avin", "#C8102E", "AV"),        // Greek
        Triple("bp", "#0B7A3B", "B"),
        Triple("carrefour", "#0B5FA5", "C"),
        Triple("cepsa", "#C8102E", "C"),
        Triple("circle k", "#E8621F", "C"),
        Triple("eko", "#F5A800", "EKO"),        // Greek
        Triple("eni", "#F5C518", "E"),
        Triple("esso", "#1B4EA0", "E"),
        Triple("gulf", "#E8621F", "G"),
        Triple("intermarché", "#C8102E", "I"),
        Triple("ip", "#0B7A3B", "IP"),
        Triple("jet", "#F5C518", "J"),
        Triple("leclerc", "#0B5FA5", "L"),
        Triple("lukoil", "#C8102E", "L"),
        Triple("mol", "#0B7A3B", "M"),
        Triple("neste", "#00A0DF", "N"),
        Triple("omv", "#0B3F8C", "O"),
        Triple("orlen", "#C8102E", "O"),
        Triple("petrol", "#0B7A3B", "P"),
        Triple("preem", "#F5C518", "P"),
        Triple("q8", "#00A0DF", "Q"),
        Triple("repsol", "#E8621F", "R"),
        Triple("revoil", "#004B93", "RV"),      // Greek
        Triple("shell", "#D4231E", "S"),
        Triple("socar", "#00A0DF", "S"),
        Triple("statoil", "#E8621F", "S"),
        Triple("tamoil", "#C8102E", "T"),
        Triple("texaco", "#C8102E", "T"),
        Triple("total", "#E4322B", "T"),
        Triple("totalenergies", "#E4322B", "T"),
    )

    /** OSM in Greece frequently writes brands in GREEK script. Map those to the
     *  latin key so both spellings resolve to the same logo/chip (and so the
     *  drawable name stays ASCII). */
    private val aliases: Map<String, String> = mapOf(
        "εκο" to "eko", "εκο-ελδα" to "eko", "eko-elda" to "eko",
        "αβιν" to "avin", "αβίν" to "avin",
        "ρεβοιλ" to "revoil", "ρεβόιλ" to "revoil",
        "ελιν" to "elin", "ελίν" to "elin",
        // ELIN is the fuel brand of ELINOIL / ELLINOIL ("Ελληνοϊλ"); OSM uses
        // several spellings and "ellinoil" does NOT prefix-match "elin".
        "ellinoil" to "elin", "elinoil" to "elin",
        "ελληνοϊλ" to "elin", "ελληνοιλ" to "elin", "ελινοιλ" to "elin",
        "κοραλ" to "coral", "κοράλ" to "coral",
        "τζετοιλ" to "jetoil", "jet oil" to "jetoil",
        "κυκλων" to "cyclon", "σικλον" to "cyclon",
        "αιγαιον" to "aegean", "aegean oil" to "aegean",
        "ετεκα" to "eteka",
        "καοιλ" to "kaoil",
        "σελλ" to "shell", "μπι πι" to "bp",
    )

    /** Normalised key for a brand string, or null when we have no match. */
    fun keyFor(brand: String?): String? {
        val raw = brand?.trim()?.lowercase() ?: return null
        if (raw.isEmpty()) return null
        // Greek (or other) spelling -> latin key
        aliases[raw]?.let { return it }
        // exact match wins
        table.firstOrNull { it.first == raw }?.let { return it.first }
        // Then word-boundary matching. A plain "contains" is unsafe for short
        // keys — "eko" would match "Ekoenergo", "ip" would match "Philips" —
        // so require the key to appear as a whole word (or the start of one).
        val words = raw.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        for ((k, _, _) in table) {
            if (k.contains(' ')) {                 // multi-word key, e.g. "circle k"
                if (raw.contains(k)) return k
            } else if (words.any { it == k }) {
                return k
            }
        }
        // finally, allow a prefix match only for keys of 4+ chars, so
        // "Shell Express" -> shell but short keys stay strict.
        for ((k, _, _) in table) {
            if (k.length >= 4 && words.any { it.startsWith(k) }) return k
        }
        return null
    }

    fun colourFor(key: String): String =
        table.first { it.first == key }.second

    fun letterFor(key: String): String =
        table.first { it.first == key }.third

    /** All keys, so every brand icon can be registered once with the style. */
    fun allKeys(): List<String> = table.map { it.first }.distinct()

    /** Brands whose logo is a distinct, self-contained symbol (no text to make
     *  legible), so it looks better drawn directly on the map without a plate. */
    private val noPlateBrands = setOf(
        "bp", "shell", "aegean", "eteka", "eko", "jetoil")

    fun iconId(key: String) = "dbm-ic-fuel-$key"

    /** Brands whose logo drawable was found and registered with the map style.
     *  Anything not in here renders with the generic distributor icon. */
    private val available = java.util.Collections.synchronizedSet(HashSet<String>())
    fun markAvailable(key: String) { available.add(key) }
    fun hasLogo(key: String) = available.contains(key)

    /** Drawable resource name expected for a brand's real logo, e.g.
     *  "shell" -> res/drawable/fuel_logo_shell.png  (or .webp / .xml vector).
     *  Drop the licensed artwork in with these names and it is picked up
     *  automatically — no code change needed. */
    fun drawableName(key: String): String {
        // Transliterate accents first, otherwise "intermarché" would become
        // "intermarch" (the é dropped) and silently never match a drawable.
        val ascii = java.text.Normalizer.normalize(key, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return "fuel_logo_" +
            ascii.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    }

    /** Load a brand's real logo if the app ships one, scaled to the map icon
     *  size on a transparent square. Returns null when no such drawable exists,
     *  so the caller falls back to the coloured chip. */
    fun loadLogo(ctx: android.content.Context, key: String): Bitmap? {
        val name = drawableName(key)
        val resId = runCatching {
            ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
        }.getOrDefault(0)
        if (resId == 0) return null
        val src = runCatching {
            androidx.core.content.ContextCompat.getDrawable(ctx, resId)
        }.getOrNull() ?: return null

        val s = 64
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // Most logos are wordmarks that need a light plate to stay legible over
        // the map. Brands whose mark is a strong, self-contained symbol read
        // better WITHOUT one, so they are drawn straight onto the map.
        val plate = key !in noPlateBrands
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (plate) {
            p.color = AndroidColor.WHITE; p.alpha = 235
            c.drawRoundRect(RectF(2f, 2f, s - 2f, s - 2f), 14f, 14f, p)
            p.color = AndroidColor.parseColor("#B0BEC5"); p.alpha = 200
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f
            c.drawRoundRect(RectF(2f, 2f, s - 2f, s - 2f), 14f, 14f, p)
            p.style = Paint.Style.FILL
        }
        // fit the logo inside, preserving aspect ratio. Plate-less marks get less
        // padding so the symbol itself renders as large as possible.
        val pad = if (plate) 9 else 3
        val w = src.intrinsicWidth.coerceAtLeast(1)
        val h = src.intrinsicHeight.coerceAtLeast(1)
        val box = s - 2 * pad
        val scale = minOf(box.toFloat() / w, box.toFloat() / h)
        val dw = (w * scale).toInt().coerceAtLeast(1)
        val dh = (h * scale).toInt().coerceAtLeast(1)
        val left = (s - dw) / 2
        val top = (s - dh) / 2
        src.setBounds(left, top, left + dw, top + dh)
        src.draw(c)
        return out
    }
}

/** A round brand chip: the brand's house colour with its initial in white. */
private fun fuelBrandBitmap(colorHex: String, letter: String): Bitmap {
    val s = 64; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = AndroidColor.parseColor(colorHex)
    c.drawCircle(s / 2f, s / 2f, s / 2f - 5f, p)
    p.color = AndroidColor.parseColor("#FFFFFF"); p.alpha = 210
    p.style = Paint.Style.STROKE; p.strokeWidth = 3f
    c.drawCircle(s / 2f, s / 2f, s / 2f - 5f, p)
    p.style = Paint.Style.FILL; p.color = AndroidColor.WHITE; p.alpha = 255
    p.textSize = if (letter.length > 1) 26f else 34f
    p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
    val fm = p.fontMetrics
    c.drawText(letter, s / 2f, s / 2f - (fm.ascent + fm.descent) / 2f, p)
    return bmp
}
