# DBM — Driver Behavior Monitor

**Current version: v17.5** (see CHANGELOG.md; major = new features, minor = corrections; version is embedded in package filenames and the in-app About screen).

**Phase 1 (current):** phone-only — front camera watches the driver, rear camera watches the road ahead, with road-sign/lane compliance and driver scoring.
**Phase 2:** external Raspberry Pi Zero 2 W camera nodes over in-car WiFi (infrastructure already in `pi/`).

In-vehicle monitoring system: three Raspberry Pi Zero 2 W camera nodes stream
H.264 video over an in-car WiFi network to an Android phone, which performs
all AI inference, alerting, and evidential logging.

```
 [Driver Pi]  ── WiFi AP (192.168.50.1) ── rtsp://192.168.50.1:8554/driver
      ▲   ▲
      │   └────────── [Front Pi]  (192.168.50.11)  rtsp://192.168.50.11:8554/front
      │              [Rear  Pi]  (192.168.50.12)  rtsp://192.168.50.12:8554/rear
      │
 [Android phone] (192.168.50.20) — decoding, MediaPipe + TFLite inference,
                                   overlays, alerts, Room event DB, clip storage
```

## Repository layout

| Path | Contents |
|------|----------|
| `pi/ap/` | Driver-facing Pi: WiFi access point + camera + RTSP server |
| `pi/client/` | Front & rear Pis: WiFi client + camera + RTSP server |
| `pi/common/` | mediamtx config, camera service, status endpoint (all nodes) |
| `android/` | Android app (Kotlin, Jetpack Compose, Media3, MediaPipe, TFLite) |

## Detection functions

* **Driver camera** (full frame rate): microsleep / prolonged eye closure
  (PERCLOS + eye-aspect-ratio), head pose (not looking ahead, mirror checks),
  phone use, hands off wheel, seatbelt (custom TFLite classifier hook).
* **Front / rear cameras** (5–10 FPS): object detection (vehicles, bikes,
  motorbikes, pedestrians, scooters), track + time-to-collision proxy via
  bounding-box growth → imminent-risk alerts (tailgating, fast approach).
* All events: timestamped Room DB rows + pre/post event video clips,
  SHA-256 hashed for tamper evidence.

## Quick start

1. Flash three SD cards with Raspberry Pi OS Lite (Bookworm, 32/64-bit).
2. On the driver Pi: `sudo bash pi/ap/setup_ap.sh`
3. On front Pi: `sudo bash pi/client/setup_client.sh front 192.168.50.11`
4. On rear  Pi: `sudo bash pi/client/setup_client.sh rear 192.168.50.12`
5. Build `android/` in Android Studio (or `./gradlew assembleDebug`), install,
   join the phone to WiFi `DMS-CAR`, open the app.

## Play Store compliance notes

* All processing and storage is on-device; no network upload. Declare
  accordingly in the Data Safety form.
* App records identifiable people: ship a privacy policy URL and prominent
  in-app disclosure (see `PrivacyNotice` composable).
* Foreground service uses type `connectedDevice`.
* Marketed as a driver-awareness aid, not a safety-critical system.
