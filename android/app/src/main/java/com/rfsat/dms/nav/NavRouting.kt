package com.rfsat.dms.nav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Where the routing flow currently is, for the UI to reflect. */
enum class RoutingPhase { IDLE, SEARCHING, ROUTING, NAVIGATING, ERROR }

data class RoutingState(
    val phase: RoutingPhase = RoutingPhase.IDLE,
    val start: GeoPoint? = null,
    val startLabel: String = "Current location",
    val destination: GeoPoint? = null,
    val destinationLabel: String = "",
    // Intermediate waypoints (in order) the route must pass through, between the
    // start and the destination.
    val waypoints: List<Pair<String, GeoPoint>> = emptyList(),
    val searchResults: List<Pair<String, GeoPoint>> = emptyList(),
    // What a picked search result becomes: the destination, or a new waypoint.
    val addingWaypoint: Boolean = false,
    val route: Route? = null,
    val guidance: Guidance? = null,
    val error: String? = null
)

/**
 * Owns the routing/navigation flow, independent of any UI framework. The screen
 * observes `state` and calls these methods. Provider is injected, so online
 * (OSM) today and offline later are interchangeable.
 */
class NavRouter(@Volatile var provider: NavProvider) {

    private val _state = MutableStateFlow(RoutingState())
    val state: StateFlow<RoutingState> = _state

    private var engine: RouteEngine? = null

    /** Set the start point (null = use live location supplied at navigate time). */
    fun setStart(point: GeoPoint?, label: String) {
        _state.value = _state.value.copy(start = point, startLabel = label)
    }

    suspend fun search(query: String) {
        if (query.isBlank()) return
        _state.value = _state.value.copy(phase = RoutingPhase.SEARCHING, error = null)
        val results = withContext(Dispatchers.IO) {
            runCatching { provider.search(query) }.getOrDefault(emptyList()) }
        _state.value = _state.value.copy(
            phase = RoutingPhase.IDLE, searchResults = results)
    }

    fun chooseDestination(label: String, point: GeoPoint) {
        val s = _state.value
        if (s.addingWaypoint) {
            // add as an intermediate waypoint instead of the destination
            _state.value = s.copy(
                waypoints = s.waypoints + (label to point),
                searchResults = emptyList(), addingWaypoint = false)
        } else {
            _state.value = s.copy(
                destination = point, destinationLabel = label, searchResults = emptyList())
        }
    }

    /** Next picked search result will be added as a waypoint, not the destination. */
    fun beginAddWaypoint() {
        _state.value = _state.value.copy(addingWaypoint = true)
    }

    fun removeWaypoint(index: Int) {
        val s = _state.value
        if (index in s.waypoints.indices)
            _state.value = s.copy(waypoints = s.waypoints.toMutableList()
                .also { it.removeAt(index) })
    }

    /** Calculate the route from start through waypoints to destination. */
    suspend fun calculateRoute(livePosition: GeoPoint?) {
        val s = _state.value
        val from = s.start ?: livePosition
        val to = s.destination
        if (from == null || to == null) {
            _state.value = s.copy(phase = RoutingPhase.ERROR,
                error = "Need both a start and a destination.")
            return
        }
        _state.value = s.copy(phase = RoutingPhase.ROUTING, error = null)
        // Ordered points: start, each waypoint, destination.
        val pts = buildList {
            add(from); addAll(s.waypoints.map { it.second }); add(to)
        }
        val route = withContext(Dispatchers.IO) {
            runCatching { provider.routeVia(pts) }.getOrNull() }
        if (route == null) {
            _state.value = _state.value.copy(phase = RoutingPhase.ERROR,
                error = "Could not calculate a route (service unreachable?).")
            return
        }
        engine = RouteEngine(route)
        _state.value = _state.value.copy(
            phase = RoutingPhase.NAVIGATING, route = route,
            guidance = engine!!.update(from, null, null))
    }

    /** Feed a live position update during navigation to advance guidance. */
    fun onPosition(pos: GeoPoint, speedMps: Float?, speedLimitKmh: Int?) {
        val e = engine ?: return
        if (_state.value.phase != RoutingPhase.NAVIGATING) return
        _state.value = _state.value.copy(
            guidance = e.update(pos, speedMps, speedLimitKmh))
    }

    fun stop() {
        engine = null
        _state.value = RoutingState()
    }
}
