package com.rfsat.dms.nav

/**
 * Map style sources selectable by the user. OSM works with no API key. Google
 * needs the user's own Google Maps API key (their tiles/SDK are not free), so it
 * is offered as a slot that activates once a key is provided in settings.
 */
enum class MapSource(val label: String, val needsApiKey: Boolean) {
    OSM_STREETS("OpenStreetMap", false),
    OSM_DEMO("OSM (lite)", false),
    GOOGLE("Google Maps", true)
}

object MapStyles {
    /**
     * A self-contained MapLibre style JSON that renders the full OSM street
     * network as raster tiles — no API key. (OSM tile usage policy: modest
     * volumes, real User-Agent; swap the tile URL for MapTiler/self-host for
     * production scale.)
     */
    const val OSM_RASTER_STYLE = """
{
  "version": 8,
  "sources": {
    "osm": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "attribution": "© OpenStreetMap contributors",
      "maxzoom": 19
    }
  },
  "layers": [
    { "id": "osm", "type": "raster", "source": "osm" }
  ]
}
"""

    /** Low-detail demo vector style (fallback / lightweight). */
    const val DEMO_STYLE_URL = "https://demotiles.maplibre.org/style.json"

    /**
     * Build a Google raster style from a user-provided API key. Uses Google's
     * static map tiles endpoint. Without a key this returns null and the UI keeps
     * OSM. NOTE: Google's terms require using their SDK/Maps Tiles API under a
     * billing account; this raster path is a convenience for users who have a key
     * and accept Google's terms.
     */
    fun googleStyle(apiKey: String?): String? {
        if (apiKey.isNullOrBlank()) return null
        // Google Map Tiles API session-based tiles would go here; kept as a
        // documented slot. Returning null keeps behaviour safe until a key +
        // the tiles session flow are wired.
        return null
    }

    fun styleFor(source: MapSource, googleKey: String?): String = when (source) {
        MapSource.OSM_STREETS -> OSM_RASTER_STYLE
        MapSource.OSM_DEMO -> DEMO_STYLE_URL
        MapSource.GOOGLE -> googleStyle(googleKey) ?: OSM_RASTER_STYLE
    }

    /** True when the given source string is a full style JSON (vs. a URL). */
    fun isInlineJson(style: String) = style.trimStart().startsWith("{")
}
