package com.rfsat.dms.fusion

import android.database.sqlite.SQLiteDatabase
import com.rfsat.dms.util.DLog

/** A speed camera near the driver. */
data class SpeedCamera(
    val lat: Double,
    val lon: Double,
    val maxspeed: Int?,        // enforced limit, if known
    val direction: String?,    // raw OSM tag: forward | backward | both | compass
    val distanceM: Double,     // distance from the query point
    /** Bearing of the traffic this camera measures (deg, 0=N), resolved at map
     *  build time from the camera's parent carriageway. Null = not resolvable,
     *  which means "assume it applies to us". */
    val trafficDeg: Double? = null,
    /** True when [trafficDeg] was known and matched our direction of travel, so
     *  the warning is backed by data rather than by the fail-open default. */
    val directionConfirmed: Boolean = false,
)

/**
 * Reads the speed_camera table written by add_parking.py (schema v5+). Shares
 * the region database handle already opened by OsmMap.
 *
 * DIRECTION OF TRAVEL. Every camera in the area is drawn on the map (that is
 * OsmMap.overlayNear's job, and it filters nothing). This class serves the
 * WARNING path only, and a warning is suppressed when the camera provably
 * measures the opposite direction — a camera on the other carriageway of a dual
 * carriageway is metres away and straight ahead, but has nothing to do with us.
 *
 * Three signals, in order of trust:
 *
 *  1. trafficDeg (schema v6): the bearing of the traffic the camera monitors,
 *     resolved off-device from its parent way — forward/backward against the
 *     way as drawn, or the travel direction of a one-way carriageway. Decisive:
 *     more than [OPPOSING_TOL_DEG] off our heading and the camera is not ours.
 *  2. a compass `direction` with no resolved bearing (older maps, or a two-way
 *     road): per the OSM wiki this is the way the camera FACES, which is
 *     parallel to its road but may point with or against the traffic it
 *     measures, so it cannot separate the two carriageways. It is still a good
 *     test for "is this camera even on my road": a facing roughly perpendicular
 *     to our heading belongs to a crossing street.
 *  3. nothing at all: warn. Most cameras carry no direction tag, and staying
 *     quiet on a guess would be the dangerous failure.
 *
 * LEGAL NOTE: dynamic speed-camera warnings are prohibited in some countries.
 * This class only READS data; whether a warning is surfaced is gated by a user
 * opt-in in settings ("hazard_speed_cameras", off by default), per the OSM
 * guidance that such warnings must be an explicit choice.
 */
class CameraMonitor private constructor(private val db: SQLiteDatabase) {

    private var lastSkipLogMs = 0L

    /** Whether this region db carries the v6 resolved-direction column. Probed
     *  once, because older maps must keep working unchanged. */
    private val hasTrafficDeg: Boolean by lazy {
        runCatching {
            db.rawQuery("PRAGMA table_info(speed_camera)", null).use { c ->
                val ni = c.getColumnIndex("name")
                var found = false
                while (c.moveToNext()) if (c.getString(ni) == "trafficDeg") found = true
                found
            }
        }.getOrDefault(false).also {
            DLog.i(TAG, if (it) "map has resolved camera directions (schema v6+)"
                        else "map has no resolved camera directions (pre-v6) — " +
                             "opposite-carriageway cameras can only be filtered " +
                             "by the facing tag")
        }
    }

    /**
     * Cameras within [aheadM] metres that are ahead of the driver along
     * [headingDeg] AND not known to measure the opposite direction.
     *
     * [headingDeg] must be a TRUE COMPASS bearing (0 = north, clockwise) — the
     * same frame as the bearings computed here. It is not the math-frame heading
     * OsmMap.match() takes.
     */
    fun camerasAhead(
        lat: Double, lon: Double, headingDeg: Float, aheadM: Double = 600.0,
    ): List<SpeedCamera> {
        if (headingDeg.isNaN()) return emptyList()
        val dLat = aheadM / 111_320.0
        val dLon = aheadM / (111_320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        val out = ArrayList<SpeedCamera>(4)
        var skippedOpposite = 0
        var skippedOffAxis = 0
        val cols = if (hasTrafficDeg) "lat, lon, maxspeed, direction, trafficDeg"
                   else "lat, lon, maxspeed, direction"
        val sql = "SELECT $cols FROM speed_camera " +
            "WHERE maxLat >= ? AND minLat <= ? AND maxLon >= ? AND minLon <= ?"
        runCatching {
            db.rawQuery(sql, arrayOf(
                (lat - dLat).toString(), (lat + dLat).toString(),
                (lon - dLon).toString(), (lon + dLon).toString())).use { c ->
                while (c.moveToNext()) {
                    val cla = c.getDouble(0); val clo = c.getDouble(1)
                    val d = haversineM(lat, lon, cla, clo)
                    if (d > aheadM) continue
                    val bearing = bearingDeg(lat, lon, cla, clo)
                    if (angleDiff(bearing, headingDeg) > AHEAD_CONE_DEG) continue  // behind/aside
                    val rawDir = c.getString(3)
                    val traffic = if (hasTrafficDeg && !c.isNull(4)) c.getDouble(4) else null

                    var confirmed = false
                    if (traffic != null) {
                        // Decisive: the camera's own carriageway direction.
                        if (angleDiff(traffic, headingDeg) > OPPOSING_TOL_DEG) {
                            skippedOpposite++
                            continue
                        }
                        confirmed = true
                    } else {
                        // Fall back to the facing tag, which can only rule out a
                        // camera that watches a road crossing ours.
                        val facing = parseCompass(rawDir)
                        if (facing != null) {
                            val off = angleDiff(facing, headingDeg)
                            if (Math.min(off, 180.0 - off) > AXIS_TOL_DEG) {
                                skippedOffAxis++
                                continue
                            }
                        }
                    }
                    out.add(SpeedCamera(cla, clo,
                        if (c.isNull(2)) null else c.getInt(2), rawDir, d,
                        traffic, confirmed))
                }
            }
        }.onFailure { DLog.e(TAG, "camera query failed", it) }
        // Throttled: this runs on every GPS fix, and an unthrottled line here
        // would bury the log while driving past a camera-dense stretch.
        if (skippedOpposite > 0 || skippedOffAxis > 0) {
            val now = System.currentTimeMillis()
            if (now - lastSkipLogMs > SKIP_LOG_INTERVAL_MS) {
                lastSkipLogMs = now
                DLog.i(TAG, "direction filter: skipped $skippedOpposite " +
                    "opposite-direction and $skippedOffAxis off-axis camera(s)")
            }
        }
        return out.sortedBy { it.distanceM }
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = Math.sin(dLon) * Math.cos(Math.toRadians(lat2))
        val x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2)) -
            Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLon)
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
    }

    /** Smallest absolute difference between two bearings, 0..180. */
    private fun angleDiff(a: Double, b: Float): Double {
        var d = Math.abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }

    companion object {
        private const val TAG = "CameraMonitor"

        /** Half-angle of the "ahead of us" cone, applied to the bearing TO the
         *  camera. Wide enough to keep cameras round a bend in view. */
        const val AHEAD_CONE_DEG = 60.0

        /** How far the monitored traffic direction may differ from our heading
         *  before the camera counts as watching other traffic. Generous enough
         *  for a bend or an interchange between us and the camera, tight enough
         *  to exclude a crossing road (~90 deg) and anything oncoming. A camera
         *  dropped early is reconsidered on every later fix, so a curve only
         *  delays its warning rather than losing it. */
        const val OPPOSING_TOL_DEG = 75.0

        /** How far a camera's facing may differ from our road's AXIS (either
         *  sense) before it is taken to belong to a crossing road. */
        const val AXIS_TOL_DEG = 60.0

        /** Minimum gap between "cameras skipped" log lines. */
        private const val SKIP_LOG_INTERVAL_MS = 30_000L

        private val CARDINALS = mapOf(
            "n" to 0.0, "nne" to 22.5, "ne" to 45.0, "ene" to 67.5,
            "e" to 90.0, "ese" to 112.5, "se" to 135.0, "sse" to 157.5,
            "s" to 180.0, "ssw" to 202.5, "sw" to 225.0, "wsw" to 247.5,
            "w" to 270.0, "wnw" to 292.5, "nw" to 315.0, "nnw" to 337.5,
            "north" to 0.0, "northeast" to 45.0, "east" to 90.0,
            "southeast" to 135.0, "south" to 180.0, "southwest" to 225.0,
            "west" to 270.0, "northwest" to 315.0,
        )

        /** An OSM direction value as a compass bearing, or null for
         *  forward/backward/both/absent/unparseable. Mirrors
         *  add_parking.parse_compass() so device and map agree. */
        fun parseCompass(value: String?): Double? {
            val s = value?.trim()?.lowercase() ?: return null
            if (s.isEmpty()) return null
            CARDINALS[s]?.let { return it }
            return s.toDoubleOrNull()?.let { ((it % 360.0) + 360.0) % 360.0 }
        }

        fun from(db: SQLiteDatabase): CameraMonitor? = runCatching {
            val exists = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='speed_camera'",
                null).use { it.moveToFirst() }
            if (!exists) {
                DLog.i(TAG, "region db has no speed_camera table (pre-v5) — feature off")
                return null
            }
            CameraMonitor(db)
        }.onFailure { DLog.e(TAG, "camera init failed", it) }.getOrNull()
    }
}
