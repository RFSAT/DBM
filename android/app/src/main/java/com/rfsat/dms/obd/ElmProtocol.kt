package com.rfsat.dms.obd

import com.rfsat.dms.util.DLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The ELM327 conversation, shared by all transports. Subclasses provide only the
 * raw byte pipe ([writeBytes] + [readAvailable]); this class handles the init
 * handshake and reading a response up to the '>' prompt. That keeps the ELM
 * protocol identical across Bluetooth Classic and BLE.
 */
abstract class ElmProtocol : ObdTransport {

    /** Write raw bytes to the adapter. Return false on failure. */
    protected abstract suspend fun writeBytes(data: ByteArray): Boolean

    /** Return any bytes received since the last call (may be empty), or null on
     *  a dead link. Implementations buffer incoming data and drain it here. */
    protected abstract fun readAvailable(): ByteArray?

    override suspend fun initElm(): Boolean {
        val reset = exchange("ATZ", 4000)
        if (reset == null) { DLog.w(TAG, "init: no response to ATZ"); return false }
        val looksElm = reset.uppercase().contains("ELM") || reset.contains("v")
        exchange("ATE0")   // echo off
        exchange("ATL0")   // linefeeds off
        exchange("ATS0")   // spaces off
        exchange("ATH0")   // headers off
        exchange("ATSP0")  // auto protocol
        DLog.i(TAG, "ELM init done (reset reply: ${reset.take(24)})")
        return looksElm
    }

    override suspend fun send(command: String, timeoutMs: Long): String? =
        exchange(command, timeoutMs)

    /** Write a command (with CR) and read until the '>' prompt or timeout. */
    private suspend fun exchange(command: String, timeoutMs: Long = 1000): String? {
        if (!writeBytes((command + "\r").toByteArray())) return null
        return withTimeoutOrNull(timeoutMs) {
            val sb = StringBuilder()
            while (true) {
                val chunk = readAvailable() ?: break  // null = dead link
                if (chunk.isNotEmpty()) {
                    sb.append(String(chunk, Charsets.US_ASCII))
                    if (sb.contains('>')) break
                } else {
                    delay(8)
                }
            }
            sb.toString().ifEmpty { null }
        }
    }

    companion object { const val TAG = "ElmProto" }
}
