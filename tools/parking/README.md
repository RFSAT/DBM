# DBM parking data — Tier 1 pipeline (step 1 of 3)

Adds parking data to a region `.db` your existing converter already produced.
This is an extra pipeline step, not a replacement:

```bash
pip install osmium
python add_parking.py --pbf greece-latest.osm.pbf --db greece.db
python test_parking.py          # unit tests, no .pbf needed
```

## Two layers, deliberately separate

| Table | Source | Coverage | Feeds |
|---|---|---|---|
| `parking_lot` | `amenity=parking` | Good globally (526 features in a 10 km box over central Athens) | the "where can I park" finder |
| `parking_curb` | `parking:{left,right,both}:*` | Sparse and regional; often zero | advisory restriction warnings |

`parking_curb` being empty for a region is a normal outcome. The app must
render that as **"no parking data for this area"**, never as "no restrictions
here" — conflating the two is the one genuinely unsafe failure mode in this
feature.

## Schema decisions

Both tables mirror the existing `segments` table on purpose:

* **Plain indexed bbox columns**, not R-tree — Android's bundled SQLite has no
  rtree module, so `OsmMap.queryNear()` filters on `minLat/maxLat/minLon/maxLon`.
  The parking tables use the identical pattern and the same index shape.
* **Geometry packed as little-endian int32 pairs (deg × 1e7)** — the same
  encoding `OsmMap.unpackCoords()` already reads, so the Kotlin side needs no
  new decoder.
* **Conditional values are stored verbatim** (e.g.
  `no_stopping @ (Mo-Fr 07:00-09:00)`) and parsed at runtime on the phone. That
  way a parser fix ships in an app update instead of forcing every region `.db`
  to be regenerated.
* `schema_version` in `meta` is bumped to 4; row counts are written to
  `parking_lot_rows` / `parking_curb_rows` so the app can tell "no data for this
  region" from "region not built with parking support".

## Still to build

2. **Conditional parser** (Kotlin) for the `@ (...)` grammar — `Mo-Fr 07:00-09:00`,
   `stay < 2 hours`, `;`-separated rules where later rules win. Consider
   `ch.poole:OpeningHoursParser` (used by JOSM/Vespucci) rather than writing it.
3. **Runtime + UI** — `ParkingMonitor` reading these tables, advisory display,
   the finder, and the settings toggle.
