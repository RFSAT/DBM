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
import math
import os
import sqlite3
import struct
import sys

SCHEMA_VERSION = 6          # v4 parking; v5 adds speed_camera;
                            # v6 adds resolved camera direction (wayDeg/oneway/
                            # trafficDeg) so the app can warn only about cameras
                            # facing the driver's own direction of travel.
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
        # "charge" carries the actual tariff string when a mapper recorded one
        # (e.g. "EUR 2.00/hour"); fee:conditional carries time-based variants.
        # Sparsely mapped in OSM, but real pricing where present.
        "charge":   tags.get("charge"),
        "fee_cond": tags.get("fee:conditional"),
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


# --- camera direction resolution --------------------------------------------
# A camera only measures traffic travelling one way along the road (except where
# tagged direction=both). To warn ONLY about cameras pointed at the driver's own
# carriageway, the app needs the bearing of the MONITORED TRAFFIC — which the raw
# `direction` tag does not reliably give:
#
#   * direction=forward|backward is relative to how the parent way was drawn, so
#     it is meaningless without that way.
#   * a compass direction=<deg|cardinal> on a camera node is, per the OSM wiki,
#     "the way the camera faces" — which is parallel to the road but may point
#     WITH the monitored traffic (rear-facing housings, common in the UK) or
#     AGAINST it (front-plate housings, common on the continent). Facing alone
#     therefore cannot tell the two carriageways apart.
#
# What IS unambiguous is the parent way: if the camera sits on a ONE-WAY way
# (each carriageway of a dual carriageway is its own one-way way), the traffic it
# sees can only be going that way. So this stage resolves, per camera:
#
#   wayDeg     bearing of the parent highway at the camera, as drawn
#   oneway     'yes' | '-1' | 'no' for that way
#   trafficDeg bearing of the monitored traffic, or NULL when undeterminable
#
# trafficDeg = NULL means "cannot tell", and the app MUST still warn (fail open):
# suppressing a warning on a guess would be a safety regression.

CARDINAL_DEG = {
    "n": 0.0, "nne": 22.5, "ne": 45.0, "ene": 67.5,
    "e": 90.0, "ese": 112.5, "se": 135.0, "sse": 157.5,
    "s": 180.0, "ssw": 202.5, "sw": 225.0, "wsw": 247.5,
    "w": 270.0, "wnw": 292.5, "nw": 315.0, "nnw": 337.5,
    "north": 0.0, "northeast": 45.0, "east": 90.0, "southeast": 135.0,
    "south": 180.0, "southwest": 225.0, "west": 270.0, "northwest": 315.0,
}


def parse_compass(value):
    """Parse an OSM direction value as a compass bearing (deg, 0=N), or None.

    Accepts a number ("270", "270.5") or a cardinal/intercardinal name ("SW").
    Returns None for forward/backward/both and anything unparseable.
    """
    if value is None:
        return None
    s = str(value).strip().lower()
    if not s:
        return None
    if s in CARDINAL_DEG:
        return CARDINAL_DEG[s]
    try:
        return float(s) % 360.0
    except ValueError:
        return None


def oneway_value(tags):
    """'yes' (traffic runs along the way as drawn), '-1' (against), or 'no'.

    Also applies the two implicit cases OSM treats as one-way without the tag:
    roundabouts and `highway=motorway` (both documented as implying oneway=yes).
    Everything else without an explicit tag is reported as 'no' — guessing here
    would cost a real warning.
    """
    ow = str(tags.get("oneway", "")).strip().lower()
    if ow in ("yes", "true", "1"):
        return "yes"
    if ow in ("-1", "reverse", "backward"):
        return "-1"
    if ow in ("no", "false", "0"):
        return "no"
    if str(tags.get("junction", "")).strip().lower() in ("roundabout", "circular"):
        return "yes"
    if str(tags.get("highway", "")).strip().lower() == "motorway":
        return "yes"
    return "no"


def bearing_deg(lat1, lon1, lat2, lon2):
    """Initial compass bearing (deg, 0=N, clockwise) from point 1 to point 2."""
    dlon = math.radians(lon2 - lon1)
    la1 = math.radians(lat1)
    la2 = math.radians(lat2)
    y = math.sin(dlon) * math.cos(la2)
    x = math.cos(la1) * math.sin(la2) - math.sin(la1) * math.cos(la2) * math.cos(dlon)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def resolve_traffic_deg(direction, way_deg, oneway):
    """Bearing of the traffic a camera monitors, or None when undeterminable.

    None is the honest answer in two common cases — a camera not on any way, and
    a camera on a two-way road with only a compass `direction` (facing cannot be
    mapped to a carriageway) — and the app treats None as "warn anyway".
    """
    if way_deg is None:
        return None
    d = str(direction).strip().lower() if direction else ""
    if d == "forward":
        return way_deg % 360.0
    if d == "backward":
        return (way_deg + 180.0) % 360.0
    if d == "both":
        return None                       # monitors both directions: always warn
    if oneway == "yes":
        return way_deg % 360.0            # one-way carriageway: unambiguous
    if oneway == "-1":
        return (way_deg + 180.0) % 360.0
    return None


def scan_way_for_cameras(w, tags, cam_ids, cam_way):
    """Record each speed-camera node's parent-way bearing and oneway status.

    Called from the way phase (nodes always precede ways in a .pbf, so cam_ids is
    complete by then). Deliberately cheap: a set lookup per node ref and, only on
    a hit, two coordinate reads — no geometry list is built, because doing that
    for every highway way is what made dense extracts crawl.

    An entry already resolved from a one-way parent is not overwritten by a
    later two-way parent, so the most informative parent wins.
    """
    if not cam_ids or "highway" not in tags:
        return
    nodes = w.nodes
    n = len(nodes)
    if n < 2:
        return
    ow = oneway_value(tags)
    for i in range(n):
        ref = nodes[i].ref
        if ref not in cam_ids:
            continue
        prev = cam_way.get(ref)
        if prev is not None and prev[1] in ("yes", "-1"):
            continue
        j = i - 1 if i > 0 else i
        k = i + 1 if i < n - 1 else i
        if j == k:
            continue
        a, b = nodes[j], nodes[k]
        try:
            if not (a.location.valid() and b.location.valid()):
                continue
            cam_way[ref] = (bearing_deg(a.lat, a.lon, b.lat, b.lon), ow)
        except Exception:
            continue


def camera_rows_resolved(cam_rows, cam_way):
    """Append (wayDeg, oneway, trafficDeg) to each 10-column camera row.

    Returns (rows13, resolved_count) — the count is how many cameras ended up
    with a usable monitored-traffic bearing, which is worth printing.
    """
    out = []
    resolved = 0
    for r in cam_rows:
        way_deg, ow = cam_way.get(r[0], (None, None))
        td = resolve_traffic_deg(r[4], way_deg, ow)
        if td is not None:
            resolved += 1
        out.append(tuple(r) + (way_deg, ow, td))
    return out, resolved


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
  charge   TEXT,          -- tariff string when mapped (e.g. "EUR 2.00/hour")
  fee_cond TEXT,          -- fee:conditional (time-based variants)
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
  minLat REAL, maxLat REAL, minLon REAL, maxLon REAL,
  -- v6: resolved direction (see "camera direction resolution" above)
  wayDeg     REAL,       -- bearing of the parent highway at the camera, as drawn
  oneway     TEXT,       -- 'yes' | '-1' | 'no' for that way; NULL if not on one
  trafficDeg REAL        -- bearing of the MONITORED traffic; NULL = cannot tell
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

def run(pbf, db_path, progress_cb=None):
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
        # forward the latest progress line to a dashboard, if one is listening
        if progress_cb is not None:
            try:
                progress_cb("parking", text)
            except Exception:
                pass

    def _finish_line():
        # Terminate the current overwriting line so following output is clean.
        if _pw["max"] > 0:
            _sys.stdout.write("\n")
            _sys.stdout.flush()
            _pw["max"] = 0

    # The speed-limit stage (osm_to_speedlimitdb) scanned this same .pbf first and
    # recorded BOTH the node and way totals in the DB's meta table. We scan the
    # SAME file, so both are valid denominators — read them back so each phase
    # (nodes, then ways) shows a real percentage.
    total_nodes = 0
    total_ways = 0
    try:
        for key, setter in (("total_nodes", "n"), ("total_ways", "w")):
            row = con.execute(
                "SELECT value FROM meta WHERE key=?", (key,)).fetchone()
            if row:
                if setter == "n":
                    total_nodes = int(row[0])
                else:
                    total_ways = int(row[0])
    except (sqlite3.Error, ValueError):
        total_nodes = total_ways = 0

    progress = {"nodes": 0, "ways": 0, "t0": _time.time(),
                "last_t": _time.time(), "last_n": 0,
                "total_all": total_nodes + total_ways}
    try:
        _mb = os.path.getsize(pbf) / 1e6
    except OSError:
        _mb = 0.0
    if progress["total_all"] > 0:
        # Stage 1 counted the whole file, so we show ONE continuous percentage
        # over the entire scan: numerator = nodes+ways processed so far,
        # denominator = total_nodes + total_ways. It climbs monotonically 0->100%
        # (nodes carry it up first, then ways finish it) with no mid-scan reset.
        print(f"  scanning {pbf} ({_mb:.0f} MB, "
              f"{progress['total_all']:,} elements) for parking + cameras — "
              f"% is over the whole scan…", flush=True)
    else:
        # No stored totals (e.g. base stage was skipped) — fall back to counts+rate.
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
        # One continuous percentage over the whole scan (nodes + ways), using the
        # combined total from stage 1. Clamped to 100 in case counts differ
        # slightly from stage 1 (e.g. relations, which we don't count here).
        if progress["total_all"] > 0:
            raw = 100.0 * total / progress["total_all"]
            pct = f"{min(raw, 100.0):4.0f}%"
        else:
            pct = "    "     # blank slot keeps columns aligned when total unknown
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
            # camera node ids, and id -> (parent way bearing, oneway), filled in
            # the way phase so each camera's monitored direction can be resolved.
            self.cam_ids = set()
            self.cam_way = {}

        def _lot(self, o, coords):
            info = lot_tags(dict(o.tags))
            if info is None or not coords:
                return
            mnla, mxla, mnlo, mxlo = bbox(coords)
            self.lot_rows.append((
                o.id, info["name"], info["kind"], info["access"], info["fee"],
                info["capacity"], info["maxstay"], info["hours"],
                info["charge"], info["fee_cond"],
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
                self.cam_ids.add(n.id)
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

            # Resolve the carriageway direction of any camera sitting on this way
            # (cheap; skipped entirely for non-highway ways).
            scan_way_for_cameras(w, tags, self.cam_ids, self.cam_way)

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
    _finish_line()   # end the overwriting progress line before the summary

    total = progress["nodes"] + progress["ways"]
    elapsed = _time.time() - progress["t0"]
    print(f"Scan complete: {total:,} elements in {elapsed:.0f}s. "
          f"Writing tables to the database…", flush=True)

    con.executemany(
        "INSERT INTO parking_curb VALUES(" + ",".join("?" * 17) + ")", h.curb_rows)
    con.executemany(
        "INSERT INTO parking_lot VALUES(" + ",".join("?" * 16) + ")", h.lot_rows)
    cam_rows13, cam_resolved = camera_rows_resolved(h.cam_rows, h.cam_way)
    con.executemany(
        "INSERT INTO speed_camera VALUES(" + ",".join("?" * 13) + ")", cam_rows13)
    set_meta(con, "parking_curb_rows", stats["curb"])
    set_meta(con, "parking_lot_rows", stats["lot"])
    set_meta(con, "speed_camera_rows", stats["cam"])
    set_meta(con, "speed_camera_directed", cam_resolved)
    set_meta(con, "schema_version", SCHEMA_VERSION)
    con.commit()
    print("Database written.\n", flush=True)

    print(f"parking_lot  : {stats['lot']:>7} features")
    print(f"parking_curb : {stats['curb']:>7} side-records")
    print(f"speed_camera : {stats['cam']:>7} cameras "
          f"({cam_resolved} with a resolved travel direction)")
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
    try:
        main()
    except KeyboardInterrupt:
        # Gentle stop on Ctrl+C: no traceback. Any partial DB is left for
        # build_europe to clean/redo; a standalone run just exits.
        import sys as _sys
        print("\nInterrupted by user (Ctrl+C) — stopped.", file=_sys.stderr)
        _sys.exit(130)
