package com.rfsat.dms.nav

/**
 * Map-data features to overlay on the navigation map, pulled from the offline
 * map .db for the area in view. Which categories are actually populated depends
 * on the user's enabled POI set (see PoiType / NavSettings.enabledPois) — a
 * disabled category is left empty so its layer draws nothing. `enabled` carries
 * the set so MapLibreBase can also hide layers explicitly.
 */
data class MapOverlayData(
    val speedLimitLines: List<List<GeoPoint>> = emptyList(),
    val parking: List<GeoPoint> = emptyList(),
    val cameras: List<GeoPoint> = emptyList(),
    val enabled: Set<PoiType> = emptySet()
)
