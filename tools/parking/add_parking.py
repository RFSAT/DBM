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
import os
import sqlite3
import struct
import sys

SCHEMA_VERSION = 5          # v4 parking; v5 adds speed_camera
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


def camera_tags(tags):
    """Extract speed-camera info from a node's tags, or None.

    Recognises the standard `highway=speed_camera` node tagging. Optional
    maxspeed and direction enrich the warning. Enforcement type is only read if
    tagged on the node itself (the full picture lives in an `enforcement`
    relation we don't traverse). Legality of surfacing camera warnings is a
    CONSUMER concern handled in the app (opt-in), not an extraction concern.
    """
    if tags.get("highway") != "speed_camera":
        return None
    ms = tags.get("maxspeed")
    try:
        ms = int(str(ms).split()[0]) if ms else None    # "50" or "50 mph" -> 50
    except (ValueError, IndexError):
        ms = None
    return {
        "maxspeed": ms,
        "direction": tags.get("direction"),
        "kind": tags.get("enforcement"),
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

DROP TABLE IF EXISTS speed_camera;
CREATE TABLE speed_camera(
  id        INTEGER,
  lat       REAL,
  lon       REAL,
  maxspeed  INTEGER,     -- enforced limit if tagged, else NULL
  direction TEXT,        -- forward | backward | both | compass, if tagged
  kind      TEXT,        -- enforcement type if tagged on the node (maxspeed/average_speed)
  minLat REAL, maxLat REAL, minLon REAL, maxLon REAL
);
CREATE INDEX idx_cam_bbox ON speed_camera(minLat, maxLat);
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
    stats = {"curb": 0, "lot": 0, "cam": 0}

    import time as _time
    import sys as _sys

    # Progress lines overwrite each other on one line (carriage return) instead
    # of scrolling. To avoid leaving tail characters when a new line is shorter
    # than the previous one, we pad every line to the widest length seen so far.
    # A newline is printed once when the phase ends (see _finish_line).
    _pw = {"max": 0}

    def _line(text):
        pad = max(0, _pw["max"] - len(text))
        _pw["max"] = max(_pw["max"], len(text))
        _sys.stdout.write("\r" + text + " " * pad)
        _sys.stdout.flush()

    def _finish_line():
        # Terminate the current overwriting line so following output is clean.
        if _pw["max"] > 0:
            _sys.stdout.write("\n")
            _sys.stdout.flush()
            _pw["max"] = 0

    # The speed-limit stage (osm_to_speedlimitdb) scanned this same .pbf first and
    # recorded the node total in the DB's meta table. We scan the SAME file, so
    # that total is a valid denominator — read it back for a real percentage.
    total_nodes = 0
    try:
        row = con.execute(
            "SELECT value FROM meta WHERE key='total_nodes'").fetchone()
        if row:
            total_nodes = int(row[0])
    except sqlite3.Error:
        total_nodes = 0

    progress = {"nodes": 0, "ways": 0, "t0": _time.time(),
                "last_t": _time.time(), "last_n": 0, "total_nodes": total_nodes}
    try:
        _mb = os.path.getsize(pbf) / 1e6
    except OSError:
        _mb = 0.0
    if total_nodes > 0:
        # We know the node total from stage 1, so the node phase shows a real
        # percentage. (Ways come after nodes in a PBF; the way phase then shows
        # counts + rate — its own total isn't needed for the useful progress.)
        print(f"  scanning {pbf} ({_mb:.0f} MB, ~{total_nodes:,} nodes) for "
              f"parking + cameras — node phase shows % complete…", flush=True)
    else:
        # No stored total (e.g. base stage was skipped) — fall back to counts+rate.
        print(f"  scanning {pbf} ({_mb:.0f} MB) for parking + cameras; progress "
              f"every 200k nodes / 100k ways (elapsed / counts / rate)…",
              flush=True)

    def _emit(kind):
        now = _time.time()
        total = progress["nodes"] + progress["ways"]
        dt = now - progress["last_t"]
        dn = total - progress["last_n"]
        rate = (dn / dt) if dt > 0 else 0
        elapsed = now - progress["t0"]
        # Percentage only makes sense during the node phase against total_nodes.
        if kind == "nodes" and progress["total_nodes"] > 0:
            pct = f"{100.0 * progress['nodes'] / progress['total_nodes']:4.0f}%"
        else:
            pct = "    "     # blank slot keeps columns aligned in the way phase
        _line(f"  [{elapsed:6.0f}s] {pct} {kind:5s}  "
              f"nodes={progress['nodes']:>10,}  ways={progress['ways']:>9,}  "
              f"| found: lots={stats['lot']:>6,} curb={stats['curb']:>6,} "
              f"cams={stats['cam']:>4,}  "
              f"| {rate/1000:.0f}k elem/s")
        progress["last_t"] = now
        progress["last_n"] = total

    class Handler(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.curb_rows = []
            self.lot_rows = []
            self.cam_rows = []

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
            progress["nodes"] += 1
            if progress["nodes"] % 200_000 == 0:
                _emit("nodes")
            if not n.location.valid():
                return
            lat, lon = n.location.lat, n.location.lon
            self._lot(n, [(lat, lon)])
            cam = camera_tags(dict(n.tags))
            if cam is not None:
                m = 0.0002    # ~22 m bbox so the indexed range query finds it
                self.cam_rows.append((
                    n.id, lat, lon, cam["maxspeed"], cam["direction"], cam["kind"],
                    lat - m, lat + m, lon - m, lon + m))
                stats["cam"] += 1

        def way(self, w):
            progress["ways"] += 1
            if progress["ways"] % 100_000 == 0:
                _emit("ways")
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
    print("(A full country takes several minutes and builds a node-location "
          "index first — the first progress line may take a little while.)",
          flush=True)
    h.apply_file(pbf, locations=True)
    _finish_line()   # end the overwriting progress line before the summary

    total = progress["nodes"] + progress["ways"]
    elapsed = _time.time() - progress["t0"]
    print(f"Scan complete: {total:,} elements in {elapsed:.0f}s. "
          f"Writing tables to the database…", flush=True)

    con.executemany(
        "INSERT INTO parking_curb VALUES(" + ",".join("?" * 17) + ")", h.curb_rows)
    con.executemany(
        "INSERT INTO parking_lot VALUES(" + ",".join("?" * 14) + ")", h.lot_rows)
    con.executemany(
        "INSERT INTO speed_camera VALUES(" + ",".join("?" * 10) + ")", h.cam_rows)
    set_meta(con, "parking_curb_rows", stats["curb"])
    set_meta(con, "parking_lot_rows", stats["lot"])
    set_meta(con, "speed_camera_rows", stats["cam"])
    set_meta(con, "schema_version", SCHEMA_VERSION)
    con.commit()
    print("Database written.\n", flush=True)

    print(f"parking_lot  : {stats['lot']:>7} features")
    print(f"parking_curb : {stats['curb']:>7} side-records")
    print(f"speed_camera : {stats['cam']:>7} cameras")
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
