package com.rfsat.dms.nav

/**
 * MapLibre style JSONs for the selectable base layers. All are raster sources
 * needing no API key (usage terms apply for wide distribution — swap for a keyed
 * provider like MapTiler then).
 *
 * NOTE on 3D: these are RASTER (flat image) tiles. Tilting them gives perspective
 * but no real 3D buildings — raster has no building shapes to extrude. True 3D
 * buildings require a VECTOR tile source with building heights (OpenMapTiles
 * schema), which needs a provider key. buildVectorStyle() is the hook for that.
 */
object MapStyles {

    private fun rasterStyle(tiles: String, attribution: String, maxZoom: Int = 19) = """
{
  "version": 8,
  "sources": {
    "base": {
      "type": "raster",
      "tiles": ["$tiles"],
      "tileSize": 256,
      "attribution": "$attribution",
      "maxzoom": $maxZoom
    }
  },
  "layers": [ { "id": "base", "type": "raster", "source": "base" } ]
}
"""

    val STREET = rasterStyle(
        "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        "© OpenStreetMap contributors")

    val SATELLITE = rasterStyle(
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        "© Esri, Maxar, Earthstar Geographics", maxZoom = 19)

    val TERRAIN = rasterStyle(
        "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
        "© OpenTopoMap (CC-BY-SA)", maxZoom = 17)

    fun styleFor(layer: MapLayer): String = when (layer) {
        MapLayer.STREET -> STREET
        MapLayer.SATELLITE -> SATELLITE
        MapLayer.TERRAIN -> TERRAIN
    }

    fun isInlineJson(style: String) = style.trimStart().startsWith("{")
}
