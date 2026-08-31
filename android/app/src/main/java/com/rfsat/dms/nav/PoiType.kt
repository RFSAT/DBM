package com.rfsat.dms.nav

/**
 * Point-of-interest / map-data categories shown on the navigation map.
 *
 * Availability is now DATA-DRIVEN (Option B): the base types (speed limits,
 * cameras, parking) are always present in any built map; the extra types (fuel,
 * charging, hospital, rest areas) are available only when the loaded region .db
 * actually contains them — detected from the meta markers add_pois.py writes and
 * cached by the service. `metaKey` links an extra type to that data (and to its
 * DB table name); base types have no metaKey. Use NavSettings.availablePois for
 * the live set — do NOT read a hardcoded flag here.
 */
enum class PoiType(
    val label: String,
    val isBase: Boolean,      // always present in a built map
    val metaKey: String?,     // extra-POI table / meta key, or null for base types
    val defaultOn: Boolean
) {
    SPEED_LIMITS("Speed limits", isBase = true, metaKey = null, defaultOn = true),
    SPEED_CAMERAS("Speed cameras", isBase = true, metaKey = null, defaultOn = true),
    PARKING("Parking", isBase = true, metaKey = null, defaultOn = true),
    FUEL("Fuel stations", isBase = false, metaKey = "fuel", defaultOn = false),
    CHARGING("EV charging", isBase = false, metaKey = "charging", defaultOn = false),
    HOSPITAL("Hospitals", isBase = false, metaKey = "hospital", defaultOn = false),
    REST_AREA("Rest areas", isBase = false, metaKey = "rest_area", defaultOn = false);

    companion object {
        val defaults: Set<PoiType> get() = values().filter { it.defaultOn }.toSet()
        val base: Set<PoiType> get() = values().filter { it.isBase }.toSet()

        /** Resolve a set of extra-POI meta keys (e.g. from the .db) to the base
         *  types plus whichever extra types those keys enable. */
        fun availableFrom(extraKeys: Set<String>): Set<PoiType> =
            base + values().filter { it.metaKey != null && it.metaKey in extraKeys }
    }
}
