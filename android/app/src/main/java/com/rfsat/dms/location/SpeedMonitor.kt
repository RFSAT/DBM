package com.rfsat.dms.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.rfsat.dms.util.DLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Vehicle speed from GNSS (km/h), with health tracking. GNSS is considered
 * healthy only if the provider reports availability AND a fix arrived within
 * STALE_MS — otherwise the system falls back to visual speed estimation.
 */
class SpeedMonitor(context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    private val _speedKmh = MutableStateFlow(0)
    val speedKmh: StateFlow<Int> = _speedKmh

    /** Latest GNSS position (lat, lon) in degrees, or null until the first fix.
     *  Exposed for the planned map-based speed-limit cross-check, and logged as
     *  a parseable trace when GPS-trace logging is enabled. */
    private val _position = MutableStateFlow<Pair<Double, Double>?>(null)
    val position: StateFlow<Pair<Double, Double>?> = _position

    /** When true, each GNSS fix is written to the diagnostic log as a
     *  machine-parseable line. Off by default (it records a precise location
     *  trace — personal data — so it is opt-in for development use). */
    @Volatile var logTrace = false

    private val _available = MutableStateFlow(false)
    private var lastFixMs = 0L

    /** True if GNSS speed is fresh and usable. */
    val healthy: Boolean
        get() = _available.value && System.currentTimeMillis() - lastFixMs < STALE_MS

    private val cb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.lastLocation?.let {
                if (it.hasSpeed()) {
                    _speedKmh.value = (it.speed * 3.6f).toInt()
                    lastFixMs = System.currentTimeMillis()
                }
                // Position is present on every fix (independent of speed).
                _position.value = it.latitude to it.longitude
                if (logTrace) {
                    val spd = if (it.hasSpeed()) (it.speed * 3.6f).toInt() else -1
                    DLog.i(TAG, "GPS lat=%.6f lon=%.6f spd=%d acc=%.0f".format(
                        it.latitude, it.longitude, spd,
                        if (it.hasAccuracy()) it.accuracy else -1f))
                }
            }
        }
        override fun onLocationAvailability(a: LocationAvailability) {
            _available.value = a.isLocationAvailable
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        client.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build(),
            cb, Looper.getMainLooper())
    }

    fun stop() = client.removeLocationUpdates(cb)

    companion object {
        const val STALE_MS = 4000L
        private const val TAG = "SpeedMonitor"
    }
}
