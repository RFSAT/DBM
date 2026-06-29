package com.rfsat.dms.obd

/**
 * OBD-II Mode 01 (current data) PID catalogue for the parameters DBM uses, plus
 * the support-discovery PIDs.
 *
 * Each [Pid] knows its hex command, how many data bytes it returns, and how to
 * decode those bytes into a physical value. Formulas are the SAE J1979 standard
 * decodings (public, vehicle-independent).
 *
 * STAGING: only [SPEED] is strictly required for the first integration (it
 * becomes the primary speed source). RPM/THROTTLE/LOAD/COOLANT are decoded too
 * but are gated on capability discovery (see [ObdCapabilities]) so the app only
 * polls and uses what a given vehicle actually supports.
 */
enum class Pid(
    val mode: Int,
    val pid: Int,
    val dataBytes: Int,
    val label: String,
    val unit: String,
) {
    // --- Support-discovery PIDs (bitmasks of which other PIDs exist) ----------
    SUPPORT_01_20(0x01, 0x00, 4, "supported PIDs 01-20", ""),
    SUPPORT_21_40(0x01, 0x20, 4, "supported PIDs 21-40", ""),
    SUPPORT_41_60(0x01, 0x40, 4, "supported PIDs 41-60", ""),

    // --- Universal / common data PIDs ----------------------------------------
    /** Vehicle speed, 1 byte, 0..255 km/h. The primary DBM signal. */
    SPEED(0x01, 0x0D, 1, "vehicle speed", "km/h"),
    /** Engine RPM, 2 bytes: ((A*256)+B)/4. */
    RPM(0x01, 0x0C, 2, "engine RPM", "rpm"),
    /** Throttle position, 1 byte: A*100/255 percent. */
    THROTTLE(0x01, 0x11, 1, "throttle position", "%"),
    /** Calculated engine load, 1 byte: A*100/255 percent. */
    ENGINE_LOAD(0x01, 0x04, 1, "engine load", "%"),
    /** Engine coolant temperature, 1 byte: A-40 deg C. */
    COOLANT_TEMP(0x01, 0x05, 1, "coolant temp", "\u00B0C"),
    /** Intake air temperature, 1 byte: A-40 deg C. */
    INTAKE_TEMP(0x01, 0x0F, 1, "intake air temp", "\u00B0C");

    /** The ELM327 command string for this PID, e.g. "010D" for speed. */
    val command: String
        get() = "%02X%02X".format(mode, pid)

    /**
     * Decode the data bytes (already parsed from the hex response, excluding the
     * "41 XX" echo header) into a physical value. Returns null if there aren't
     * enough bytes. Caller is responsible for plausibility checks beyond range.
     */
    fun decode(data: IntArray): Double? {
        if (data.size < dataBytes) return null
        val a = data[0]
        val b = if (data.size > 1) data[1] else 0
        return when (this) {
            SPEED -> a.toDouble()
            RPM -> ((a * 256) + b) / 4.0
            THROTTLE, ENGINE_LOAD -> a * 100.0 / 255.0
            COOLANT_TEMP, INTAKE_TEMP -> (a - 40).toDouble()
            // Support PIDs are bitmasks, decoded separately (see ObdCapabilities).
            SUPPORT_01_20, SUPPORT_21_40, SUPPORT_41_60 -> null
        }
    }

    companion object {
        /** PIDs that carry vehicle data (not the support bitmasks). */
        val DATA_PIDS: List<Pid> by lazy {
            entries.filter { it !in listOf(SUPPORT_01_20, SUPPORT_21_40, SUPPORT_41_60) }
        }

        /** Find a data PID by its Mode-01 pid number, or null. */
        fun byPidNumber(n: Int): Pid? = DATA_PIDS.firstOrNull { it.pid == n }
    }
}
