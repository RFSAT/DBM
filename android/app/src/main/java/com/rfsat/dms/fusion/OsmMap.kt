package com.rfsat.dms.fusion

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.rfsat.dms.util.DLog
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/** A road segment: an ordered polyline with an optional speed limit (km/h, or
 *  -1 if untagged). */
data class RoadSegment(
    val id: Long,
    val lat: DoubleArray,
    val lon: DoubleArray,
    val maxSpeed: Int,
)

/** A (lat,lon) pair for map-overlay features. */
data class DoublePair(val lat: Double, val lon: Double)

/** Map-display features near a point: speed-limit segment polylines, parking
 *  points, and speed-camera points. See OsmMap.overlayNear(). */
data class MapOverlay(
    val speedLimitLines: List<List<DoublePair>>,
    val parking: List<DoublePair>,
    val cameras: List<DoublePair>,
    val fuel: List<DoublePair> = emptyList(),
    val charging: List<DoublePair> = emptyList(),
    val hospital: List<DoublePair> = emptyList(),
    val restArea: List<DoublePair> = emptyList(),
    val tollBooth: List<DoublePair> = emptyList(),
    val borderControl: List<DoublePair> = emptyList(),
    val levelCrossing: List<DoublePair> = emptyList(),
    val speedBump: List<DoublePair> = emptyList(),
)

/** Result of matching a GPS point to the road network. */
data class MatchResult(val mapLimit: Int, val segId: Long, val distM: Double)

/**
 * On-device OSM road network backed by a SQLite + R-tree spatial database
 * (produced off-device by osm_to_speedlimitdb.py). Per GPS fix it queries the
 * R-tree for segments NEAR the point — not the whole region — then applies the
 * validated heading-consistency + hysteresis matching to that small candidate
 * set. This scales to a whole-country database where loading every segment into
 * memory would not.
 *
 * The matching logic (heading folding, hysteresis, point-to-polyline distance)
 * is unchanged from the validated MATLAB prototype; only the data source moved
 * from an in-memory list to a spatial query.
 *
 * Stateful (hysteresis): call match() once per GPS fix in order. Close with
 * close() when done.
 */
class OsmMap private constructor(private val db: SQLiteDatabase) {

    /**
     * Parking data lives in the SAME region database (schema v4+, written by
     * tools/add_parking.py). Sharing this handle avoids opening the file twice.
     * Null when the downloaded region predates parking support, so older maps
     * keep working with the feature simply unavailable.
     */
    val parking: com.rfsat.dms.parking.ParkingMonitor? by lazy {
        com.rfsat.dms.parking.ParkingMonitor.from(db)
    }

    /** Speed-camera data (schema v5+), sharing this db handle. Null on older
     *  region databases, so the feature is simply unavailable there. */
    val cameras: CameraMonitor? by lazy { CameraMonitor.from(db) }


    private var lastSeg: Long = -1L

    companion object {
        private const val TAG = "OsmMap"

        // Tunables — from defaultFuseConfig.m (validated on real drives).
        const val MATCH_MAX_DIST_M = 25.0
        const val MATCH_HYSTERESIS_M = 8.0
        const val HEADING_TOL_DEG = 35.0
        const val HEADING_WEIGHT = 0.5
        private const val EARTH_R = 6371000.0

        // Spatial-query window: ~250 m in degrees. Big enough to include the
        // correct road plus neighbours for hysteresis, small enough that the
        // R-tree returns only a handful of candidates.
        private const val QUERY_MARGIN_DEG = 0.0025

        /**
         * Open a speed-limit database. Looks in the app's files dir first (where
         * the downloader places it), then /sdcard/Download (for manual adb-push
         * during development). Returns null if no database is found or it cannot
         * be opened — the fuser then runs sign+cache only (no crash).
         */
        fun open(ctx: Context, fileName: String): OsmMap? {
            // Search order, all readable under scoped storage without permissions:
            //  1. app internal files/maps        — where the importer/downloader writes
            //  2. app external files/maps         — USB-droppable: Android/data/<pkg>/files/maps
            //  3. legacy public Download paths    — only work on older Android / with
            //                                       permission; kept as a dev fallback
            val candidates = listOfNotNull(
                File(File(ctx.filesDir, "maps"), fileName),
                ctx.getExternalFilesDir("maps")?.let { File(it, fileName) },
                File("/sdcard/Download/RFSAT-DBM", fileName),
                File("/sdcard/Download", fileName),
                @Suppress("DEPRECATION")
                File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), fileName),
            )
            val f = candidates.firstOrNull { it.exists() && it.canRead() }
            if (f == null) {
                DLog.w(TAG, "no map db '$fileName' found in ${candidates.map { it.path }}")
                return null
            }
            return runCatching {
                val db = SQLiteDatabase.openDatabase(
                    f.path, null, SQLiteDatabase.OPEN_READONLY)
                val meta = readMeta(db)
                DLog.i(TAG, "opened map db ${f.path}: region=${meta["region"]} " +
                    "segments=${meta["segments"]} schema=${meta["schema_version"]}")
                OsmMap(db)
            }.onFailure { DLog.e(TAG, "open map db failed: ${f.path}", it) }.getOrNull()
        }

        private fun readMeta(db: SQLiteDatabase): Map<String, String> {
            val m = HashMap<String, String>()
            runCatching {
                db.rawQuery("SELECT key,value FROM meta", null).use { c ->
                    while (c.moveToNext()) m[c.getString(0)] = c.getString(1)
                }
            }
            return m
        }
    }

    /**
     * Match a GPS point to the best nearby road segment. headingDeg in degrees
     * (0=east in the local metric frame) or NaN if unknown. Returns limit -1
     * when the matched road has no maxspeed, or when nothing is within range.
     */
    fun match(lat: Double, lon: Double, headingDeg: Double): MatchResult {
        val lat0 = lat * Math.PI / 180.0
        val candidates = queryNear(lat, lon)
        if (candidates.isEmpty()) { lastSeg = -1L; return MatchResult(-1, -1L, Double.MAX_VALUE) }

        var bestScore = Double.MAX_VALUE
        var bestDist = Double.MAX_VALUE
        var bestId = -1L
        var bestLimit = -1
        var prevDist = Double.MAX_VALUE
        var prevLimit = -1
        var prevId = -1L

        for (r in candidates) {
            val (d, segHead) = pointToPolyline(lat, lon, lat0, r.lat, r.lon)
            if (r.id == lastSeg) { prevDist = d; prevLimit = r.maxSpeed; prevId = r.id }
            if (d > MATCH_MAX_DIST_M) continue
            var score = d
            if (!headingDeg.isNaN() && !segHead.isNaN()) {
                var dh = angDiff(headingDeg, segHead)
                dh = min(dh, 180.0 - dh)               // roads undirected
                if (dh > HEADING_TOL_DEG) score += HEADING_WEIGHT * (dh - HEADING_TOL_DEG)
            }
            if (score < bestScore) {
                bestScore = score; bestDist = d; bestId = r.id; bestLimit = r.maxSpeed
            }
        }

        // Hysteresis: keep the previous segment unless clearly beaten.
        if (prevId != -1L && prevDist <= MATCH_MAX_DIST_M &&
            prevDist <= bestDist + MATCH_HYSTERESIS_M) {
            bestId = prevId; bestDist = prevDist; bestLimit = prevLimit
        }

        if (bestId == -1L || bestDist > MATCH_MAX_DIST_M) {
            lastSeg = -1L; return MatchResult(-1, -1L, bestDist)
        }
        lastSeg = bestId
        return MatchResult(bestLimit, bestId, bestDist)
    }

    /** R-tree query: segments whose bounding box overlaps a small window around
     *  the point. Reads only those segments' geometry, not the whole network. */
    /**
     * Features for map display in the given area: speed-limit road-segment
     * polylines (only segments that HAVE a limit), parking points, and speed
     * cameras. Reuses the same bbox query as navigation matching. Coordinates are
     * (lat,lon) pairs. Cheap enough to call as the map view moves (debounced by
     * the caller). marginDeg widens the query box to roughly the visible area.
     */
    fun overlayNear(lat: Double, lon: Double, marginDeg: Double = 0.02): MapOverlay {
        val limits = ArrayList<List<DoublePair>>()
        val sql = """
            SELECT maxspeed, coords FROM segments
            WHERE maxLat >= ? AND minLat <= ? AND maxLon >= ? AND minLon <= ?
              AND maxspeed > 0
        """.trimIndent()
        runCatching {
            db.rawQuery(sql, arrayOf(
                (lat - marginDeg).toString(), (lat + marginDeg).toString(),
                (lon - marginDeg).toString(), (lon + marginDeg).toString())).use { c ->
                var n = 0
                while (c.moveToNext() && n < 4000) {   // cap to keep it light
                    val blob = c.getBlob(1)
                    val (la, lo) = unpackCoords(blob)
                    if (la.size >= 2) {
                        limits.add(la.indices.map { DoublePair(la[it], lo[it]) })
                        n++
                    }
                }
            }
        }.onFailure { DLog.e(TAG, "overlay segment query failed", it) }

        val park = runCatching {
            parking?.lotsNear(lat, lon, radiusM = 3000.0, publicOnly = false,
                limit = 500)?.map { DoublePair(it.lat, it.lon) } ?: emptyList()
        }.getOrDefault(emptyList())

        // cameras + the optional extra POI tables (fuel/charging/hospital/
        // rest_area) all share the same "points in bbox" query.
        val cams = queryPoiPoints("speed_camera", lat, lon, marginDeg)
        val fuel = queryPoiPoints("fuel", lat, lon, marginDeg)
        val charging = queryPoiPoints("charging", lat, lon, marginDeg)
        val hospital = queryPoiPoints("hospital", lat, lon, marginDeg)
        val restArea = queryPoiPoints("rest_area", lat, lon, marginDeg)
        val tollBooth = queryPoiPoints("toll_booth", lat, lon, marginDeg)
        val borderControl = queryPoiPoints("border_control", lat, lon, marginDeg)
        val levelCrossing = queryPoiPoints("level_crossing", lat, lon, marginDeg)
        val speedBump = queryPoiPoints("speed_bump", lat, lon, marginDeg)

        return MapOverlay(limits, park, cams, fuel, charging, hospital, restArea,
                          tollBooth, borderControl, levelCrossing, speedBump)
    }

    /** Points (lat,lon) from a table that has a lat/lon column, within a bbox.
     *  Returns empty if the table doesn't exist (older .db without extra POIs). */
    /** Points from a POI table that lie AHEAD in the direction of travel, within
     *  aheadM metres — mirrors CameraMonitor.camerasAhead but generic, for the
     *  level-crossing / speed-bump approach warnings. Returns (lat,lon,distM)
     *  sorted nearest-first; empty if the table doesn't exist. */
    fun hazardsAhead(table: String, lat: Double, lon: Double, headingDeg: Float,
                     aheadM: Double): List<Triple<Double, Double, Double>> {
        val out = ArrayList<Triple<Double, Double, Double>>(4)
        runCatching {
            val hasTable = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table)).use { it.moveToNext() }
            if (!hasTable) return emptyList()
            val dLat = aheadM / 111_320.0
            val dLon = aheadM / (111_320.0 *
                Math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
            db.rawQuery(
                "SELECT lat, lon FROM $table " +
                "WHERE maxLat >= ? AND minLat <= ? AND maxLon >= ? AND minLon <= ?",
                arrayOf((lat - dLat).toString(), (lat + dLat).toString(),
                        (lon - dLon).toString(), (lon + dLon).toString())).use { c ->
                while (c.moveToNext()) {
                    val pla = c.getDouble(0); val plo = c.getDouble(1)
                    val d = distMeters(lat, lon, pla, plo)
                    if (d > aheadM) continue
                    val brg = bearingDegTo(lat, lon, pla, plo)
                    if (angleDiffDeg(brg, headingDeg.toDouble()) > 55.0) continue
                    out.add(Triple(pla, plo, d))
                }
            }
        }.onFailure { DLog.e(TAG, "hazardsAhead($table) failed", it) }
        return out.sortedBy { it.third }
    }

    private fun distMeters(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val dLat = Math.toRadians(la2 - la1); val dLon = Math.toRadians(lo2 - lo1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun bearingDegTo(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val y = Math.sin(Math.toRadians(lo2 - lo1)) * Math.cos(Math.toRadians(la2))
        val x = Math.cos(Math.toRadians(la1)) * Math.sin(Math.toRadians(la2)) -
            Math.sin(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) *
            Math.cos(Math.toRadians(lo2 - lo1))
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }

    private fun angleDiffDeg(a: Double, b: Double): Double {
        val d = Math.abs(a - b) % 360; return if (d > 180) 360 - d else d
    }

    private fun queryPoiPoints(
        table: String, lat: Double, lon: Double, marginDeg: Double, cap: Int = 500
    ): List<DoublePair> {
        val out = ArrayList<DoublePair>()
        runCatching {
            val hasTable = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table)).use { it.moveToNext() }
            if (!hasTable) return emptyList()
            db.rawQuery(
                "SELECT lat, lon FROM $table " +
                "WHERE lat >= ? AND lat <= ? AND lon >= ? AND lon <= ?",
                arrayOf((lat - marginDeg).toString(), (lat + marginDeg).toString(),
                        (lon - marginDeg).toString(), (lon + marginDeg).toString())
            ).use { c ->
                var n = 0
                while (c.moveToNext() && n < cap) {
                    out.add(DoublePair(c.getDouble(0), c.getDouble(1))); n++
                }
            }
        }.onFailure { DLog.e(TAG, "overlay $table query failed", it) }
        return out
    }

    /** Which optional POI types this loaded .db actually contains, from the meta
     *  markers add_pois.py writes (poi_<type>_count > 0). The base types (speed
     *  limits, cameras, parking) are always present in a built map. Returns the
     *  extra type keys: "fuel", "charging", "hospital", "rest_area". */
    fun availableExtraPois(): Set<String> {
        val meta = runCatching { readMeta(db) }.getOrDefault(emptyMap())
        val out = HashSet<String>()
        for (t in listOf("fuel", "charging", "hospital", "rest_area",
                         "level_crossing", "speed_bump", "toll_booth",
                         "border_control")) {
            val c = meta["poi_${t}_count"]?.toIntOrNull() ?: 0
            if (c > 0) out.add(t)
        }
        return out
    }

    private fun queryNear(lat: Double, lon: Double): List<RoadSegment> {
        val out = ArrayList<RoadSegment>(16)
        // Android's built-in SQLite has no rtree module, so we filter on plain
        // bbox columns (indexed on minLat/maxLat). Schema v3 stores these.
        val sql = """
            SELECT id, maxspeed, coords FROM segments
            WHERE maxLat >= ? AND minLat <= ? AND maxLon >= ? AND minLon <= ?
        """.trimIndent()
        val args = arrayOf(
            (lat - QUERY_MARGIN_DEG).toString(), (lat + QUERY_MARGIN_DEG).toString(),
            (lon - QUERY_MARGIN_DEG).toString(), (lon + QUERY_MARGIN_DEG).toString(),
        )
        runCatching {
            db.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val ms = c.getInt(1)
                    val blob = c.getBlob(2)
                    val (la, lo) = unpackCoords(blob)
                    if (la.size >= 2) out.add(RoadSegment(id, la, lo, ms))
                }
            }
        }.onFailure { DLog.e(TAG, "spatial query failed", it) }
        return out
    }

    /** Decode packed coordinates written by the pre-processor. Schema v2 stores
     *  scaled int32 (degrees * 1e7) — half the size of float64, ~1 cm precision. */
    private fun unpackCoords(blob: ByteArray): Pair<DoubleArray, DoubleArray> {
        val n = blob.size / 8                       // 2 x int32 per point
        val la = DoubleArray(n); val lo = DoubleArray(n)
        val bb = java.nio.ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            la[i] = bb.int / 1e7
            lo[i] = bb.int / 1e7
        }
        return la to lo
    }

    fun close() = runCatching { db.close() }

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

    private fun angDiff(a: Double, b: Double): Double =
        abs((((a - b) % 360.0) + 540.0) % 360.0 - 180.0)
}
