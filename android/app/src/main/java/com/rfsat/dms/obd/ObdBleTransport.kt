package com.rfsat.dms.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.rfsat.dms.util.DLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume

/**
 * Bluetooth LE (GATT) transport for modern BLE OBD adapters (Veepeak, many
 * iPhone-compatible units, etc.). Supplies the raw byte pipe to [ElmProtocol].
 *
 * BLE OBD adapters expose a GATT service with a WRITE characteristic (send AT/PID
 * commands) and a NOTIFY characteristic (receive the adapter's reply in chunks).
 * Several UUID conventions exist; we try the common ones and otherwise fall back
 * to auto-detecting a service that has both a writable and a notifiable
 * characteristic — which covers most ELM327-style BLE adapters.
 *
 * Incoming notifications are buffered in a queue that [readAvailable] drains, so
 * the shared ElmProtocol command/response framing works unchanged.
 */
@SuppressLint("MissingPermission")
class ObdBleTransport(private val context: Context) : ElmProtocol() {

    private var gatt: BluetoothGatt? = null
    private var writeCh: BluetoothGattCharacteristic? = null
    private var notifyCh: BluetoothGattCharacteristic? = null
    @Volatile private var ready = false
    private val rxQueue = ConcurrentLinkedQueue<Byte>()
    private val writeLock = Mutex()

    override val isOpen: Boolean get() = ready

    // Common BLE-OBD service/characteristic UUIDs (and the CCCD for notifications).
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val knownServices = listOf(
        UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),  // very common
        UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),  // HM-10 style
        UUID.fromString("18f0e400-e8f2-537e-4f6c-d104768a1214"),  // some units
    )

    override suspend fun connect(mac: String): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        val device = runCatching { adapter.getRemoteDevice(mac) }.getOrNull() ?: return false
        ready = false; rxQueue.clear()
        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var resumed = false
                val cb = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            DLog.i(TAG, "BLE connected; discovering services")
                            g.discoverServices()
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            ready = false
                            if (!resumed) { resumed = true; cont.resume(false) }
                        }
                    }
                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        val ok = bindCharacteristics(g)
                        if (ok) enableNotifications(g)
                        if (!resumed) { resumed = true; cont.resume(ok) }
                    }
                    override fun onCharacteristicChanged(
                        g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
                        value.forEach { rxQueue.add(it) }
                    }
                    @Deprecated("pre-33 callback")
                    override fun onCharacteristicChanged(
                        g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                        ch.value?.forEach { rxQueue.add(it) }
                    }
                }
                gatt = device.connectGatt(context, false, cb)
                cont.invokeOnCancellation { runCatching { gatt?.disconnect() } }
            }
        } ?: false
        ready = connected && writeCh != null && notifyCh != null
        if (!ready) { close(); DLog.w(TAG, "BLE setup incomplete for $mac") }
        return ready
    }

    private fun bindCharacteristics(g: BluetoothGatt): Boolean {
        // Prefer a known service; else any service exposing both write + notify.
        val services = g.services ?: return false
        for (svcUuid in knownServices) {
            g.getService(svcUuid)?.let { svc ->
                val w = svc.characteristics.firstOrNull { it.isWritable() }
                val n = svc.characteristics.firstOrNull { it.isNotifiable() }
                if (w != null && n != null) { writeCh = w; notifyCh = n; return true }
            }
        }
        for (svc in services) {
            val w = svc.characteristics.firstOrNull { it.isWritable() }
            val n = svc.characteristics.firstOrNull { it.isNotifiable() }
            if (w != null && n != null) { writeCh = w; notifyCh = n; return true }
        }
        return false
    }

    private fun enableNotifications(g: BluetoothGatt) {
        val n = notifyCh ?: return
        g.setCharacteristicNotification(n, true)
        n.getDescriptor(cccd)?.let { d ->
            @Suppress("DEPRECATION")
            run {
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }
        }
    }

    override suspend fun writeBytes(data: ByteArray): Boolean = writeLock.withLock {
        val g = gatt ?: return false
        val w = writeCh ?: return false
        runCatching {
            @Suppress("DEPRECATION")
            run { w.value = data; g.writeCharacteristic(w) }
            true
        }.getOrDefault(false)
    }

    override fun readAvailable(): ByteArray? {
        if (!ready) return null
        if (rxQueue.isEmpty()) return ByteArray(0)
        val out = ArrayList<Byte>(rxQueue.size)
        while (true) { val b = rxQueue.poll() ?: break; out.add(b) }
        return out.toByteArray()
    }

    override fun close() {
        ready = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null; writeCh = null; notifyCh = null; rxQueue.clear()
    }

    private fun BluetoothGattCharacteristic.isWritable(): Boolean =
        properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

    private fun BluetoothGattCharacteristic.isNotifiable(): Boolean =
        properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

    companion object {
        private const val TAG = "ObdBle"
        private const val CONNECT_TIMEOUT_MS = 12000L
    }
}
