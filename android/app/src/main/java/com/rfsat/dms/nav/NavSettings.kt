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

    init {
        // One-time migration: earlier builds defaulted the base view to
        // ARROW_ONLY. The default is now the 2D street map. On the first run of
        // this version, if ARROW_ONLY is the stored base, clear it so the new
        // 2D-street default takes effect. The marker ensures this runs only once,
        // so any later deliberate choice of ARROW_ONLY in Settings is kept.
        if (!p.getBoolean("migrated_v2", false)) {
            if (p.getString("base", null) == "ARROW_ONLY")
                p.edit().remove("base").apply()
            p.edit().putBoolean("migrated_v2", true).apply()
        }
    }

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

    /**
     * Keep the map locked on the vehicle, so the own-location icon stays put and
     * the map scrolls under it. This is the PREFERENCE, not the live state:
     * dragging the map suspends following for that drive (otherwise the camera
     * would snap back and panning would be impossible), and the lock button or a
     * recenter re-arms it. Default on — a navigation map that lets the vehicle
     * walk off the screen is not much use.
     */
    var followLock: Boolean
        get() = p.getBoolean("follow_lock", true)
        set(v) { p.edit().putBoolean("follow_lock", v).apply() }

    /**
     * Shift the vehicle below the centre of the map while following, which trades
     * the view behind for more road ahead. Off by default: the icon sits exactly
     * in the middle. Most useful with the 2½D and 3D views, where a centred
     * vehicle spends the top half of the screen on the horizon.
     */
    var lookAhead: Boolean
        get() = p.getBoolean("look_ahead", false)
        set(v) { p.edit().putBoolean("look_ahead", v).apply() }

    var theme: NavTheme
        get() = get("theme", NavTheme.AUTO) { NavTheme.valueOf(it) }
        set(v) { p.edit().putString("theme", v.name).apply() }

    /** POI types the LOADED map data supports (base types + extras present in the
     *  active region .db, as cached by the service). Drives Settings greying. */
    val availablePois: Set<PoiType>
        get() {
            val extra = p.getStringSet("available_extra_pois", null) ?: emptySet()
            return PoiType.availableFrom(extra)
        }

    /** Which POI / map-data categories to draw on the navigation map. Filtered to
     *  what's actually available so a stored choice for missing data is ignored. */
    var enabledPois: Set<PoiType>
        get() {
            val avail = availablePois
            val saved = p.getStringSet("pois", null)
                ?: return PoiType.defaults.filter { it in avail }.toSet()
            return saved.mapNotNull { runCatching { PoiType.valueOf(it) }.getOrNull() }
                .filter { it in avail }.toSet()
        }
        set(v) { p.edit().putStringSet("pois",
            v.map { it.name }.toSet()).apply() }

    /** Convenience: is ANY map data enabled (used to skip the query entirely). */
    val anyPoiEnabled: Boolean get() = enabledPois.isNotEmpty()

    var googleApiKey: String?
        get() = p.getString("google_key", null)
        set(v) { p.edit().putString("google_key", v).apply() }

    /** Snapshot as a NavState for the renderer. */
    fun toNavState(): NavState = NavState(
        base = base, overlays = overlays,
        transform = DisplayTransform(windshieldMirror = windshieldMirror, theme = theme))
}
