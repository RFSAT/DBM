package com.rfsat.dms.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.rfsat.dms.util.DLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth RFCOMM transport to a classic-SPP ELM327 OBD-II adapter.
 *
 * Responsibilities (transport only — no PID/polling logic):
 *   - open/close an RFCOMM socket to a specific adapter (by MAC),
 *   - run the ELM327 initialisation handshake,
 *   - send a command and read its response up to the ELM327 '>' prompt,
 *   - validate that a candidate device really is an ELM327 (for setup).
 *
 * All blocking I/O runs on Dispatchers.IO; callers use suspend functions. This
 * mirrors how SpeedMonitor isolates its sensor I/O from the frame loop.
 */
class ObdBluetoothTransport {

    // Standard Serial Port Profile UUID — what classic ELM327 clones expose.
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    val isOpen: Boolean get() = socket?.isConnected == true

    /**
     * Open an RFCOMM connection to the adapter with the given MAC address.
     * Returns true on success. Cancels any in-progress discovery first (scanning
     * while connecting is a classic Android Bluetooth pitfall).
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(adapter: BluetoothAdapter, mac: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                if (adapter.isDiscovering) adapter.cancelDiscovery()
                val device: BluetoothDevice = adapter.getRemoteDevice(mac)
                val s = device.createRfcommSocketToServiceRecord(sppUuid)
                s.connect()
                socket = s
                input = s.inputStream
                output = s.outputStream
                DLog.i(TAG, "RFCOMM connected to $mac")
                true
            }.getOrElse {
                DLog.w(TAG, "RFCOMM connect to $mac failed: ${it.message}")
                close()
                false
            }
        }

    /**
     * Standard ELM327 init sequence. Returns true if the adapter responds like a
     * real ELM327 (used both to initialise and to VALIDATE a candidate during
     * setup). Sequence: reset, echo off, linefeeds off, spaces off, headers off,
     * auto protocol.
     */
    suspend fun initElm(): Boolean = withContext(Dispatchers.IO) {
        // ATZ can be slow (full reset); give it room. A real ELM327 returns a
        // version string containing "ELM" on reset.
        val reset = sendRaw("ATZ", timeoutMs = 4000)
        if (reset == null) { DLog.w(TAG, "init: no response to ATZ"); return@withContext false }
        val looksElm = reset.uppercase().contains("ELM") || reset.contains("v")
        // These are best-effort; failures on a clone are non-fatal.
        sendRaw("ATE0")   // echo off
        sendRaw("ATL0")   // linefeeds off
        sendRaw("ATS0")   // spaces off (we tolerate spaces in the parser anyway)
        sendRaw("ATH0")   // headers off
        sendRaw("ATSP0")  // auto-detect protocol
        DLog.i(TAG, "ELM init done (reset reply: ${reset.take(24)})")
        looksElm
    }

    /**
     * Send an OBD/AT command and return the raw response text up to the ELM327
     * '>' prompt, or null on timeout/error. Appends the required CR.
     */
    suspend fun send(command: String, timeoutMs: Long = 1000): String? =
        sendRaw(command, timeoutMs)

    private suspend fun sendRaw(command: String, timeoutMs: Long = 1000): String? =
        withContext(Dispatchers.IO) {
            val out = output ?: return@withContext null
            val inp = input ?: return@withContext null
            runCatching {
                out.write((command + "\r").toByteArray())
                out.flush()
                withTimeoutOrNull(timeoutMs) {
                    val sb = StringBuilder()
                    val buf = ByteArray(64)
                    // read until the ELM327 prompt '>' appears
                    while (true) {
                        if (inp.available() > 0) {
                            val n = inp.read(buf)
                            if (n > 0) {
                                sb.append(String(buf, 0, n, Charsets.US_ASCII))
                                if (sb.contains('>')) break
                            }
                        } else {
                            kotlinx.coroutines.delay(8)  // brief yield; bus latency
                        }
                    }
                    sb.toString()
                }
            }.getOrNull()
        }

    fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null; output = null; socket = null
    }

    companion object { private const val TAG = "ObdBt" }
}
