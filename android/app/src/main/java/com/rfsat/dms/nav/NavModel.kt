package com.rfsat.dms.nav

/**
 * Navigation feature — core state model. Self-contained: nothing here depends on
 * the rest of the app. Presentation is a BASE VIEW plus any combination of
 * OVERLAYS, so modes are individually selectable AND combinable (see
 * docs/NAVIGATION-DESIGN.md).
 */

/** Full-screen base views — exactly one active (mutually exclusive viewports). */
enum class BaseView(val label: String, val needsMapSdk: Boolean, val needsCamera: Boolean) {
    MAP_2D_TOPDOWN("2D map", true, false),
    MAP_2D_PERSPECTIVE("2D perspective", true, false),
    MAP_3D("3D map", true, false),
    CAMERA_AR("Camera (AR)", false, true),
    ARROW_ONLY("Arrow / text", false, false)   // no map SDK needed — always works
}

/** Stackable overlays — any subset, drawn on top of the base view. */
enum class Overlay(val label: String, val visual: Boolean) {
    ARROW_MANEUVER("Turn arrow", true),
    SPEED_LIMIT("Speed & limit", true),     // reuses the app's speed-limit map data
    LANE_GUIDANCE("Lane guidance", true),
    JUNCTION_VIEW("Junction view", true),
    RIBBON_HUD("Ribbon HUD", true),
    VOICE("Voice prompts", false),          // non-visual (TTS)
    HAPTIC("Haptic cues", false)            // non-visual (vibration)
}

enum class NavTheme { DAY, NIGHT, AUTO }

/**
 * Display transforms applied on top of whatever is shown — orthogonal to the
 * base/overlay choice. WINDSHIELD mirrors horizontally + boosts contrast so the
 * screen can be reflected off the windshield with the phone flat on the dash.
 */
data class DisplayTransform(
    val windshieldMirror: Boolean = false,
    val theme: NavTheme = NavTheme.AUTO
)

/** The complete, serializable navigation UI state. */
data class NavState(
    val base: BaseView = BaseView.ARROW_ONLY,
    val overlays: Set<Overlay> = setOf(Overlay.ARROW_MANEUVER, Overlay.VOICE),
    val transform: DisplayTransform = DisplayTransform()
) {
    fun withBase(b: BaseView) = copy(base = b)
    fun toggleOverlay(o: Overlay) =
        copy(overlays = if (o in overlays) overlays - o else overlays + o)
    fun withMirror(on: Boolean) = copy(transform = transform.copy(windshieldMirror = on))
    fun withTheme(t: NavTheme) = copy(transform = transform.copy(theme = t))
}

// ---- geometry / routing primitives (provider-agnostic) ----------------------

data class GeoPoint(val lat: Double, val lon: Double)

enum class Maneuver { STRAIGHT, TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
    SHARP_LEFT, SHARP_RIGHT, UTURN, ROUNDABOUT, MERGE, EXIT, ARRIVE }

/** One instruction in a route: what to do, where, and how far ahead. */
data class RouteStep(
    val at: GeoPoint,
    val maneuver: Maneuver,
    val instruction: String,      // human text, e.g. "Turn left onto Main St"
    val distanceMeters: Double,   // distance from previous step to this one
    val laneHint: String? = null  // e.g. "||<|" style, provider-dependent
)

data class Route(
    val points: List<GeoPoint>,   // full polyline
    val steps: List<RouteStep>,
    val totalMeters: Double,
    val totalSeconds: Double
)

/** Live guidance derived from current position + route (what renderers draw). */
data class Guidance(
    val nextStep: RouteStep?,
    val distanceToNext: Double,   // metres to the next maneuver
    val currentSpeedMps: Float?,
    val speedLimitKmh: Int?,      // filled from the app's speed-limit maps if available
    val offRoute: Boolean = false
)
