package com.rfsat.dms.nav

/**
 * Map-data features to overlay on the navigation map, pulled from the app's
 * offline map .db for the area currently in view: speed-limit road segments,
 * parking locations, and speed cameras. Populated by MainActivity from OsmMap;
 * null means "don't draw these".
 */
data class MapOverlayData(
    val speedLimitLines: List<List<GeoPoint>> = emptyList(),
    val parking: List<GeoPoint> = emptyList(),
    val cameras: List<GeoPoint> = emptyList()
)
