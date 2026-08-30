package com.rfsat.dms.nav

import android.content.Context

/** How the map is oriented. FREE = user rotates manually; the button cycles
 *  NORTH_UP -> HEADING_UP -> FREE and back. */
enum class MapOrientation(val label: String) {
    NORTH_UP("North up"), HEADING_UP("Heading up"), FREE("Free rotate")
}

/** Base map imagery. Raster sources need no API key. */
enum class MapLayer(val label: String) {
    STREET("Street"), SATELLITE("Satellite"), TERRAIN("Terrain")
}

/** Icon drawn at the user's own location. */
enum class OwnLocationIcon(val label: String) {
    BLUE_DOT("Blue dot"), CAR("Car"), PEDESTRIAN("Pedestrian"), ARROW("Arrow")
}

/**
 * All persistent navigation settings, backed by SharedPreferences so they
 * survive app restarts. This is the single source of truth for nav configuration
 * that used to live as transient state inside NavScreen. The mode controls now
 * live in the Settings screen; NavScreen reads/writes through here.
 */
class NavSettings(ctx: Context) {
    private val p = ctx.getSharedPreferences("dbm_nav", Context.MODE_PRIVATE)

    private fun <T> get(key: String, default: T, parse: (String) -> T): T =
        p.getString(key, null)?.let { runCatching { parse(it) }.getOrDefault(default) }
            ?: default

    var base: BaseView
        get() = get("base", BaseView.MAP_2D_TOPDOWN) { BaseView.valueOf(it) }
        set(v) { p.edit().putString("base", v.name).apply() }

    var overlays: Set<Overlay>
        get() = p.getStringSet("overlays",
            setOf(Overlay.ARROW_MANEUVER.name, Overlay.VOICE.name))!!
            .mapNotNull { runCatching { Overlay.valueOf(it) }.getOrNull() }.toSet()
        set(v) { p.edit().putStringSet("overlays", v.map { it.name }.toSet()).apply() }

    var mapLayer: MapLayer
        get() = get("layer", MapLayer.STREET) { MapLayer.valueOf(it) }
        set(v) { p.edit().putString("layer", v.name).apply() }

    var orientation: MapOrientation
        get() = get("orient", MapOrientation.NORTH_UP) { MapOrientation.valueOf(it) }
        set(v) { p.edit().putString("orient", v.name).apply() }

    var ownIcon: OwnLocationIcon
        get() = get("ownicon", OwnLocationIcon.BLUE_DOT) { OwnLocationIcon.valueOf(it) }
        set(v) { p.edit().putString("ownicon", v.name).apply() }

    var windshieldMirror: Boolean
        get() = p.getBoolean("mirror", false)
        set(v) { p.edit().putBoolean("mirror", v).apply() }

    var theme: NavTheme
        get() = get("theme", NavTheme.AUTO) { NavTheme.valueOf(it) }
        set(v) { p.edit().putString("theme", v.name).apply() }

    var showMapData: Boolean      // speed limits / parking / cameras on the map
        get() = p.getBoolean("mapdata", true)
        set(v) { p.edit().putBoolean("mapdata", v).apply() }

    var googleApiKey: String?
        get() = p.getString("google_key", null)
        set(v) { p.edit().putString("google_key", v).apply() }

    /** Snapshot as a NavState for the renderer. */
    fun toNavState(): NavState = NavState(
        base = base, overlays = overlays,
        transform = DisplayTransform(windshieldMirror = windshieldMirror, theme = theme))
}
