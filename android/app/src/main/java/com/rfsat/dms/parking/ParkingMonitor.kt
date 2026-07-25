package com.rfsat.dms.parking

import android.database.sqlite.SQLiteDatabase
import com.rfsat.dms.util.DLog
import java.util.Calendar

/** A car park near the driver. */
data class ParkingLot(
    val name: String?,
    val kind: String?,          // surface | multi-storey | underground | ...
    val access: String?,        // yes | private | customers | permit | no
    val fee: String?,
    val capacity: Int?,
    val hours: String?,
    val lat: Double,
    val lon: Double,
    val distanceM: Double,
) {
    /** True when anyone may use it (not private/permit/customer-only). */
    val isPublic: Boolean
        get() = access == null || access == "yes" || access == "public" ||
                access == "permissive"

    fun label(): String = name ?: when (kind) {
        "multi-storey" -> "Multi-storey car park"
        "underground"  -> "Underground car park"
        "street_side"  -> "Street-side parking"
        else           -> "Car park"
    }
}

/** The curbside rule in force at a location, already resolved for "now". */
data class CurbRule(
    val side: String,           // left | right | both
    val restriction: String?,   // no_parking | no_stopping | ... (resolved)
    val access: String?,        // yes | permit | private | ... (resolved)
    val permit: String?,        // residents | ...
    val zone: String?,
    val maxstay: String?,
    val unresolved: Boolean,    // a condition we deliberately did not evaluate
    val rawConditional: String?,// shown to the driver when unresolved
) {
    /** True if leaving a car here is disallowed right now. */
    val blocksParking: Boolean
        get() = restriction in BLOCKING ||
                access == "private" || access == "no" || access == "permit"

    fun describe(): String {
        if (unresolved && rawConditional != null) return "Restricted: $rawConditional"
        val what = when (restriction) {
            "no_stopping" -> "No stopping"
            "no_parking"  -> "No parking"
            "no_standing" -> "No standing"
            "loading_only"-> "Loading only"
            else -> when (access) {
                "permit"  -> if (permit != null) "Permit holders only ($permit)" else "Permit holders only"
                "private" -> "Private parking"
                "no"      -> "No parking"
                "customers" -> "Customers only"
                else -> return "Parking allowed"
            }
        }
        val z = zone?.let { " · zone $it" } ?: ""
        val m = maxstay?.let { " · max stay $it" } ?: ""
        return "$what$z$m"
    }

    companion object {
        val BLOCKING = setOf("no_parking", "no_stopping", "no_standing")
    }
}

/**
 * Reads the parking tables that add_parking.py writes into the region database
 * (schema v4+). Shares the SQLiteDatabase handle already opened by OsmMap, so
 * there is no second file handle and no second search for the .db.
 *
 * IMPORTANT SEMANTICS: an empty result is "we have no data here", NOT "parking
 * is allowed here". Curbside coverage in OSM is sparse and regional, so the UI
 * must distinguish those two cases — see [hasCurbData].
 */
class ParkingMonitor private constructor(
    private val db: SQLiteDatabase,
    /** True when this region's db actually contains curbside rows at all. */
    val hasCurbData: Boolean,
) {

    /** Curbside rules near a point, resolved against the current time. */
    fun rulesAt(lat: Double, lon: Double, marginDeg: Double = 0.0004): List<CurbRule> {
        val cal = Calendar.getInstance()
        // Calendar: Sunday=1..Saturday=7 -> ISO Monday=1..Sunday=7
        val dow = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val out = ArrayList<CurbRule>(4)
        val sql = """
            SELECT side, restriction, restr_cond, access, access_cond,
                   permit, zone, maxstay, maxstay_cond
            FROM parking_curb
            WHERE maxLat >= ? AND minLat <= ? AND maxLon >= ? AND minLon <= ?
            LIMIT 24
        """.trimIndent()
        runCatching {
            db.rawQuery(sql, arrayOf(
                (lat - marginDeg).toString(), (lat + marginDeg).toString(),
                (lon - marginDeg).toString(), (lon + marginDeg).toString())).use { c ->
                while (c.moveToNext()) {
                    val restrCond = c.getString(2)
                    val accessCond = c.getString(4)
                    val restriction = ParkingCondition.resolve(c.getString(1), restrCond, dow, minutes)
                    val access = ParkingCondition.resolve(c.getString(3), accessCond, dow, minutes)
                    val unresolved = restriction == ParkingCondition.UNPARSED ||
                                     access == ParkingCondition.UNPARSED
                    out.add(CurbRule(
                        side = c.getString(0) ?: "both",
                        restriction = restriction.takeUnless { it == ParkingCondition.UNPARSED },
                        access = access.takeUnless { it == ParkingCondition.UNPARSED },
                        permit = c.getString(5),
                        zone = c.getString(6),
                        maxstay = ParkingCondition.resolve(c.getString(7), c.getString(8), dow, minutes)
                            ?.takeUnless { it == ParkingCondition.UNPARSED },
                        unresolved = unresolved,
                        rawConditional = if (unresolved) (restrCond ?: accessCond) else null,
                    ))
                }
            }
        }.onFailure { DLog.e(TAG, "curb query failed", it) }
        return out
    }

    /**
     * Car parks near a point, nearest first. [publicOnly] drops private,
     * permit-only and customer-only facilities, which are technically nearby but
     * useless to a driver looking for somewhere to leave the car.
     */
    fun lotsNear(
        lat: Double, lon: Double,
        radiusM: Double = 800.0,
        publicOnly: Boolean = true,
        limit: Int = 10,
    ): List<ParkingLot> {
        // Degrees of latitude per metre; longitude shrinks with cos(lat).
        val dLat = radiusM / 111_320.0
        val dLon = radiusM / (111_320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        val out = ArrayList<ParkingLot>(16)
        val sql = """
            SELECT name, kind, access, fee, capacity, hours, lat, lon
            FROM parking_lot
            WHERE maxLat >= ? AND minLat <= ? AND maxLon >= ? AND minLon <= ?
        """.trimIndent()
        runCatching {
            db.rawQuery(sql, arrayOf(
                (lat - dLat).toString(), (lat + dLat).toString(),
                (lon - dLon).toString(), (lon + dLon).toString())).use { c ->
                while (c.moveToNext()) {
                    val la = c.getDouble(6); val lo = c.getDouble(7)
                    val d = haversineM(lat, lon, la, lo)
                    if (d > radiusM) continue
                    val lot = ParkingLot(
                        name = c.getString(0), kind = c.getString(1),
                        access = c.getString(2), fee = c.getString(3),
                        capacity = if (c.isNull(4)) null else c.getInt(4),
                        hours = c.getString(5), lat = la, lon = lo, distanceM = d)
                    if (publicOnly && !lot.isPublic) continue
                    out.add(lot)
                }
            }
        }.onFailure { DLog.e(TAG, "lot query failed", it) }
        return out.sortedBy { it.distanceM }.take(limit)
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

    companion object {
        private const val TAG = "ParkingMonitor"

        /**
         * Attach to an already-open region database. Returns null when the db
         * predates schema v4 and has no parking tables, so older downloaded
         * regions keep working with the feature simply unavailable.
         */
        fun from(db: SQLiteDatabase): ParkingMonitor? = runCatching {
            fun tableExists(n: String) = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(n)
            ).use { it.moveToFirst() }
            if (!tableExists("parking_lot")) {
                DLog.i(TAG, "region db has no parking tables (pre-v4) — feature off")
                return null
            }
            val curbRows = if (!tableExists("parking_curb")) 0 else
                db.rawQuery("SELECT COUNT(*) FROM parking_curb", null).use {
                    if (it.moveToFirst()) it.getInt(0) else 0
                }
            DLog.i(TAG, "parking data available; curbside rows=$curbRows")
            ParkingMonitor(db, curbRows > 0)
        }.onFailure { DLog.e(TAG, "parking init failed", it) }.getOrNull()
    }
}
