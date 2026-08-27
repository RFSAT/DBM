#!/usr/bin/env python3
"""
osm_to_speedlimitdb.py — pre-process an OSM extract into a compact, spatially
indexed SQLite database for the DBM on-device speed-limit fusion.

This runs OFF-DEVICE (on a desktop/server), once per region. It converts a raw
OSM extract (~100 MB of XML/PBF for a region) into a small .db (a few MB) that
the phone downloads and queries directly via an R-tree spatial index — no XML
parsing or whole-network-in-memory on the phone.

It keeps ONLY what the fuser needs:
  - highway road segments: geometry (polyline) + maxspeed (km/h, -1 if untagged)
Everything else in OSM (buildings, POIs, unused tags) is discarded.

DATABASE SCHEMA (queried by the Android OsmMap SQLite backend):
  meta(key TEXT PRIMARY KEY, value TEXT)         -- region name, bbox, counts
  segments(id INTEGER PRIMARY KEY,               -- road segments
           maxspeed INTEGER,                      -- km/h, -1 if untagged
           coords BLOB)                           -- packed lat/lon pairs (float64)
  segments also store bbox columns (minLat,maxLat,minLon,maxLon) + an index,
  used for spatial lookup (Android SQLite has no rtree module).

  The phone queries the bbox columns for segments whose bounding box is near the GPS
  point, then reads only those few segments' coords and applies the validated
  heading/hysteresis matching — instead of looping over the whole region.

USAGE
  pip install osmium            # for .pbf/.osm (fast, streaming)
  python3 osm_to_speedlimitdb.py attica.osm.pbf attica.db --region "Attica"

  Then host the .db on the server (public folder) for the app to download.

If osmium is unavailable, a slower pure-Python .osm XML path is used (--xml).
"""

import argparse
import os
import sqlite3
import struct
import sys
import re


# ---- implicit-default speed limits (km/h) for untagged roads, by highway type
# Resolves common cases so untagged main roads still get a sensible baseline.
# Conservative: only applied where the highway class strongly implies a limit.
IMPLICIT_DEFAULTS = {
    "motorway": 130, "motorway_link": 80,
    "trunk": 110, "trunk_link": 80,
    "primary": 90, "secondary": 90, "tertiary": 70,
    "residential": 50, "living_street": 20,
    "unclassified": 50, "service": 30,
}


def parse_maxspeed(value):
    """OSM maxspeed tag -> km/h int, or None."""
    if not value:
        return None
    m = re.search(r"\d+", value)
    if not m:
        return None
    num = int(m.group())
    if "mph" in value.lower():
        num = round(num * 1.60934)
    return num


COORD_SCALE = 1e7   # degrees -> int32 (7 dp, ~1 cm; finer than GPS)

# Road types we never drive-monitor on: no speed limit, not driveable. Dropping
# them removes rows and geometry with zero loss to the fusion.
SKIP_HIGHWAY = {
    "footway", "cycleway", "path", "steps", "pedestrian", "bridleway",
    "corridor", "platform", "construction", "proposed", "raceway", "bus_guideway",
}


def pack_coords(coords):
    """List of (lat, lon) -> packed int32 blob (scaled by COORD_SCALE).
    Halves geometry vs float64; 1 cm precision, far finer than GPS."""
    out = bytearray()
    for lat, lon in coords:
        out += struct.pack("<ii", round(lat * COORD_SCALE), round(lon * COORD_SCALE))
    return bytes(out)


def simplify(coords, tol_m=2.0):
    """Douglas-Peucker polyline simplification. Removes near-collinear points
    (a straight road stored with many redundant vertices) with a metre
    tolerance, cutting geometry size with no meaningful shape change for
    matching. Returns the simplified coordinate list."""
    import math
    if len(coords) <= 2:
        return coords
    lat0 = coords[len(coords) // 2][0]
    mPerLat = 111320.0
    mPerLon = 111320.0 * max(0.1, abs(math.cos(lat0 * math.pi / 180.0)))

    def perp(p, a, b):
        ax, ay = a[1] * mPerLon, a[0] * mPerLat
        bx, by = b[1] * mPerLon, b[0] * mPerLat
        px, py = p[1] * mPerLon, p[0] * mPerLat
        dx, dy = bx - ax, by - ay
        den = dx * dx + dy * dy
        if den < 1e-9:
            return ((px - ax) ** 2 + (py - ay) ** 2) ** 0.5
        t = ((px - ax) * dx + (py - ay) * dy) / den
        t = max(0.0, min(1.0, t))
        cx, cy = ax + t * dx, ay + t * dy
        return ((px - cx) ** 2 + (py - cy) ** 2) ** 0.5

    keep = [False] * len(coords)
    keep[0] = keep[-1] = True
    stack = [(0, len(coords) - 1)]
    while stack:
        i, j = stack.pop()
        if j <= i + 1:
            continue
        dmax, idx = 0.0, -1
        for k in range(i + 1, j):
            d = perp(coords[k], coords[i], coords[j])
            if d > dmax:
                dmax, idx = d, k
        if dmax > tol_m and idx != -1:
            keep[idx] = True
            stack.append((i, idx)); stack.append((idx, j))
    return [c for c, k in zip(coords, keep) if k]


def build_db(db_path, region, segments, bbox):
    """segments: list of (maxspeed:int, coords:list[(lat,lon)])."""
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.executescript("""
        PRAGMA journal_mode=OFF;
        DROP TABLE IF EXISTS meta;
        DROP TABLE IF EXISTS segments;
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE segments(id INTEGER PRIMARY KEY, maxspeed INTEGER,
            minLat REAL, maxLat REAL, minLon REAL, maxLon REAL, coords BLOB);
        CREATE INDEX idx_seg_lat ON segments(minLat, maxLat);
    """)

    tagged = 0
    pts_before = 0
    pts_after = 0
    next_id = 1
    total = len(segments)
    import time as _time
    _t0 = _time.time()
    print(f"  building DB: simplifying + inserting {total:,} segments…", flush=True)
    for (maxspeed, coords) in segments:
        if len(coords) < 2:
            continue
        pts_before += len(coords)
        coords = simplify(coords, tol_m=2.0)
        pts_after += len(coords)
        lats = [c[0] for c in coords]
        lons = [c[1] for c in coords]
        cur.execute("""INSERT INTO segments(id,maxspeed,minLat,maxLat,minLon,maxLon,coords)
                       VALUES(?,?,?,?,?,?,?)""",
                    (next_id, maxspeed, min(lats), max(lats), min(lons), max(lons),
                     pack_coords(coords)))
        if maxspeed > 0:
            tagged += 1
        next_id += 1
        if next_id % 100_000 == 0:
            pct = 100.0 * next_id / total if total else 0
            print(f"    [{_time.time() - _t0:5.0f}s] inserted {next_id:>9,} / "
                  f"{total:,} ({pct:.0f}%)", flush=True)

    if pts_before:
        print(f"  simplified geometry: {pts_before:,} -> {pts_after:,} points "
              f"({100*pts_after/pts_before:.0f}% kept)")

    meta = {
        "region": region,
        "bbox": ",".join(f"{v:.6f}" for v in bbox) if bbox else "",
        "segments": str(cur.execute("SELECT COUNT(*) FROM segments").fetchone()[0]),
        "with_maxspeed": str(tagged),
        "schema_version": "3",
        "coord_encoding": "int32_1e7",
    }
    cur.executemany("INSERT INTO meta(key,value) VALUES(?,?)", list(meta.items()))
    conn.commit()
    # compact the file
    print("  compacting database (VACUUM)…", flush=True)
    cur.execute("VACUUM")
    conn.commit()
    conn.close()
    print(f"wrote {db_path}: {meta['segments']} segments "
          f"({tagged} with maxspeed) for region '{region}'")


def read_with_osmium(osm_path, drop_untagged_minor=False):
    """Fast streaming read of .osm/.pbf via osmium. Yields (maxspeed, coords)."""
    import osmium
    import time

    file_mb = 0.0
    try:
        file_mb = os.path.getsize(osm_path) / 1e6
    except OSError:
        pass

    # Note on progress: osmium streams the file and gives no reliable up-front
    # element count, so we don't show a percentage here. Instead each line shows
    # elapsed time, running node/way counts, roads kept and the processing rate —
    # which always works and gives a clear feel for how far the scan has gone.
    # (Nodes stream before ways in a PBF, so ways stay 0 during the node phase.)
    print(f"  reading {osm_path} ({file_mb:.0f} MB) — scanning; progress every "
          f"200k elements (elapsed / counts / rate)…", flush=True)

    class Handler(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.drop_untagged_minor = drop_untagged_minor
            self.segments = []
            self.minlat = self.minlon = 1e9
            self.maxlat = self.maxlon = -1e9
            self.nodes_seen = 0
            self.ways_seen = 0
            self.t0 = time.time()
            self.last_t = self.t0

        def _tick(self, kind):
            now = time.time()
            dt = now - self.last_t
            rate = 200_000 / dt if dt > 0 else 0
            print(f"    [{now - self.t0:5.0f}s] {kind:5s} "
                  f"nodes={self.nodes_seen:>10,} ways={self.ways_seen:>9,}"
                  f"  | kept roads {len(self.segments):>8,}"
                  f"  | {rate/1000:.0f}k/s", flush=True)
            self.last_t = now

        def node(self, n):
            self.nodes_seen += 1
            if (self.nodes_seen + self.ways_seen) % 200_000 == 0:
                self._tick("nodes")

        def way(self, w):
            self.ways_seen += 1
            if (self.nodes_seen + self.ways_seen) % 200_000 == 0:
                self._tick("ways")
            if "highway" not in w.tags:
                return
            hw = w.tags.get("highway")
            if hw in SKIP_HIGHWAY:
                return
            ms = parse_maxspeed(w.tags.get("maxspeed"))
            if ms is None:
                ms = IMPLICIT_DEFAULTS.get(hw, -1)
            if self.drop_untagged_minor and ms < 0:
                return        # untagged & no implicit default -> drop (optional)
            coords = []
            for n in w.nodes:
                if n.location.valid():
                    la, lo = n.location.lat, n.location.lon
                    coords.append((la, lo))
                    self.minlat = min(self.minlat, la); self.maxlat = max(self.maxlat, la)
                    self.minlon = min(self.minlon, lo); self.maxlon = max(self.maxlon, lo)
            if len(coords) >= 2:
                self.segments.append((ms, coords))

    h = Handler()
    # locations=True resolves node coords for ways in one pass
    h.apply_file(osm_path, locations=True)
    print(f"  scan done: {h.ways_seen:,} ways seen, {len(h.segments):,} road "
          f"segments kept in {time.time() - h.t0:.0f}s", flush=True)
    bbox = (h.minlat, h.minlon, h.maxlat, h.maxlon)
    return h.segments, bbox


def read_with_xml(osm_path, drop_untagged_minor=False):
    """Slower pure-Python .osm XML fallback (no osmium needed)."""
    import xml.etree.ElementTree as ET
    nodes = {}
    segments = []
    minlat = minlon = 1e9
    maxlat = maxlon = -1e9
    # two-pass streaming to keep memory sane
    for event, el in ET.iterparse(osm_path, events=("end",)):
        if el.tag == "node":
            nid = el.get("id")
            la = el.get("lat"); lo = el.get("lon")
            if nid and la and lo:
                nodes[int(nid)] = (float(la), float(lo))
            el.clear()
        elif el.tag == "way":
            is_hw = False
            ms = None
            hw = None
            refs = []
            for c in el:
                if c.tag == "nd":
                    r = c.get("ref")
                    if r:
                        refs.append(int(r))
                elif c.tag == "tag":
                    k = c.get("k"); v = c.get("v")
                    if k == "highway":
                        is_hw = True; hw = v
                    elif k == "maxspeed":
                        ms = parse_maxspeed(v)
            if is_hw and hw not in SKIP_HIGHWAY:
                if ms is None:
                    ms = IMPLICIT_DEFAULTS.get(hw, -1)
                if drop_untagged_minor and ms < 0:
                    el.clear(); continue
                coords = [nodes[r] for r in refs if r in nodes]
                if len(coords) >= 2:
                    segments.append((ms, coords))
                    for la, lo in coords:
                        minlat = min(minlat, la); maxlat = max(maxlat, la)
                        minlon = min(minlon, lo); maxlon = max(maxlon, lo)
            el.clear()
    return segments, (minlat, minlon, maxlat, maxlon)


def main():
    ap = argparse.ArgumentParser(description="OSM extract -> DBM speed-limit DB")
    ap.add_argument("input", help="input .osm / .osm.pbf")
    ap.add_argument("output", help="output .db")
    ap.add_argument("--region", required=True, help="region name (e.g. 'Attica')")
    ap.add_argument("--xml", action="store_true",
                    help="force pure-Python XML reader (no osmium)")
    ap.add_argument("--drop-untagged-minor", action="store_true",
                    help="drop roads with no maxspeed AND no implicit default "
                         "(smaller DB; fusion reports NONE on those roads)")
    args = ap.parse_args()

    if args.xml:
        segments, bbox = read_with_xml(args.input, args.drop_untagged_minor)
    else:
        try:
            segments, bbox = read_with_osmium(args.input, args.drop_untagged_minor)
        except ImportError:
            print("osmium not installed; falling back to XML reader "
                  "(slower; .pbf NOT supported this way).", file=sys.stderr)
            segments, bbox = read_with_xml(args.input, args.drop_untagged_minor)

    if not segments:
        print("no highway segments found — check the input file", file=sys.stderr)
        sys.exit(1)

    build_db(args.output, args.region, segments, bbox)


if __name__ == "__main__":
    main()
