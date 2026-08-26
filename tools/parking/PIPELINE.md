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

osm_to_speedlimitdb.py supports `--drop-untagged-minor`, and build_europe.py
NOW PASSES IT (in the BASE_CONVERTER line) to trim the combined DB. It drops only
DRIVABLE roads whose highway class is uncommon (not one of the ~12 classes with
an implicit default) AND that carry no maxspeed — e.g. highway=road (unknown
class), highway=track. All common classes (motorway…residential, living_street,
unclassified, service) keep their implicit default and are NOT dropped, so the
size saving is modest and the coverage loss is limited to uncommon roads.

The app LATCHES the last known speed limit across any gap: when a segment is
missing (dropped) or has no maxspeed, match() returns -1, the fuser yields no new
limit, and the display keeps the last valid value. So a driver retains a sensible
limit on the few dropped roads. Remove the flag from BASE_CONVERTER if you prefer
maximum coverage over size.

## Then deploy

build_europe.py also writes index.json (all regions present, correct sha256/
size/version/dbSchemaVersion=5). Upload changed .db files FIRST, index.json
LAST — see SERVER-DEPLOYMENT.md.
