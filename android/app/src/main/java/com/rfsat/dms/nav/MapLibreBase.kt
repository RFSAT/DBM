package com.rfsat.dms.nav

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
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * MapLibre-backed base view for navigation. Renders the real OSM road network
 * (raster street tiles by default — the whole surrounding network, not just the
 * route) and draws the active route line on top.
 *
 * - Zoom: pinch/double-tap are enabled by default (MapLibre UiSettings).
 * - Orientation: `headingUp`=false -> north-up (bearing 0); true -> the map
 *   rotates to the vehicle heading (bearingDeg), so "up" is where the car points.
 * - `tiltDegrees`: 0 flat 2D, ~45 perspective, ~62 steep 3D-style.
 * - `styleSpec`: either a full style JSON (inline object) or a style URL.
 */
@Composable
fun MapLibreBase(
    route: List<GeoPoint>?,
    center: GeoPoint?,
    tiltDegrees: Double,
    styleSpec: String,
    headingUp: Boolean,
    bearingDeg: Double,
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
    // Remember which style is currently loaded so we only reload on change.
    val loadedStyle = remember { mutableStateOf<String?>(null) }

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
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier) { mv ->
        val map = mapHolder.value
        if (map == null) {
            mv.getMapAsync { m ->
                mapHolder.value = m
                // Zoom + rotate gestures on; keep it simple and drivable.
                m.uiSettings.isZoomGesturesEnabled = true
                m.uiSettings.isRotateGesturesEnabled = true
                m.uiSettings.isTiltGesturesEnabled = true
                m.uiSettings.isCompassEnabled = true
                loadStyle(m, styleSpec) { style ->
                    styleHolder.value = style
                    loadedStyle.value = styleSpec
                    ensureRouteLayer(style)
                    pushRoute(style, route)
                    applyCamera(m, center, route, tiltDegrees, headingUp, bearingDeg)
                }
            }
        } else {
            // style changed? reload it (and re-add the route layer afterwards)
            if (loadedStyle.value != styleSpec) {
                loadStyle(map, styleSpec) { style ->
                    styleHolder.value = style
                    loadedStyle.value = styleSpec
                    ensureRouteLayer(style)
                    pushRoute(style, route)
                }
            }
            applyCamera(map, center, route, tiltDegrees, headingUp, bearingDeg)
            styleHolder.value?.let { pushRoute(it, route) }
        }
    }
}

private const val ROUTE_SRC = "dbm-route-src"
private const val ROUTE_LYR = "dbm-route-lyr"
private const val ROUTE_CASING_LYR = "dbm-route-casing"

private fun loadStyle(map: MapLibreMap, spec: String, onLoaded: (Style) -> Unit) {
    val builder = if (MapStyles.isInlineJson(spec))
        Style.Builder().fromJson(spec)
    else
        Style.Builder().fromUri(spec)
    map.setStyle(builder) { style -> onLoaded(style) }
}

private fun ensureRouteLayer(style: Style) {
    if (style.getSource(ROUTE_SRC) == null) {
        style.addSource(GeoJsonSource(ROUTE_SRC))
        // casing (dark, wider) under the coloured line for contrast over the map
        style.addLayer(LineLayer(ROUTE_CASING_LYR, ROUTE_SRC).withProperties(
            PropertyFactory.lineColor("#0C2E28"),
            PropertyFactory.lineWidth(9f)
        ))
        style.addLayer(LineLayer(ROUTE_LYR, ROUTE_SRC).withProperties(
            PropertyFactory.lineColor("#4DC494"),
            PropertyFactory.lineWidth(5f)
        ))
    }
}

private fun applyCamera(
    map: MapLibreMap, center: GeoPoint?, route: List<GeoPoint>?,
    tilt: Double, headingUp: Boolean, bearingDeg: Double
) {
    val c = center ?: route?.firstOrNull() ?: return
    map.cameraPosition = CameraPosition.Builder()
        .target(LatLng(c.lat, c.lon))
        .zoom(15.0)
        .tilt(tilt)
        .bearing(if (headingUp) bearingDeg else 0.0)   // north-up vs heading-up
        .build()
}

private fun pushRoute(style: Style, route: List<GeoPoint>?) {
    val src = style.getSourceAs<GeoJsonSource>(ROUTE_SRC) ?: return
    if (route == null || route.size < 2) {
        src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        return
    }
    val pts = route.map { Point.fromLngLat(it.lon, it.lat) }
    src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts)))
}
