package com.rfsat.dms.fusion

import android.content.Context
import com.rfsat.dms.util.DLog
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/** A road segment from OSM: an ordered polyline with an optional speed limit. */
data class RoadSegment(
    val id: Long,
    val lat: DoubleArray,
    val lon: DoubleArray,
    val maxSpeed: Int,            // km/h, or -1 if untagged
)

/** Result of matching a GPS point to the road network. */
data class MatchResult(val mapLimit: Int, val segId: Long, val distM: Double)

/**
 * On-device OSM road network with nearest-segment matching, ported from the
 * validated MATLAB prototype (loadOSM.m + matchSegment.m). Loads a bundled .osm
 * XML extract once, then answers "what is the speed limit on the road at this
 * lat/lon" with heading consistency + hysteresis to avoid parallel-road and
 * single-sample mismatches.
 *
 * The matcher state (last matched segment) is held here, so call match() once
 * per GPS fix in order.
 */
class OsmMap private constructor(private val roads: List<RoadSegment>) {

    private var lastSeg: Long = -1L

    companion object {
        private const val TAG = "OsmMap"

        // Tunables — copied from defaultFuseConfig.m (validated on real drives).
        const val MATCH_MAX_DIST_M = 25.0
        const val MATCH_HYSTERESIS_M = 8.0
        const val HEADING_TOL_DEG = 35.0
        const val HEADING_WEIGHT = 0.5      // metres of penalty per degree over tol

        private const val EARTH_R = 6371000.0

        /** Load from a bundled assets file (e.g. "attiki.osm"). Returns null on
         *  failure so the caller can run without a map (sign-only). */
        fun fromAsset(ctx: Context, assetName: String): OsmMap? = runCatching {
            ctx.assets.open(assetName).use { parse(it) }
        }.onFailure { DLog.e(TAG, "OSM load failed: $assetName", it) }.getOrNull()

        /** Minimal OSM XML parser: nodes (id->lat/lon) then ways (highway with
         *  node refs + maxspeed). Mirrors loadOSM.m. */
        private fun parse(input: java.io.InputStream): OsmMap {
            val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
            val xpp = factory.newPullParser()
            xpp.setInput(input, null)

            val nodeLat = HashMap<Long, Double>(100_000)
            val nodeLon = HashMap<Long, Double>(100_000)
            val roads = ArrayList<RoadSegment>(10_000)

            var inWay = false
            var wayIsHighway = false
            var wayMax = -1
            var wayId = 0L
            val wayNodes = ArrayList<Long>(64)

            var event = xpp.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    when (xpp.name) {
                        "node" -> {
                            val id = xpp.getAttributeValue(null, "id")?.toLongOrNull()
                            val la = xpp.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            val lo = xpp.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            if (id != null && la != null && lo != null) {
                                nodeLat[id] = la; nodeLon[id] = lo
                            }
                        }
                        "way" -> {
                            inWay = true; wayIsHighway = false; wayMax = -1
                            wayId = xpp.getAttributeValue(null, "id")?.toLongOrNull() ?: 0L
                            wayNodes.clear()
                        }
                        "nd" -> if (inWay) {
                            xpp.getAttributeValue(null, "ref")?.toLongOrNull()
                                ?.let { wayNodes.add(it) }
                        }
                        "tag" -> if (inWay) {
                            val k = xpp.getAttributeValue(null, "k")
                            val v = xpp.getAttributeValue(null, "v")
                            if (k == "highway") wayIsHighway = true
                            if (k == "maxspeed" && v != null) {
                                val num = Regex("\\d+").find(v)?.value?.toIntOrNull()
                                if (num != null) {
                                    wayMax = if (v.contains("mph"))
                                        (num * 1.60934).toInt() else num
                                }
                            }
                        }
                    }
                } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG && xpp.name == "way") {
                    if (inWay && wayIsHighway && wayNodes.size >= 2) {
                        val la = DoubleArray(wayNodes.size)
                        val lo = DoubleArray(wayNodes.size)
                        var k = 0
                        for (ref in wayNodes) {
                            val a = nodeLat[ref]; val b = nodeLon[ref]
                            if (a != null && b != null) { la[k] = a; lo[k] = b; k++ }
                        }
                        if (k >= 2) roads.add(
                            RoadSegment(wayId, la.copyOf(k), lo.copyOf(k), wayMax))
                    }
                    inWay = false
                }
                event = xpp.next()
            }
            val tagged = roads.count { it.maxSpeed > 0 }
            DLog.i(TAG, "OSM parsed: ${roads.size} road segments ($tagged with maxspeed)")
            return OsmMap(roads)
        }
    }

    val size: Int get() = roads.size

    /**
     * Match a GPS point to the best road segment. heading in degrees (0=east in
     * the local metric frame) or NaN if unknown. Returns a limit of -1 when the
     * matched road has no maxspeed, or when nothing is within MATCH_MAX_DIST_M.
     */
    fun match(lat: Double, lon: Double, headingDeg: Double): MatchResult {
        if (roads.isEmpty()) return MatchResult(-1, -1L, Double.MAX_VALUE)
        val lat0 = lat * Math.PI / 180.0

        var bestScore = Double.MAX_VALUE
        var bestDist = Double.MAX_VALUE
        var bestId = -1L
        var bestLimit = -1
        var prevDist = Double.MAX_VALUE
        var prevLimit = -1
        var prevId = -1L

        for (r in roads) {
            val (d, segHead) = pointToPolyline(lat, lon, lat0, r.lat, r.lon)
            if (d > MATCH_MAX_DIST_M) {
                if (r.id == lastSeg) { prevDist = d }   // still track prev even if far
                continue
            }
            var score = d
            if (!headingDeg.isNaN() && !segHead.isNaN()) {
                var dh = angDiff(headingDeg, segHead)
                dh = min(dh, 180.0 - dh)                // roads undirected
                if (dh > HEADING_TOL_DEG) score += HEADING_WEIGHT * (dh - HEADING_TOL_DEG)
            }
            if (score < bestScore) {
                bestScore = score; bestDist = d; bestId = r.id; bestLimit = r.maxSpeed
            }
            if (r.id == lastSeg) { prevDist = d; prevLimit = r.maxSpeed; prevId = r.id }
        }

        // Hysteresis: keep the previous segment unless clearly beaten.
        if (prevId != -1L && prevDist <= MATCH_MAX_DIST_M &&
            prevDist <= bestDist + MATCH_HYSTERESIS_M) {
            bestId = prevId; bestDist = prevDist; bestLimit = prevLimit
        }

        if (bestDist > MATCH_MAX_DIST_M) { lastSeg = -1L; return MatchResult(-1, -1L, bestDist) }
        lastSeg = bestId
        return MatchResult(bestLimit, bestId, bestDist)
    }

    private fun pointToPolyline(
        plat: Double, plon: Double, lat0: Double, vlat: DoubleArray, vlon: DoubleArray
    ): Pair<Double, Double> {
        val px = EARTH_R * (plon * Math.PI / 180.0) * cos(lat0)
        val py = EARTH_R * (plat * Math.PI / 180.0)
        var d = Double.MAX_VALUE
        var head = Double.NaN
        for (i in 0 until vlat.size - 1) {
            val ax = EARTH_R * (vlon[i] * Math.PI / 180.0) * cos(lat0)
            val ay = EARTH_R * (vlat[i] * Math.PI / 180.0)
            val bx = EARTH_R * (vlon[i + 1] * Math.PI / 180.0) * cos(lat0)
            val by = EARTH_R * (vlat[i + 1] * Math.PI / 180.0)
            val (dd, hh) = segDistHead(px, py, ax, ay, bx, by)
            if (dd < d) { d = dd; head = hh }
        }
        return d to head
    }

    private fun segDistHead(
        px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double
    ): Pair<Double, Double> {
        val abx = bx - ax; val aby = by - ay
        val apx = px - ax; val apy = py - ay
        val denom = abx * abx + aby * aby
        if (denom < 1e-9) return hypot(apx, apy) to Double.NaN
        var t = (apx * abx + apy * aby) / denom
        t = t.coerceIn(0.0, 1.0)
        val cx = ax + t * abx; val cy = ay + t * aby
        val d = hypot(px - cx, py - cy)
        val head = ((atan2(aby, abx) * 180.0 / Math.PI) % 360.0 + 360.0) % 360.0
        return d to head
    }

    private fun angDiff(a: Double, b: Double): Double {
        val d = abs((((a - b) % 360.0) + 540.0) % 360.0 - 180.0)
        return d
    }
}
