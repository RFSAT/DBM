package com.rfsat.dms.nav

/**
 * Turns a route + the current position into live Guidance (which renderers,
 * voice and haptic all consume). Provider-agnostic and UI-agnostic — pure logic,
 * easy to unit-test. In v1 it advances through the route's steps by proximity.
 */
class RouteEngine(private val route: Route) {
    private var stepIndex = 0

    /** Update with the latest position; returns the current guidance. */
    fun update(pos: GeoPoint, speedMps: Float?, speedLimitKmh: Int?): Guidance {
        if (stepIndex >= route.steps.size) {
            return Guidance(null, 0.0, speedMps, speedLimitKmh)
        }
        val step = route.steps[stepIndex]
        val dist = haversine(pos, step.at)
        // Advance to the next step once we're within ~25 m of this maneuver.
        if (dist < 25.0 && stepIndex < route.steps.size - 1) {
            stepIndex++
        }
        // Off-route heuristic: far from the whole polyline. Cheap check vs. the
        // nearest route point (good enough for the skeleton; real map-matching
        // comes with the online provider).
        val nearest = route.points.minOf { haversine(pos, it) }
        return Guidance(
            nextStep = step,
            distanceToNext = dist,
            currentSpeedMps = speedMps,
            speedLimitKmh = speedLimitKmh,
            offRoute = nearest > 80.0
        )
    }

    val steps: List<RouteStep> get() = route.steps
    val currentStepIndex: Int get() = stepIndex
}
