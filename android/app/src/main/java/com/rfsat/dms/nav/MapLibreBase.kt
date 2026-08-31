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
 *  - The user's pan/zoom/rotate is NEVER overridden: the camera is set ONCE when
 *    the map first loads (centred on the user), and thereafter only when the
 *    caller explicitly requests a recenter (recenterKey changes) or the
 *    orientation mode dictates a bearing. Panning/zooming the map does not get
 *    reset on recomposition.
 *  - Always shows an own-location marker (icon chosen in settings) that tracks
 *    the live position without moving the camera.
 *  - Shows a destination marker when navigating.
 *  - Draws the route, plus optional map-data overlays (speed limits / parking /
 *    cameras) supplied by the caller as GeoJSON feature lists.
 *  - Orientation: NORTH_UP pins bearing 0; HEADING_UP follows the vehicle course;
 *    FREE lets the user rotate freely (bearing left as the user set it).
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
    onCenterChanged: ((GeoPoint) -> Unit)? = null,  // reports map center when idle
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
    val lastOrientation = remember { mutableStateOf(orientation) }

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
                loadStyle(m, styleSpec) { style ->
                    styleHolder.value = style; loadedStyle.value = styleSpec
                    ensureLayers(style)
                    updateData(style, route, ownLocation, ownIcon, destination, mapData)
                    // initial camera ONCE (centre on user or route start)
                    val c = ownLocation ?: route?.firstOrNull()
                    if (c != null && !didInitialCamera.value) {
                        m.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(c.lat, c.lon)).zoom(15.0)
                            .tilt(tiltDegrees).bearing(bearingFor(orientation, headingDeg))
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
                    ensureLayers(style)
                    updateData(style, route, ownLocation, ownIcon, destination, mapData)
                }
            } else {
                styleHolder.value?.let {
                    updateData(it, route, ownLocation, ownIcon, destination, mapData)
                }
            }
            // Camera is updated ONLY on an explicit recenter request or an
            // orientation-mode change — never on ordinary recomposition, so the
            // user's pan/zoom is preserved.
            val recenterRequested = recenterKey != lastRecenterKey.value
            val orientationChanged = orientation != lastOrientation.value
            if (recenterRequested) {
                val c = ownLocation ?: route?.firstOrNull()
                if (c != null) {
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(c.lat, c.lon)).zoom(16.0)
                            .tilt(tiltDegrees)
                            .bearing(bearingFor(orientation, headingDeg)).build()))
                }
                lastRecenterKey.value = recenterKey
            } else if (orientationChanged && orientation != MapOrientation.FREE) {
                // keep current target/zoom, only change bearing
                val cur = map.cameraPosition
                map.animateCamera(CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(cur.target ?: LatLng(0.0, 0.0)).zoom(cur.zoom)
                        .tilt(cur.tilt).bearing(bearingFor(orientation, headingDeg))
                        .build()))
            } else if (orientation == MapOrientation.HEADING_UP) {
                // continuous heading-up: rotate to course without touching zoom/target
                val cur = map.cameraPosition
                map.moveCamera(CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(cur.target ?: LatLng(0.0, 0.0)).zoom(cur.zoom)
                        .tilt(cur.tilt).bearing(headingDeg).build()))
            }
            lastOrientation.value = orientation
        }
    }
}

private fun bearingFor(o: MapOrientation, headingDeg: Double) = when (o) {
    MapOrientation.NORTH_UP -> 0.0
    MapOrientation.HEADING_UP -> headingDeg
    MapOrientation.FREE -> 0.0     // only used at first load; then user controls
}

private const val ROUTE_SRC = "dbm-route"; private const val ROUTE_LYR = "dbm-route-l"
private const val ROUTE_CASING = "dbm-route-c"
private const val OWN_SRC = "dbm-own"; private const val OWN_LYR = "dbm-own-l"
private const val DEST_SRC = "dbm-dest"; private const val DEST_LYR = "dbm-dest-l"
private const val LIMIT_SRC = "dbm-lim"; private const val LIMIT_LYR = "dbm-lim-l"
private const val PARK_SRC = "dbm-park"; private const val PARK_LYR = "dbm-park-l"
private const val CAM_SRC = "dbm-cam"; private const val CAM_LYR = "dbm-cam-l"
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
private fun registerOverlayIcons(style: Style) {
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
    // Recognizable top-down car silhouette (no surrounding circle). Points up =
    // direction of travel: rounded body, windshield, side windows, wheels.
    val s = 56; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val blue = AndroidColor.parseColor("#2E7DFF")
    // wheels (dark), poking out at the four corners
    p.color = AndroidColor.parseColor("#1A1A1A")
    c.drawRoundRect(RectF(11f, 16f, 17f, 26f), 2f, 2f, p)   // front-left
    c.drawRoundRect(RectF(39f, 16f, 45f, 26f), 2f, 2f, p)   // front-right
    c.drawRoundRect(RectF(11f, 32f, 17f, 42f), 2f, 2f, p)   // rear-left
    c.drawRoundRect(RectF(39f, 32f, 45f, 42f), 2f, 2f, p)   // rear-right
    // body
    p.color = blue
    c.drawRoundRect(RectF(15f, 8f, 41f, 50f), 11f, 13f, p)
    // white glass: windshield (front, narrower) + rear window
    p.color = AndroidColor.WHITE
    val wind = android.graphics.Path().apply {
        moveTo(20f, 20f); lineTo(36f, 20f); lineTo(33f, 13f); lineTo(23f, 13f); close()
    }
    c.drawPath(wind, p)
    val rear = android.graphics.Path().apply {
        moveTo(21f, 34f); lineTo(35f, 34f); lineTo(33f, 41f); lineTo(23f, 41f); close()
    }
    c.drawPath(rear, p)
    // roof line between the windows (a lighter blue strip reads as the cabin)
    p.color = AndroidColor.parseColor("#5C9BFF")
    c.drawRect(RectF(20f, 21f, 36f, 33f), p)
    return bmp
}

private fun ownPedBitmap(): Bitmap {
    val s = 44; val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = AndroidColor.parseColor("#2E7DFF")
    c.drawCircle(s / 2f, s / 2f, s / 2f - 3f, p)
    p.color = AndroidColor.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f
    c.drawCircle(s / 2f, s / 2f, s / 2f - 3f, p)
    // stick-figure pedestrian
    p.style = Paint.Style.FILL; p.color = AndroidColor.WHITE
    c.drawCircle(22f, 14f, 3.5f, p)                          // head
    p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.strokeCap = Paint.Cap.ROUND
    c.drawLine(22f, 18f, 22f, 28f, p)                        // body
    c.drawLine(22f, 21f, 17f, 26f, p); c.drawLine(22f, 21f, 27f, 26f, p)  // arms
    c.drawLine(22f, 28f, 18f, 34f, p); c.drawLine(22f, 28f, 26f, 34f, p)  // legs
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

private fun ensureLayers(style: Style) {
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
    registerOverlayIcons(style)
    if (style.getSource(PARK_SRC) == null) {
        style.addSource(GeoJsonSource(PARK_SRC))
        style.addLayer(SymbolLayer(PARK_LYR, PARK_SRC).withProperties(
            PropertyFactory.iconImage(ICON_PARKING),
            PropertyFactory.iconSize(1.0f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true)))
    }
    if (style.getSource(CAM_SRC) == null) {
        style.addSource(GeoJsonSource(CAM_SRC))
        style.addLayer(SymbolLayer(CAM_LYR, CAM_SRC).withProperties(
            PropertyFactory.iconImage(ICON_CAMERA),
            PropertyFactory.iconSize(1.0f),
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
    style.getSourceAs<GeoJsonSource>(CAM_SRC)?.setGeoJson(
        if (PoiType.SPEED_CAMERAS in en && mapData != null)
            FeatureCollection.fromFeatures(mapData.cameras.map {
                Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })
        else empty)
}
