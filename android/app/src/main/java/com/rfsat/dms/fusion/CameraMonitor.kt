package com.rfsat.dms.fusion

import android.database.sqlite.SQLiteDatabase
import com.rfsat.dms.util.DLog

/** A speed camera near the driver. */
data class SpeedCamera(
    val lat: Double,
    val lon: Double,
    val maxspeed: Int?,        // enforced limit, if known
    val direction: String?,    // forward | backward | both | compass bearing
    val distanceM: Double,     // distance from the query point
)

/**
 * Reads the speed_camera table written by add_parking.py (schema v5+). Shares
 * the region database handle already opened by OsmMap.
 *
 * LEGAL NOTE: dynamic speed-camera warnings are prohibited in some countries.
 * This class only READS data; whether a warning is surfaced is gated by a user
 * opt-in in settings ("hazard_speed_cameras", off by default), per the OSM
 * guidance that such warnings must be an explicit choice.
 */
class CameraMonitor private constructor(private val db: SQLiteDatabase) {

    /**
     * Cameras within [aheadM] metres that are roughly ahead of the driver along
     * [headingDeg]. A camera counts as "ahead" if the bearing to it is within
     * +/-60 deg of travel — filters out cameras behind or on the opposite
     * carriageway without needing precise lane data.
     */
    fun camerasAhead(
        lat: Double, lon: Double, headingDeg: Float, aheadM: Double = 600.0,
    ): List<SpeedCamera> {
        val dLat = aheadM / 111_320.0
        val dLon = aheadM / (111_320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        val out = ArrayList<SpeedCamera>(4)
        val sql = "SELECT lat, lon, maxspeed, direction FROM speed_camera " +
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
                    if (angleDiff(bearing, headingDeg) > 60.0) continue   // not ahead
                    out.add(SpeedCamera(cla, clo,
                        if (c.isNull(2)) null else c.getInt(2), c.getString(3), d))
                }
            }
        }.onFailure { DLog.e(TAG, "camera query failed", it) }
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
