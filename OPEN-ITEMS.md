# DBM open items register — current as of v1.20.48

Baseline: **v1.20.19 was the stable Play-accepted consolidation point**; the tree
has since advanced to **v1.20.48** with the Europe map pipeline, several UI
features, R8 minification, and CI hardening. This register is updated per release
(see "Doc maintenance" at the end).

## Open CODE issues

**None known outstanding at v1.20.48.** The compile error introduced with the
collapsible-settings work (missing `rememberSaveable` import) was fixed in
v1.20.44. Balance/annotation checks pass; the real proof remains a green CI
compile of both debug and release variants.

## Shipping but UNVERIFIED on-device (features that exist but were never confirmed with real hardware/data)

These are the real "open issues" to close — not by writing code, but by testing
what's already shipping and fixing only what testing reveals.

1. **R8 / minification release build (NEW — highest priority to verify).** R8 was
   enabled in v1.20.32 to meet Play's obfuscation requirement. R8 breakage is
   RELEASE-ONLY and invisible in the debug build CI compiles. Must be exercised on
   a real device with the actual release AAB/APK: launch, monitoring, sign OCR,
   traffic-light, face landmarker, OBD. A stripped-at-runtime crash names the
   missing class → one keep line in proguard-rules.pro. The ML AARs ship their own
   consumer keep rules, so this is expected to work, but is UNVERIFIED on-device.

2. **OBD-II adapters.** Full stack shipping: Classic (RFCOMM/SPP) + BLE (GATT) +
   in-app scan + capability discovery + speed fusion. Never tested against a real
   ELM327 adapter. Note: the OBD tab now only appears when OBD is enabled in
   Settings (v1.20.36).
   - Test order: a Classic ELM327 clone first, then a BLE adapter (Kiwi3 /
     Veepeak) via the Scan button.
   - Watch: does it appear in Paired (Classic) / Scan (BLE); does the speed badge
     show "· OBD"; if BLE connects but gives no data, suspect the characteristic
     auto-detection (logs show discovered services).

3. **Europe map pipeline — real-data run (NEW, supersedes the old parking item).**
   The full pipeline is built and extensively tested with mock tools, but has
   NEVER been run end-to-end on a real Geofabrik .pbf (no pyosmium/.pbf in the dev
   sandbox). First real run is the proof.
   - Start with Greece: `python build_europe.py --dir . --base-url
     https://www.rfsat.com/products/maps --only greece`.
   - Confirm the summary shows non-zero segments (speed limits), ~15,000 parking
     lots, and ~413 speed cameras (matches the live Overpass coverage check).
   - Then deploy per `tools/parking/SERVER-DEPLOYMENT.md` and confirm the app
     downloads the schema-5 DB and the features light up.
   - Sub-region folders, resumable index.json, stale-cleanup, and the
     folder-name flexibility (v1.20.46) are all mock-tested only.

4. **Device-behaviour checks** (built, never measured):
   - Screen-dim thermal mode — does One UI honour the 0.02f brightness override,
     and is there a measurable thermal benefit?
   - Retrained traffic-light model — German-trained (DTLD); validate on Greek
     roads for false positives and weaker amber recall.

## App UI still to build (from the sub-region requirements)

- **Collapsible parent→sub-region list** in the Settings map manager: show the
  country (e.g. Germany), tap to reveal its sub-regions to choose from. The
  manifest already carries `parent` + per-feature `counts` to drive this.
- **Region info + map preview window** when choosing a region to download:
  data summary (ways, parking, curb, cameras — data IS in the manifest) plus a
  map view. DECISION PENDING: the Geofabrik-style hover image can't be reproduced
  offline; the offered alternative is a bounding-box outline on a simple map,
  which needs bbox added to the pipeline (cheap — the base converter already
  computes it). Awaiting confirmation before building.

## DEFERRED — separate projects, NOT "close now" items

- **AGP 9 migration.** Still on the 8.x line (compileSdk 36 works under AGP
  8.12.0). AGP 9 has breaking DSL/Kotlin changes; needs a coordinated bump on its
  own branch. Play lists it as a recommendation, not a requirement.
- **Android Auto**, OBD RPM/throttle into scoring, parking Tier 2 (sign
  detection), monocular depth — genuinely new features, not open issues.

## RESOLVED since v1.20.19 (moved out of "open")

- **R8 / minification** — enabled v1.20.32 (Play requirement). Now needs
  on-device verification (item 1 above), but no longer a deferred "maybe".
- **Europe map processing** — built across v1.20.20–v1.20.47 (batch processor,
  speed-limit + parking + camera pipeline, sub-regions, resumability, progress).

## Doc maintenance

OPEN-ITEMS.md and PLAY-COMPLIANCE.md are updated as part of each release, together
with CHANGELOG.md / build.gradle.kts / README.md, whenever a release changes what
they describe. If a release doesn't touch their subject matter, only the "current
as of" version line is refreshed.
