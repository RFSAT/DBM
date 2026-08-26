# Full region data pipeline — speed limits + parking + speed cameras

Every final `<region>.db` contains ALL THREE feature sets:
  * speed limits   (segments table)      — osm_to_speedlimitdb.py  (schema 3)
  * parking        (parking_lot/_curb)   — add_parking.py          (-> schema 5)
  * speed cameras  (speed_camera)        — add_parking.py          (-> schema 5)

## The two stages compose (order matters)

1. `osm_to_speedlimitdb.py` creates the DB fresh: `segments` + `meta`, schema 3.
   It DROPs/creates its tables, so it MUST run first.
2. `add_parking.py` opens that DB, ADDs parking_lot/parking_curb/speed_camera,
   and upserts schema_version -> 5. It never touches `segments`, so speed limits
   are preserved.

Both pack coordinates identically (little-endian int32 x 1e7), matching the
app's unpackCoords. Verified: running them in this order yields one DB with all
three feature sets and schema 5.

## One command for all of Europe

`build_europe.py` runs BOTH stages per region automatically, in the right order:

    python build_europe.py --dir . --base-url https://www.rfsat.com/products/maps

For each *.osm.pbf in the folder that is a recognised European country:
  [1/2] speed limits  (osm_to_speedlimitdb.py)
  [2/2] parking + cameras  (add_parking.py)
then per-file SUMMARY + VERIFICATION (which now REQUIRES a non-empty segments
table — a DB without speed limits is a hard FAIL, so you can't accidentally ship
an incomplete region).

Flags: --only greece,cyprus  |  --force  |  --skip-base (advanced: reuse an
existing segments table).

## POLICY: --drop-untagged-minor

osm_to_speedlimitdb.py supports `--drop-untagged-minor` (drops roads with no
maxspeed and no implicit default, shrinking the DB). build_europe.py does NOT
pass it: we keep full road coverage by default. Only add it — by editing the
BASE_CONVERTER line in build_europe.py — if a specific region's .db becomes
impractically large. Untagged minor roads still get sensible implicit defaults
by highway class, so keeping them costs size but improves coverage.

## Then deploy

build_europe.py also writes index.json (all regions present, correct sha256/
size/version/dbSchemaVersion=5). Upload changed .db files FIRST, index.json
LAST — see SERVER-DEPLOYMENT.md.
