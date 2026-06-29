package com.rfsat.dms.obd

import android.bluetooth.BluetoothAdapter
import android.content.Context
import com.rfsat.dms.util.DLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Orchestrates the OBD-II adapter: connect to a remembered adapter, run the
 * ELM327 handshake, DISCOVER which PIDs the vehicle supports, then poll the
 * supported ones and publish readings — mirroring how SpeedMonitor exposes GPS.
 *
 * Exposes (all StateFlow, like SpeedMonitor):
 *   - [state]        connection lifecycle
 *   - [capabilities] discovered supported-PID set (drives app adaptivity)
 *   - [data]         latest readings (only supported fields are non-null)
 * and a [healthy]/[speedHealthy] freshness check for the speed fusion.
 *
 * STAGED: the class is written whole, but the integration is incremental —
 *   Stage 1: connect + initElm + poll SPEED only -> feed speed fusion.
 *   Stage 2: capability discovery walk -> poll the full supported set.
 *   Stage 3: downstream features consult [capabilities] to enable/disable.
 * Each stage is independently testable; nothing here blocks the frame loop.
 */
class ObdManager(
    private val context: Context,
    private val prefs: ObdPrefs = ObdPrefs(context),
) {
    private val transport = ObdBluetoothTransport()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val _state = MutableStateFlow(ObdConnectionState.NOT_CONFIGURED)
    val state: StateFlow<ObdConnectionState> = _state

    private val _capabilities = MutableStateFlow(ObdCapabilitySet())
    val capabilities: StateFlow<ObdCapabilitySet> = _capabilities

    private val _data = MutableStateFlow(ObdData())
    val data: StateFlow<ObdData> = _data

    @Volatile var enabled: Boolean = prefs.enabled
        private set

    private var lastSpeedMs = 0L

    /** True if OBD speed is connected and fresh enough to trust as primary. */
    val speedHealthy: Boolean
        get() = _state.value == ObdConnectionState.CONNECTED &&
            _data.value.speedKmh != null &&
            System.currentTimeMillis() - lastSpeedMs < SPEED_STALE_MS

    /** Latest OBD speed in km/h, or null if not available/fresh. */
    val speedKmh: Int? get() = if (speedHealthy) _data.value.speedKmh else null

    fun setEnabled(on: Boolean) {
        enabled = on; prefs.enabled = on
        if (!on) { stop(); _state.value = ObdConnectionState.DISABLED }
        else start()
    }

    /**
     * Begin the connect→discover→poll lifecycle against the remembered adapter.
     * Safe to call repeatedly; no-ops if already running. Falls back silently
     * (state NOT_FOUND/ERROR) so the rest of the app keeps using GPS/visual.
     */
    fun start() {
        if (!enabled) { _state.value = ObdConnectionState.DISABLED; return }
        val mac = prefs.adapterMac
        if (mac == null) { _state.value = ObdConnectionState.NOT_CONFIGURED; return }
        if (pollJob?.isActive == true) return
        pollJob = scope.launch { connectAndRun(mac) }
    }

    fun stop() {
        pollJob?.cancel(); pollJob = null
        transport.close()
        _data.value = ObdData()
    }

    /** A discoverable adapter candidate for the setup UI. */
    data class Candidate(val name: String, val mac: String)

    /**
     * List already-bonded Bluetooth devices (the usual place a paired OBD adapter
     * appears). We prefer bonded devices over a fresh scan because OBD adapters
     * are typically paired once in Android settings, and listing bonded devices
     * needs only BLUETOOTH_CONNECT — no scan permission or location. Names that
     * look OBD-ish are sorted first to help the user.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun bondedCandidates(): List<Candidate> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        val hints = listOf("OBD", "ELM", "VIECAR", "VLINK", "V-LINK", "VGATE", "ICAR", "KONNWEI")
        return runCatching {
            adapter.bondedDevices.orEmpty().map { Candidate(it.name ?: it.address, it.address) }
                .sortedByDescending { c -> hints.any { c.name.uppercase().contains(it) } }
        }.getOrDefault(emptyList())
    }

    /**
     * Validate a chosen adapter by connecting and running the ELM327 handshake.
     * On success, REMEMBER its MAC/name and (re)start the live connection.
     * Returns true if it really behaved like an ELM327. For the setup UI.
     */
    suspend fun setUpAdapter(candidate: Candidate): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        if (!adapter.isEnabled) return false
        stop()
        _state.value = ObdConnectionState.CONNECTING
        if (!transport.connect(adapter, candidate.mac)) {
            _state.value = ObdConnectionState.NOT_FOUND
            return false
        }
        _state.value = ObdConnectionState.HANDSHAKING
        val ok = transport.initElm()
        transport.close()
        if (ok) {
            prefs.adapterMac = candidate.mac
            prefs.adapterName = candidate.name
            prefs.enabled = true
            enabled = true
            start()   // begin the live connect→discover→poll lifecycle
        } else {
            _state.value = ObdConnectionState.ERROR
        }
        return ok
    }

    /** Forget the remembered adapter and stop. */
    fun forgetAdapter() {
        stop()
        prefs.forget()
        _state.value = ObdConnectionState.NOT_CONFIGURED
    }

    val rememberedName: String? get() = prefs.adapterName

    private suspend fun connectAndRun(mac: String) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            _state.value = ObdConnectionState.ERROR
            DLog.w(TAG, "Bluetooth off/unavailable")
            return
        }
        var attempt = 0
        while (scope.isActive && enabled && attempt < CONNECT_RETRIES) {
            attempt++
            _state.value = ObdConnectionState.CONNECTING
            if (!transport.connect(adapter, mac)) {
                _state.value = ObdConnectionState.NOT_FOUND
                delay(RECONNECT_DELAY_MS)
                continue
            }
            _state.value = ObdConnectionState.HANDSHAKING
            if (!transport.initElm()) {
                DLog.w(TAG, "ELM init failed; retrying")
                transport.close(); delay(RECONNECT_DELAY_MS); continue
            }
            // Stage 2: capability discovery.
            val caps = ObdCapabilities.discover(transport)
            _capabilities.value = caps
            DLog.i(TAG, "OBD capabilities: ${caps.summary()}")

            // Poll only the supported data PIDs; always include SPEED if present.
            val toPoll = caps.supportedDataPids().ifEmpty {
                // Some clones mis-report support; assume at least SPEED works.
                listOf(Pid.SPEED)
            }
            _state.value = ObdConnectionState.CONNECTED
            attempt = 0  // reset on a good connect
            runPollLoop(toPoll)
            // If the poll loop returns, the link dropped — loop to reconnect.
            if (scope.isActive && enabled) {
                _state.value = ObdConnectionState.CONNECTING
                delay(RECONNECT_DELAY_MS)
            }
        }
        if (attempt >= CONNECT_RETRIES) {
            _state.value = ObdConnectionState.NOT_FOUND
            DLog.w(TAG, "OBD: gave up after $CONNECT_RETRIES connect attempts")
        }
    }

    /**
     * Poll supported PIDs round-robin. SPEED is polled every cycle (it's the
     * priority signal); slower-changing PIDs are polled every Nth cycle so the
     * latency-limited bus isn't starved of speed updates.
     */
    private suspend fun runPollLoop(pids: List<Pid>) {
        val slow = pids.filter { it != Pid.SPEED }
        var cycle = 0
        while (scope.isActive && enabled) {
            // Always poll speed if supported.
            if (Pid.SPEED in pids) pollOne(Pid.SPEED)
            // Poll one slow PID per cycle, round-robin.
            if (slow.isNotEmpty()) pollOne(slow[cycle % slow.size])
            cycle++
            // If a whole cycle produced no usable speed for too long, the link is
            // probably dead; break to trigger reconnect.
            if (System.currentTimeMillis() - lastSpeedMs > LINK_DEAD_MS &&
                Pid.SPEED in pids) {
                DLog.w(TAG, "no speed for ${LINK_DEAD_MS}ms; assuming link dropped")
                transport.close(); return
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun pollOne(pid: Pid) {
        val raw = transport.send(pid.command, timeoutMs = PID_TIMEOUT_MS) ?: return
        val bytes = ObdParser.parseData(raw, pid) ?: return
        val value = pid.decode(bytes) ?: return
        val now = System.currentTimeMillis()
        val d = _data.value
        _data.value = when (pid) {
            Pid.SPEED -> { lastSpeedMs = now; d.copy(speedKmh = value.toInt(), tMs = now) }
            Pid.RPM -> d.copy(rpm = value.toInt(), tMs = now)
            Pid.THROTTLE -> d.copy(throttlePct = value.toInt(), tMs = now)
            Pid.ENGINE_LOAD -> d.copy(enginePct = value.toInt(), tMs = now)
            Pid.COOLANT_TEMP -> d.copy(coolantC = value.toInt(), tMs = now)
            Pid.INTAKE_TEMP -> d.copy(intakeC = value.toInt(), tMs = now)
            else -> d
        }
    }

    companion object {
        private const val TAG = "ObdManager"
        private const val POLL_INTERVAL_MS = 60L     // ~16 polls/s budget across PIDs
        private const val PID_TIMEOUT_MS = 300L
        private const val SPEED_STALE_MS = 1500L     // speed older than this = not fresh
        private const val LINK_DEAD_MS = 4000L       // no speed this long = reconnect
        private const val RECONNECT_DELAY_MS = 2000L
        private const val CONNECT_RETRIES = 5
    }
}
