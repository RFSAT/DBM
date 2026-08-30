package com.rfsat.dms.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
 * MapLibre-backed base view for navigation. Renders real OSM vector tiles (free
 * demotiles style — no API key, no billing) and draws the active route as a
 * line. `tiltDegrees` selects the three map bases from one engine:
 *   0    -> flat 2D top-down
 *   ~45  -> 2D perspective (tilted bird's-eye)
 *   ~62  -> steep 3D-style view (true terrain/buildings need a 3D vector style;
 *           the demotiles style is flat, so this is a steep tilt for now)
 *
 * Lifecycle is forwarded from the Compose LifecycleOwner to the MapView (MapLibre
 * requires this). The route source/layer are created once and then updated in
 * place, so recomposition never re-adds a duplicate source.
 */
@Composable
fun MapLibreBase(
    route: List<GeoPoint>?,
    center: GeoPoint?,
    tiltDegrees: Double,
    styleUrl: String = "https://demotiles.maplibre.org/style.json",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // getInstance() must run before constructing a MapView. onCreate(null) here
    // (rather than via the lifecycle observer) guarantees it precedes onStart.
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    val mapHolder = remember { mutableStateOf<MapLibreMap?>(null) }
    val styleHolder = remember { mutableStateOf<Style?>(null) }

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
        val existing = mapHolder.value
        if (existing == null) {
            mv.getMapAsync { map ->
                mapHolder.value = map
                map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                    styleHolder.value = style
                    if (style.getSource(ROUTE_SRC) == null) {
                        style.addSource(GeoJsonSource(ROUTE_SRC))
                        style.addLayer(LineLayer(ROUTE_LYR, ROUTE_SRC).withProperties(
                            PropertyFactory.lineColor("#4DC494"),
                            PropertyFactory.lineWidth(6f)
                        ))
                    }
                    pushRoute(style, route)
                    applyCamera(map, center, route, tiltDegrees)
                }
            }
        } else {
            // recomposition: update camera + route in place
            applyCamera(existing, center, route, tiltDegrees)
            styleHolder.value?.let { pushRoute(it, route) }
        }
    }
}

private const val ROUTE_SRC = "dbm-route-src"
private const val ROUTE_LYR = "dbm-route-lyr"

private fun applyCamera(
    map: MapLibreMap, center: GeoPoint?, route: List<GeoPoint>?, tilt: Double
) {
    val c = center ?: route?.firstOrNull() ?: return
    map.cameraPosition = CameraPosition.Builder()
        .target(LatLng(c.lat, c.lon))
        .zoom(14.0)
        .tilt(tilt)
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
