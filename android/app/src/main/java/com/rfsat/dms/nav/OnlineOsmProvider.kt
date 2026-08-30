package com.rfsat.dms.nav

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Real online routing/geocoding provider built entirely on free OpenStreetMap
 * services — no API key, no billing:
 *   - routing:   OSRM      (router.project-osrm.org demo, or your own server)
 *   - geocoding: Nominatim (nominatim.openstreetmap.org)
 *
 * This is the concrete online NavProvider. It implements the same interface as
 * the stub, so the UI needs no changes to use real routes. An OfflineProvider
 * (downloaded maps + on-device routing) will later implement the same interface.
 *
 * NOTE on the public demo servers: they are rate-limited and best-effort, meant
 * for light use and testing. For production, point OSRM_BASE at your own OSRM
 * (or GraphHopper/Valhalla) instance. Nominatim's policy requires a real
 * User-Agent and <=1 request/second — respected here.
 */
class OnlineOsmProvider(
    private val osrmBase: String = "https://router.project-osrm.org",
    private val nominatimBase: String = "https://nominatim.openstreetmap.org",
    private val userAgent: String = "RFSAT-DBM/1.0 (navigation; contact rfsat.com)"
) : NavProvider {

    override val name = "OpenStreetMap (online)"
    override val isOnline = true
    override fun available() = true      // caller checks connectivity separately

    override suspend fun route(from: GeoPoint, to: GeoPoint): Route? =
        routeVia(listOf(from, to))

    override suspend fun routeVia(points: List<GeoPoint>): Route? {
        if (points.size < 2) return null
        // OSRM expects lon,lat pairs separated by ';', in order.
        val coords = points.joinToString(";") { "${it.lon},${it.lat}" }
        val url = "$osrmBase/route/v1/driving/$coords" +
                "?overview=full&geometries=geojson&steps=true&annotations=false"
        val json = httpGetJson(url) ?: return null
        val routes = json.optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null
        val r0 = routes.getJSONObject(0)

        // full polyline
        val gcoords = r0.getJSONObject("geometry").getJSONArray("coordinates")
        val polyline = ArrayList<GeoPoint>(gcoords.length())
        for (i in 0 until gcoords.length()) {
            val c = gcoords.getJSONArray(i)
            polyline.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
        }

        // steps across all legs (one leg per waypoint pair)
        val steps = ArrayList<RouteStep>()
        val legs = r0.optJSONArray("legs") ?: JSONArray()
        for (li in 0 until legs.length()) {
            val legSteps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
            for (si in 0 until legSteps.length()) {
                val st = legSteps.getJSONObject(si)
                val man = st.getJSONObject("maneuver")
                val loc = man.getJSONArray("location")
                val at = GeoPoint(loc.getDouble(1), loc.getDouble(0))
                val road = st.optString("name", "")
                steps.add(RouteStep(
                    at = at,
                    maneuver = mapManeuver(man.optString("type"), man.optString("modifier")),
                    instruction = instructionText(man.optString("type"),
                        man.optString("modifier"), road),
                    distanceMeters = st.optDouble("distance", 0.0),
                    laneHint = null
                ))
            }
        }
        return Route(polyline, steps,
            r0.optDouble("distance", 0.0), r0.optDouble("duration", 0.0))
    }

    override suspend fun search(query: String): List<Pair<String, GeoPoint>> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$nominatimBase/search?q=$q&format=json&limit=6"
        val arr = httpGetJsonArray(url) ?: return emptyList()
        val out = ArrayList<Pair<String, GeoPoint>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(o.optString("display_name") to
                GeoPoint(o.getDouble("lat"), o.getDouble("lon")))
        }
        return out
    }

    // ---- OSRM maneuver mapping ----------------------------------------------

    private fun mapManeuver(type: String, modifier: String): Maneuver = when {
        type == "arrive" -> Maneuver.ARRIVE
        type == "roundabout" || type == "rotary" -> Maneuver.ROUNDABOUT
        type == "merge" -> Maneuver.MERGE
        type.startsWith("exit") || type == "off ramp" -> Maneuver.EXIT
        modifier.contains("uturn") -> Maneuver.UTURN
        modifier == "sharp left" -> Maneuver.SHARP_LEFT
        modifier == "sharp right" -> Maneuver.SHARP_RIGHT
        modifier == "slight left" -> Maneuver.SLIGHT_LEFT
        modifier == "slight right" -> Maneuver.SLIGHT_RIGHT
        modifier == "left" -> Maneuver.TURN_LEFT
        modifier == "right" -> Maneuver.TURN_RIGHT
        else -> Maneuver.STRAIGHT
    }

    private fun instructionText(type: String, modifier: String, road: String): String {
        val where = if (road.isNotBlank()) " onto $road" else ""
        return when {
            type == "arrive" -> "Arrive at destination"
            type == "depart" -> "Head out" + (if (road.isNotBlank()) " on $road" else "")
            type == "roundabout" || type == "rotary" -> "Take the roundabout$where"
            type == "merge" -> "Merge$where"
            type.startsWith("exit") -> "Take the exit$where"
            modifier.contains("uturn") -> "Make a U-turn"
            modifier.contains("left") -> "Turn ${modifier}$where"
            modifier.contains("right") -> "Turn ${modifier}$where"
            modifier == "straight" || modifier.isBlank() ->
                "Continue straight" + (if (road.isNotBlank()) " on $road" else "")
            else -> "Continue $modifier$where"
        }
    }

    // ---- HTTP helpers --------------------------------------------------------

    private fun httpGetJson(url: String): JSONObject? =
        httpGet(url)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun httpGetJsonArray(url: String): JSONArray? =
        httpGet(url)?.let { runCatching { JSONArray(it) }.getOrNull() }

    private fun httpGet(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("User-Agent", userAgent)   // required by Nominatim
        }
        try {
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }.getOrNull()
}
