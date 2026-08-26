# DBM v1.20.19 — open items register

Baseline: **v1.20.19 is live on Google Play** (accepted). This is the stable
consolidation point. The depth / speed-camera / collapsible-settings research
(would-be 1.20.20–1.20.23) has been set aside; this tree does not contain it.

## Open CODE issues

**None.** v1.20.19 compiles, passed CI, and Play accepted it. The one lingering
concern — 16 KB alignment of MLKit / graphics-path — is resolved in practice by
Play accepting the bundle (see PLAY-COMPLIANCE.md).

## Shipping but UNVERIFIED on-device (features that exist but were never confirmed with real hardware/data)

These are the real "open issues" to close — not by writing code, but by testing
what's already shipping and fixing only what testing reveals.

1. **OBD-II adapters.** Full stack shipping: Classic (RFCOMM/SPP) + BLE (GATT) +
   in-app scan + capability discovery + speed fusion. Never tested against a real
   ELM327 adapter.
   - Test order: a Classic ELM327 clone first (validates the whole
     connect→handshake→PID→speed-fusion chain on the simplest transport), then a
     BLE adapter (Kiwi3 / Veepeak) via the Scan button.
   - Watch: does it appear in Paired (Classic) / Scan (BLE); does the speed badge
     show "· OBD"; if BLE connects but gives no data, the characteristic
     auto-detection is the suspect (logs show discovered services).

2. **Parking assistance (Tier 1).** App code + pipeline shipping, but no region
   DB was ever built with parking data and deployed, so the feature has never run
   against real data.
   - To activate: run `tools/parking/add_parking.py` on a region .pbf, deploy per
     `tools/parking/SERVER-DEPLOYMENT.md`, confirm the app downloads schema-4 DB.
   - Expectation: the car-park finder will light up (amenity=parking is well
     mapped); curbside restriction advisories will be sparse/absent in most
     regions (that's correct — "no data", never "allowed").

3. **Device-behaviour checks** (built, never measured):
   - Screen-dim thermal mode — does One UI honour the 0.02f brightness override,
     and is there a measurable thermal benefit (dim Off vs 30s)?
   - Retrained traffic-light model — German-trained (DTLD); validate on Greek
     roads for false positives and the weaker amber recall.

## DEFERRED — separate projects, NOT "close now" items

- **AGP 9 migration.** On a clock (AGP 8.x can't run under Gradle 9.6+ — CI works
  around it by pinning the wrapper). Needs a coordinated AGP+Gradle+Kotlin+KSP+
  Compose-plugin bump on its own branch with the Upgrade Assistant.
- **R8 / minification.** Play RECOMMENDATION, not a requirement. App loads
  TFLite/MediaPipe/MLKit reflectively, so R8 can strip them and crash release-only.
  Keep rules staged in proguard-rules.pro; enable + test as a dedicated task.
- **Android Auto**, OBD RPM/throttle into scoring, parking Tier 2 (sign
  detection), monocular depth — genuinely new features, not open issues.

## Suggested close order

1. **OBD on-device test** — you have adapters; this is the most-built,
   least-verified subsystem, and testing needs no server work.
2. **Parking real-data activation** — needs one region built + deployed; then
   the finder is verifiable.
3. **Device-behaviour checks** — quick, opportunistic, during the above.

Most of these need the phone/adapters/data in hand rather than more code. Where
testing reveals a real bug, that becomes a specific, minimal fix on this stable
base — one change at a time.
