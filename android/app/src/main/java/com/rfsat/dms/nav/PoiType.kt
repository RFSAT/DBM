package com.rfsat.dms.nav

/**
 * Point-of-interest / map-data categories that can be shown on the navigation
 * map, selectable in Settings. This is the single place to extend: adding a new
 * category (fuel, charging, hospital…) means adding an entry here plus a query in
 * OsmMap.overlayNear() and a layer in MapLibreBase — the Settings list and the
 * enable/disable plumbing pick it up automatically.
 *
 * `available` marks whether the offline map data currently CONTAINS this type.
 * Unavailable types can still be listed (greyed out) so users see what's coming,
 * but they default to off and draw nothing until the map pre-processor emits them.
 */
enum class PoiType(
    val label: String,
    val available: Boolean,
    val defaultOn: Boolean
) {
    SPEED_LIMITS("Speed limits", available = true, defaultOn = true),
    SPEED_CAMERAS("Speed cameras", available = true, defaultOn = true),
    PARKING("Parking", available = true, defaultOn = true),
    // --- not yet in the offline data; listed for future map builds ---
    FUEL("Fuel stations", available = false, defaultOn = false),
    CHARGING("EV charging", available = false, defaultOn = false),
    HOSPITAL("Hospitals", available = false, defaultOn = false),
    REST_AREA("Rest areas", available = false, defaultOn = false);

    companion object {
        val defaults: Set<PoiType> get() = values().filter { it.defaultOn }.toSet()
    }
}
