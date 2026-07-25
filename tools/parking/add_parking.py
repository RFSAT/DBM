#!/usr/bin/env python3
"""
add_parking.py — add parking data to an existing DBM region database.

Reads a Geofabrik .osm.pbf and writes two new tables into the region .db that
your existing converter already produced, so this is an ADDITIONAL step in the
pipeline rather than a replacement:

    python add_parking.py --pbf greece-latest.osm.pbf --db greece.db

Two independent layers, because they have very different coverage:

  parking_lot   amenity=parking facilities (car parks). Well mapped almost
                everywhere — this is what the "where can I park" finder reads.

  parking_curb  Curbside restrictions from the OSM "Street parking" schema
                (parking:{left,right,both}:*). Sparse and regional — this is
                what advisory restriction warnings read, and it is expected to
                be empty in many countries. That is a valid outcome, not a bug:
                the app must say "no data here", never "no restrictions here".

Schema notes (matched to the existing `segments` table on purpose):
  * Android's bundled SQLite has NO rtree module, so spatial filtering uses
    plain indexed bbox columns (minLat/maxLat/minLon/maxLon), exactly as
    OsmMap.queryNear() already does.
  * Way geometry is packed as pairs of little-endian int32 (degrees * 1e7),
    the same encoding OsmMap.unpackCoords() reads.

Requires: pyosmium  (pip install osmium)
"""

import argparse
import sqlite3
import struct
import sys

SCHEMA_VERSION = 4          # bumped: adds parking_lot + parking_curb
COORD_SCALE = 1e7

# --- tag interpretation -----------------------------------------------------
# Kept as pure functions with no osmium types so they can be unit-tested.

SIDES = ("left", "right", "both")

# Values of parking:<side>:restriction that mean "you may not leave a car here".
BLOCKING = {"no_parking", "no_stopping", "no_standing"}


def curb_tags(tags):
    """Extract curbside parking info from an OSM way's tags.

    Returns a list of dicts (one per tagged side), or [] if the way carries no
    street-parking tags at all. Both the plain and :conditional variants are
    kept verbatim — the conditional grammar is parsed at runtime on the phone,
    not here, so that a parser fix does not require regenerating every region.
    """
    out = []
    for side in SIDES:
        p = f"parking:{side}"
        # A side is only interesting if it carries a restriction, an access
        # rule, or a permit. `parking:<side>=lane` alone just says parking
        # exists, which we record only when accompanied by one of those.
        keys = {
            "restriction":   tags.get(f"{p}:restriction"),
            "restr_cond":    tags.get(f"{p}:restriction:conditional"),
            "access":        tags.get(f"{p}:access"),
            "access_cond":   tags.get(f"{p}:access:conditional"),
            "permit":        tags.get(f"{p}:permit"),
            "zone":          tags.get(f"{p}:zone"),
            "maxstay":       tags.get(f"{p}:maxstay"),
            "maxstay_cond":  tags.get(f"{p}:maxstay:conditional"),
            "fee":           tags.get(f"{p}:fee"),
        }
        if not any(v for k, v in keys.items()
                   if k in ("restriction", "restr_cond", "access",
                            "access_cond", "permit")):
            continue
        keys["side"] = side
        keys["parking"] = tags.get(p)          # lane / street_side / no / ...
        out.append(keys)
    return out


def lot_tags(tags):
    """Extract car-park info from an amenity=parking feature, or None."""
    if tags.get("amenity") != "parking":
        return None
    cap = tags.get("capacity")
    try:
        cap = int(cap) if cap is not None else None
    except ValueError:
        cap = None
    return {
        "name":     tags.get("name"),
        "kind":     tags.get("parking"),        # surface/multi-storey/underground/...
        "access":   tags.get("access"),         # yes/private/customers/permit/no
        "fee":      tags.get("fee"),
        "capacity": cap,
        "maxstay":  tags.get("maxstay"),
        "hours":    tags.get("opening_hours"),
    }


def pack_coords(coords):
    """Pack [(lat, lon), ...] as little-endian int32 pairs (deg * 1e7).

    Matches OsmMap.unpackCoords() on the app side.
    """
    buf = bytearray()
    for lat, lon in coords:
        buf += struct.pack("<ii", int(round(lat * COORD_SCALE)),
                                  int(round(lon * COORD_SCALE)))
    return bytes(buf)


def bbox(coords):
    lats = [c[0] for c in coords]
    lons = [c[1] for c in coords]
    return min(lats), max(lats), min(lons), max(lons)


# --- database ---------------------------------------------------------------

DDL = """
DROP TABLE IF EXISTS parking_curb;
DROP TABLE IF EXISTS parking_lot;

CREATE TABLE parking_curb(
  id           INTEGER,
  side         TEXT,      -- left | right | both
  parking      TEXT,      -- lane | street_side | on_kerb | no | ...
  restriction  TEXT,      -- no_parking | no_stopping | no_standing | loading_only
  restr_cond   TEXT,      -- e.g. "no_stopping @ (Mo-Fr 07:00-09:00)"
  access       TEXT,      -- yes | permit | private | customers | no
  access_cond  TEXT,      -- e.g. "permit @ (Mo-Fr 08:00-18:30)"
  permit       TEXT,      -- residents | ...
  zone         TEXT,
  maxstay      TEXT,
  maxstay_cond TEXT,
  fee          TEXT,
  coords       BLOB,
  minLat REAL, maxLat REAL, minLon REAL, maxLon REAL
);
CREATE INDEX idx_curb_bbox ON parking_curb(minLat, maxLat);

CREATE TABLE parking_lot(
  id       INTEGER,
  name     TEXT,
  kind     TEXT,          -- surface | multi-storey | underground | ...
  access   TEXT,
  fee      TEXT,
  capacity INTEGER,
  maxstay  TEXT,
  hours    TEXT,          -- opening_hours syntax
  lat REAL, lon REAL,     -- representative point, for distance ranking
  minLat REAL, maxLat REAL, minLon REAL, maxLon REAL
);
CREATE INDEX idx_lot_bbox ON parking_lot(minLat, maxLat);
"""


def open_db(path):
    con = sqlite3.connect(path)
    con.executescript(DDL)
    return con


def set_meta(con, key, value):
    con.execute(
        "INSERT INTO meta(key,value) VALUES(?,?) "
        "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        (key, str(value)))


# --- extraction (needs osmium) ---------------------------------------------

def run(pbf, db_path):
    try:
        import osmium
    except ImportError:
        sys.exit("pyosmium is required for extraction: pip install osmium")

    con = open_db(db_path)
    stats = {"curb": 0, "lot": 0}

    class Handler(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.curb_rows = []
            self.lot_rows = []

        def _lot(self, o, coords):
            info = lot_tags(dict(o.tags))
            if info is None or not coords:
                return
            mnla, mxla, mnlo, mxlo = bbox(coords)
            self.lot_rows.append((
                o.id, info["name"], info["kind"], info["access"], info["fee"],
                info["capacity"], info["maxstay"], info["hours"],
                sum(c[0] for c in coords) / len(coords),
                sum(c[1] for c in coords) / len(coords),
                mnla, mxla, mnlo, mxlo))
            stats["lot"] += 1

        def node(self, n):
            if n.location.valid():
                self._lot(n, [(n.location.lat, n.location.lon)])

        def way(self, w):
            try:
                coords = [(nd.lat, nd.lon) for nd in w.nodes if nd.location.valid()]
            except osmium.InvalidLocationError:
                return
            if len(coords) < 2:
                return
            tags = dict(w.tags)

            self._lot(w, coords)

            sides = curb_tags(tags)
            if sides:
                blob = pack_coords(coords)
                mnla, mxla, mnlo, mxlo = bbox(coords)
                for s in sides:
                    self.curb_rows.append((
                        w.id, s["side"], s["parking"], s["restriction"],
                        s["restr_cond"], s["access"], s["access_cond"],
                        s["permit"], s["zone"], s["maxstay"], s["maxstay_cond"],
                        s["fee"], blob, mnla, mxla, mnlo, mxlo))
                    stats["curb"] += 1

    h = Handler()
    # locations=True builds the node-location index needed for way geometry.
    h.apply_file(pbf, locations=True)

    con.executemany(
        "INSERT INTO parking_curb VALUES(" + ",".join("?" * 17) + ")", h.curb_rows)
    con.executemany(
        "INSERT INTO parking_lot VALUES(" + ",".join("?" * 14) + ")", h.lot_rows)
    set_meta(con, "parking_curb_rows", stats["curb"])
    set_meta(con, "parking_lot_rows", stats["lot"])
    set_meta(con, "schema_version", SCHEMA_VERSION)
    con.commit()

    print(f"parking_lot  : {stats['lot']:>7} features")
    print(f"parking_curb : {stats['curb']:>7} side-records")
    if stats["curb"] == 0:
        print("\nNo curbside restriction data in this region. That is common —")
        print("the app must present this as 'no data', never as 'no restrictions'.")
    con.close()


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pbf", required=True, help="Geofabrik .osm.pbf extract")
    ap.add_argument("--db", required=True, help="existing region .db to extend")
    a = ap.parse_args()
    run(a.pbf, a.db)


if __name__ == "__main__":
    main()
