package com.rfsat.dms.maps

import org.json.JSONObject

/**
 * The downloadable-map manifest, hosted at
 *   https://www.rfsat.com/products/maps/index.json
 *
 * Structure mirrors geofabrik's region hierarchy (continent -> country ->
 * optional sub-region) so region ids and names match the upstream source. For
 * countries geofabrik does not subdivide (e.g. Greece), there is a single
 * country-level entry. The format can still hold custom sub-regions if ever
 * needed, without restructuring.
 *
 * Each region carries a dataDate (the OSM data date the .db was built from) and
 * a version integer. "Outdated" = the server's version/dataDate is newer than
 * the copy stored on the device.
 */
data class MapCatalog(
    val schemaVersion: Int,
    val baseUrl: String,
    val updated: String,
    val regions: List<MapRegion>,
) {
    companion object {
        fun parse(json: String): MapCatalog {
            val o = JSONObject(json)
            val baseUrl = o.getString("baseUrl")
            val regions = ArrayList<MapRegion>()

            // Two accepted shapes: a flat "regions" array, or a nested
            // "countries" -> "regions". Both flatten to a region list with a
            // country label, so the UI can group by country.
            if (o.has("countries")) {
                val cs = o.getJSONArray("countries")
                for (i in 0 until cs.length()) {
                    val c = cs.getJSONObject(i)
                    val country = c.getString("name")
                    val countryId = c.getString("id")
                    val rs = c.optJSONArray("regions")
                    if (rs == null) {
                        // country itself is the downloadable unit (e.g. Greece)
                        regions.add(MapRegion.fromJson(c, country, countryId))
                    } else {
                        for (j in 0 until rs.length())
                            regions.add(MapRegion.fromJson(
                                rs.getJSONObject(j), country, countryId))
                    }
                }
            } else {
                val rs = o.getJSONArray("regions")
                for (i in 0 until rs.length()) {
                    val r = rs.getJSONObject(i)
                    regions.add(MapRegion.fromJson(
                        r, r.optString("country", r.getString("name")),
                        r.optString("country", r.getString("id"))))
                }
            }
            return MapCatalog(
                schemaVersion = o.optInt("schemaVersion", 1),
                baseUrl = baseUrl,
                updated = o.optString("updated", ""),
                regions = regions,
            )
        }
    }
}

/** One downloadable region (or a whole country, where not subdivided). */
data class MapRegion(
    val id: String,            // matches geofabrik region id, e.g. "greece"
    val name: String,          // display name, e.g. "Greece"
    val country: String,       // grouping label
    val countryId: String,
    val file: String,          // e.g. "greece.db"
    val sizeBytes: Long,
    val sha256: String,
    val dataDate: String,      // OSM data date the .db was built from
    val version: Int,          // bump when regenerated
    val dbSchemaVersion: Int,  // schema inside the .db (app must support it)
) {
    companion object {
        fun fromJson(o: JSONObject, country: String, countryId: String) = MapRegion(
            id = o.getString("id"),
            name = o.getString("name"),
            country = country,
            countryId = countryId,
            file = o.getString("file"),
            sizeBytes = o.optLong("sizeBytes", 0),
            sha256 = o.optString("sha256", ""),
            dataDate = o.optString("dataDate", ""),
            version = o.optInt("version", 1),
            dbSchemaVersion = o.optInt("dbSchemaVersion", 2),
        )
    }
}
