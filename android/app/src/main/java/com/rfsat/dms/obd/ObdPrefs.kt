package com.rfsat.dms.obd

import android.content.Context

/**
 * Persisted OBD settings, using the same "dbm" SharedPreferences store the rest
 * of the app uses. Holds the remembered adapter MAC (so we auto-connect to the
 * SAME adapter every drive after a one-time setup) and the enabled flag.
 */
class ObdPrefs(context: Context) {
    private val sp = context.getSharedPreferences("dbm", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(v) { sp.edit().putBoolean(KEY_ENABLED, v).apply() }

    /** MAC of the validated adapter, or null if none set up yet. */
    var adapterMac: String?
        get() = sp.getString(KEY_MAC, null)
        set(v) { sp.edit().putString(KEY_MAC, v).apply() }

    /** Friendly name of the remembered adapter, for the settings screen. */
    var adapterName: String?
        get() = sp.getString(KEY_NAME, null)
        set(v) { sp.edit().putString(KEY_NAME, v).apply() }

    fun forget() {
        sp.edit().remove(KEY_MAC).remove(KEY_NAME).apply()
    }

    companion object {
        private const val KEY_ENABLED = "obd_enabled"
        private const val KEY_MAC = "obd_adapter_mac"
        private const val KEY_NAME = "obd_adapter_name"
    }
}
