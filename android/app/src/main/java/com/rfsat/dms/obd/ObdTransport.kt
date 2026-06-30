package com.rfsat.dms.obd

/**
 * Transport-agnostic interface to an ELM327-style OBD adapter. The manager talks
 * only to this, so Bluetooth Classic (RFCOMM/SPP) and Bluetooth LE (GATT)
 * adapters are interchangeable. Each implementation supplies the raw byte pipe;
 * the shared ELM327 conversation (init handshake, command/response framing to the
 * '>' prompt) lives in [ElmProtocol] so it isn't duplicated.
 */
interface ObdTransport {
    val isOpen: Boolean

    /** Open a link to the adapter identified by [mac]. Returns true on success. */
    suspend fun connect(mac: String): Boolean

    /** Run the ELM327 init handshake; returns true if it behaves like an ELM327. */
    suspend fun initElm(): Boolean

    /** Send a command, return the raw response text up to the ELM327 '>' prompt. */
    suspend fun send(command: String, timeoutMs: Long = 1000): String?

    fun close()
}

/** Which Bluetooth transport an adapter uses. */
enum class ObdTransportKind { CLASSIC, BLE }
