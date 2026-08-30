package com.rfsat.dms.nav

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The ONLINE/OFFLINE abstraction. The UI talks only to this interface, so
 * swapping an online provider for an offline one (later) changes no UI code.
 *
 * Implementations planned:
 *   - OnlineOsmProvider   MapLibre vector tiles + OSRM/Valhalla routing (online)
 *   - OfflineProvider     the app's downloaded maps + on-device routing (later)
 *
 * For the v1 skeleton, StubProvider returns a simple synthesized route so the
 * renderers, guidance loop, voice and windshield mode can all be exercised with
 * no network and no map SDK.
 */
interface NavProvider {
    val name: String
    val isOnline: Boolean
    /** Compute a route between two points. May return null if unavailable. */
    suspend fun route(from: GeoPoint, to: GeoPoint): Route?
    /** Compute a route through an ordered list of points (start, vias…, end). */
    suspend fun routeVia(points: List<GeoPoint>): Route?
    /** Free-text place search -> candidate destinations. */
    suspend fun search(query: String): List<Pair<String, GeoPoint>>
    /** Whether this provider can currently serve (network up for online, etc.). */
    fun available(): Boolean
}

/** Great-circle distance in metres. */
fun haversine(a: GeoPoint, b: GeoPoint): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}

/** Initial bearing a->b in degrees (0=N, 90=E). */
fun bearing(a: GeoPoint, b: GeoPoint): Double {
    val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val y = sin(dLon) * cos(la2)
    val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

/**
 * v1 skeleton provider: synthesizes a plausible multi-step route from `from` to
 * `to` (a few straight legs with turns) so the whole nav UI is exercisable with
 * no backend. Swapped out for OnlineOsmProvider in v2. NOT for production routing.
 */
class StubProvider : NavProvider {
    override val name = "Demo (offline stub)"
    override val isOnline = false
    override fun available() = true

    override suspend fun route(from: GeoPoint, to: GeoPoint): Route =
        demoRoute(from, to)

    override suspend fun routeVia(points: List<GeoPoint>): Route? {
        if (points.size < 2) return null
        return demoRoute(points.first(), points.last())
    }

    override suspend fun search(query: String): List<Pair<String, GeoPoint>> =
        emptyList()   // no geocoding in the stub

    companion object {
        /** Synchronous demo route (no network) shared by the UI skeleton. */
        fun demoRoute(from: GeoPoint, to: GeoPoint): Route {
            val midA = GeoPoint(from.lat + (to.lat - from.lat) * 0.4,
                                from.lon + (to.lon - from.lon) * 0.2)
            val midB = GeoPoint(from.lat + (to.lat - from.lat) * 0.7,
                                from.lon + (to.lon - from.lon) * 0.8)
            val pts = listOf(from, midA, midB, to)
            val steps = listOf(
                RouteStep(midA, Maneuver.TURN_RIGHT, "Turn right", haversine(from, midA)),
                RouteStep(midB, Maneuver.TURN_LEFT, "Turn left", haversine(midA, midB)),
                RouteStep(to, Maneuver.ARRIVE, "Arrive at destination", haversine(midB, to))
            )
            val total = steps.sumOf { it.distanceMeters }
            return Route(pts, steps, total, total / 13.9)
        }
    }
}
