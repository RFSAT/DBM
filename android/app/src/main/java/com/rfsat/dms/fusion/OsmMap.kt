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
            val candidates = listOf(
                File(File(ctx.filesDir, "maps"), fileName),
                File(ctx.getExternalFilesDir("maps"), fileName),
                File("/sdcard/Download/RFSAT-DBM", fileName),
                File("/sdcard/Download", fileName),
            )
            val f = candidates.firstOrNull { it.exists() }
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
    private fun queryNear(lat: Double, lon: Double): List<RoadSegment> {
        val out = ArrayList<RoadSegment>(16)
        val sql = """
            SELECT s.id, s.maxspeed, s.coords
            FROM segment_rtree r JOIN segments s ON s.id = r.id
            WHERE r.maxLat >= ? AND r.minLat <= ? AND r.maxLon >= ? AND r.minLon <= ?
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
