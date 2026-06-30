package com.rfsat.dms.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.rfsat.dms.util.DLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Classic (RFCOMM / SPP) transport — what most cheap ELM327 clones
 * use. Supplies the raw byte pipe to [ElmProtocol]; the ELM conversation itself
 * is shared.
 */
class ObdClassicTransport : ElmProtocol() {

    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override val isOpen: Boolean get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    override suspend fun connect(mac: String): Boolean = withContext(Dispatchers.IO) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return@withContext false
        runCatching {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            val device: BluetoothDevice = adapter.getRemoteDevice(mac)
            val s = device.createRfcommSocketToServiceRecord(sppUuid)
            s.connect()
            socket = s; input = s.inputStream; output = s.outputStream
            DLog.i(TAG, "RFCOMM connected to $mac")
            true
        }.getOrElse {
            DLog.w(TAG, "RFCOMM connect to $mac failed: ${it.message}")
            close(); false
        }
    }

    override suspend fun writeBytes(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val out = output ?: return@withContext false
        runCatching { out.write(data); out.flush(); true }.getOrDefault(false)
    }

    override fun readAvailable(): ByteArray? {
        val inp = input ?: return null
        return runCatching {
            val n = inp.available()
            if (n <= 0) ByteArray(0)
            else { val b = ByteArray(n); val r = inp.read(b); if (r <= 0) ByteArray(0) else b.copyOf(r) }
        }.getOrNull()
    }

    override fun close() {
        runCatching { input?.close() }; runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null; output = null; socket = null
    }

    companion object { private const val TAG = "ObdClassic" }
}
