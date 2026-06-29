package com.rfsat.dms.obd

/**
 * Connection lifecycle state for the OBD adapter, surfaced to the UI so the user
 * can see what's happening without it being intrusive.
 */
enum class ObdConnectionState {
    DISABLED,        // OBD support turned off in settings
    NOT_CONFIGURED,  // no adapter has been set up yet
    CONNECTING,      // attempting to reach the remembered adapter
    HANDSHAKING,     // connected; running ELM327 init / capability discovery
    CONNECTED,       // streaming data
    NOT_FOUND,       // remembered adapter not reachable (e.g. wrong car) -> fell back
    ERROR,           // connection or adapter error -> fell back
}

/**
 * A snapshot of the latest OBD readings. Only fields whose PIDs the vehicle
 * supports (and that have produced a fresh reading) are non-null; everything
 * else stays null, which is how downstream features know whether to use OBD or
 * fall back. [tMs] is when this snapshot was last updated.
 */
data class ObdData(
    val speedKmh: Int? = null,
    val rpm: Int? = null,
    val throttlePct: Int? = null,
    val enginePct: Int? = null,
    val coolantC: Int? = null,
    val intakeC: Int? = null,
    val tMs: Long = 0L,
)

/**
 * The set of PIDs a specific connected vehicle reports as supported, discovered
 * via the support bitmasks at connect time. Downstream features query this to
 * decide whether they can rely on a given signal.
 */
data class ObdCapabilitySet(
    val supportedPids: Set<Int> = emptySet(),
) {
    fun supports(p: Pid): Boolean = p.pid in supportedPids

    /** The DBM data PIDs this vehicle actually supports, for the poller. */
    fun supportedDataPids(): List<Pid> =
        Pid.DATA_PIDS.filter { it.pid in supportedPids }

    /** Human-readable summary for logs / the settings screen. */
    fun summary(): String {
        if (supportedPids.isEmpty()) return "none discovered"
        val names = Pid.DATA_PIDS.filter { it.pid in supportedPids }.map { it.label }
        return if (names.isEmpty()) "no known DBM PIDs" else names.joinToString(", ")
    }
}
