# DBM OBD-II Integration — Framework & Staged Plan

Bluetooth ELM327 adapter support, built as a self-contained module under
`com.rfsat.dms.obd`, mirroring the existing `SpeedMonitor` sensor pattern
(StateFlow outputs, `start()`/`stop()`, freshness-gated `healthy`). It runs on
its own coroutine and never touches the camera frame loop.

## Module map (`com.rfsat.dms.obd`)

| File | Role |
|------|------|
| `Pid.kt` | PID catalogue + SAE J1979 decode formulas (speed, RPM, throttle, load, temps) and the support-discovery PIDs. |
| `ObdParser.kt` | Turns raw ELM327 ASCII into bytes; decodes support bitmasks. Defensive against clone quirks ("NO DATA", spacing, concatenated bytes). |
| `ObdData.kt` | `ObdData` snapshot, `ObdConnectionState`, `ObdCapabilitySet`. |
| `ObdBluetoothTransport.kt` | RFCOMM socket lifecycle, ELM327 init handshake, send/receive to the `>` prompt, adapter validation. |
| `ObdCapabilities.kt` | Walks the 0x00→0x20→0x40 support chain to discover supported PIDs. |
| `ObdManager.kt` | Orchestrates connect→discover→poll; exposes `state`, `capabilities`, `data` flows + `speedHealthy`/`speedKmh`. |
| `ObdPrefs.kt` | Remembered adapter MAC + enabled flag (in the shared "dbm" prefs). |

## Connection model (agreed)

One-time **manual setup** (scan → user picks adapter → validate via ELM327
handshake → remember MAC), then **automatic** reconnect to that MAC every drive,
with bounded background retries and silent fallback to GPS/visual. Scanning
(higher permission friction) happens only at setup; the common path is
connect-to-known-MAC.

## Adaptivity to available parameters

`ObdCapabilities.discover()` returns the vehicle's supported-PID set at connect.
The poller polls only supported PIDs; each downstream feature checks
`capabilities.supports(pid)` before relying on a signal, and otherwise falls back
to the existing vision/GPS logic. SPEED is treated as effectively guaranteed (a
clone that mis-reports support still gets SPEED polled).

---

## Staged plan

### Stage 1 — Connectivity + speed (the high-value core)  ✅ framework in place
- Bluetooth connect to a remembered MAC, ELM327 init, poll **SPEED (0x0D)**.
- `ObdManager` exposes `speedHealthy`/`speedKmh`; `MonitorService` now prefers
  OBD over GPS/visual via the new `bestSpeedKmh()` / `bestSpeedOrNull()` helper
  and the `SpeedSource.OBD` enum value.
- **Still needed to ship Stage 1:** the setup UI (scan + pick + validate + store
  MAC) — see "UI to add" below — and on-device testing against a real adapter.

### Stage 2 — Capability discovery + full common PID set  ✅ framework in place
- `ObdCapabilities` walks the support chain; `ObdManager` polls the full
  supported subset of {SPEED, RPM, THROTTLE, ENGINE_LOAD, COOLANT, INTAKE}.
- `data` flow carries every supported reading; `capabilities.summary()` is
  logged on connect.
- **Still needed:** surface RPM/throttle/load to whatever consumes them (e.g.
  driving-behaviour scoring) — currently they're published but not yet read by a
  feature.

### Stage 3 — App adaptivity  ✅ mechanism in place, consumers TBD
- Features gate on `capabilities.supports(...)`. Speed fusion already adapts
  (OBD only enters the priority chain when fresh).
- **Still needed:** wire RPM/throttle into behaviour scoring (aggressive-accel
  signal), and optionally derive own-vehicle hard-braking from OBD speed delta as
  a cross-check to the camera-based lead-braking (v19.9).

### UI to add (not yet built — needs your design pass)
- An **OBD settings section**: enable toggle, "Set up adapter" (scan + list +
  validate + remember), remembered-adapter name, "Forget", and a live status
  line bound to `ObdManager.state` (Connecting / Connected / Not found / …).
- A small **status badge** somewhere on the Detector screen showing the active
  speed source (OBD/GPS/visual) so it's visible which is in use.

## What is intentionally NOT included (per scope)
- Manufacturer-specific / CAN signals (steering, indicators, seatbelt, brake
  pressure) — not standard OBD-II, out of scope.
- USB transport — Bluetooth chosen first; the transport is isolated behind
  `ObdBluetoothTransport`, so a USB sibling can be added later behind a shared
  interface without touching the manager/poller.

## Testing notes
- Parser + support-bitmask ordering + discovery walk are unit-validated
  (Python mirrors) — the error-prone bit-order logic is confirmed.
- Connection lifecycle, ELM327 init, and real PID values are **untestable
  off-device**; first real validation is against an actual adapter. ELM327
  clones vary — test specific adapters and note which work.
