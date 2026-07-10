package com.rfsat.dms.obd

import com.rfsat.dms.util.DLog

/**
 * Discovers which PIDs a connected vehicle supports, by querying the OBD-II
 * support-bitmask PIDs (0x00 -> 0x20 -> 0x40) and walking the chain while each
 * block signals that the next one is available.
 *
 * Per SAE J1979, bit 0 of each support block (i.e. the LAST pid in the range,
 * 0x20 / 0x40 / 0x60) indicates whether the NEXT block is supported. We use that
 * to decide whether to keep walking, so we never query blocks the adapter would
 * just reject.
 */
object ObdCapabilities {

    private val SUPPORT_CHAIN = listOf(
        Pid.SUPPORT_01_20 to 0x20,   // querying 0x00; if pid 0x20 present, go on
        Pid.SUPPORT_21_40 to 0x40,   // querying 0x20; if pid 0x40 present, go on
        Pid.SUPPORT_41_60 to 0x60,   // querying 0x40; if pid 0x60 present, go on
    )

    /**
     * Run the discovery walk. Returns the union of supported PID numbers across
     * all reachable blocks. On a clone that mis-reports, returns whatever it
     * could parse (possibly empty — the manager then assumes at least SPEED).
     */
    suspend fun discover(transport: ObdBluetoothTransport): ObdCapabilitySet {
        val supported = mutableSetOf<Int>()
        for ((supportPid, nextBlockPid) in SUPPORT_CHAIN) {
            val raw = transport.send(supportPid.command, timeoutMs = 800)
            if (raw == null) {
                DLog.w(TAG, "no response to support PID ${supportPid.command}")
                break
            }
            val block = ObdParser.parseSupportMask(raw, supportPid)
            if (block == null) {
                DLog.w(TAG, "could not parse support mask for ${supportPid.command}")
                break
            }
            supported.addAll(block)
            // Continue only if this block says the next block's base PID exists.
            if (nextBlockPid !in block) break
        }
        DLog.i(TAG, "discovery found ${supported.size} supported PIDs")
        return ObdCapabilitySet(supported)
    }

    private const val TAG = "ObdCaps"
}
