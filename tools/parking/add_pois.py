#!/usr/bin/env python3
"""
add_pois.py — companion to add_parking.py. Extracts additional points of
interest from a Geofabrik .osm.pbf into new tables in the region .db, so the
navigation map can display them (see PoiType in the app):

    fuel        amenity=fuel                (petrol / service stations)
    charging    amenity=charging_station    (EV charging)
    hospital    amenity=hospital            (hospitals; clinics optional)
    rest_area   highway=rest_area | services + highway=services

    python add_pois.py --pbf greece-latest.osm.pbf --db greece.db

Each POI is stored as a single point (lat, lon) plus a small indexed bbox, the
same shape OsmMap.overlayNear() already uses for cameras — so wiring the app
side is just: flip PoiType.available, add a query per table, add a layer+icon.

Node POIs use the node location; way/area POIs (a hospital campus, a car park
building) use the centroid of their nodes, so everything is a point.

This ADDS tables and never touches the existing speed-limit / parking / camera
tables. Safe to run after osm_to_speedlimitdb.py + add_parking.py on the same db.

Requires: pyosmium  (pip install osmium)
"""
import argparse
import os
import sqlite3
import sys

COORD_SCALE = 1e7   # unused here (points only) but kept for parity

# --- tag classifiers (pure functions, unit-testable, no osmium types) -------

def fuel_tags(tags):
    if tags.get("amenity") != "fuel":
        return None
    return {"name": tags.get("name"),
            "brand": tags.get("brand"),
            "operator": tags.get("operator"),
            # OSM has no live fuel PRICES (they change daily and aren't mapped),
            # but opening hours and the available fuel types are useful and stable.
            "hours": tags.get("opening_hours"),
            "fuel_types": _fuel_types(tags)}


def _fuel_types(tags):
    """Comma-list of the fuel:* types a station advertises (diesel, octane_95…)."""
    out = []
    for k, v in tags.items():
        if k.startswith("fuel:") and str(v).lower() in ("yes", "true", "1"):
            out.append(k.split(":", 1)[1])
    return ", ".join(sorted(out)) if out else None


def charging_tags(tags):
    if tags.get("amenity") != "charging_station":
        return None
    return {"name": tags.get("name") or tags.get("operator"),
            "capacity": _to_int(tags.get("capacity")),
            "network": tags.get("network"),
            # "fee" is usually yes/no; "charge" carries a tariff string when the
            # mapper recorded one (e.g. "0.79 EUR/kWh"). Both are stable enough
            # to store, unlike live per-session pricing.
            "fee": tags.get("fee"),
            "charge": tags.get("charge"),
            "socket": _sockets(tags),
            "hours": tags.get("opening_hours")}


def _sockets(tags):
    """Comma-list of socket types/counts, e.g. "type2:4, ccs:2"."""
    out = []
    for k, v in tags.items():
        if k.startswith("socket:") and ":output" not in k:
            name = k.split(":", 1)[1]
            out.append(f"{name}:{v}" if str(v).isdigit() else name)
    return ", ".join(sorted(out)) if out else None


def hospital_tags(tags, include_clinics=False):
    a = tags.get("amenity")
    if a == "hospital" or (include_clinics and a == "clinic"):
        return {"name": tags.get("name"), "emergency": tags.get("emergency"),
                "kind": a}
    return None


def rest_area_tags(tags):
    # motorway rest areas: highway=rest_area, or highway=services (service area)
    h = tags.get("highway")
    if h in ("rest_area", "services"):
        return {"name": tags.get("name"), "kind": h}
    return None


def level_crossing_tags(tags):
    # Railway level crossing (road crosses rail at grade) — a safety warning POI.
    if tags.get("railway") == "level_crossing":
        return {"name": tags.get("name"),
                "barrier": tags.get("crossing:barrier"),
                "light": tags.get("crossing:light")}
    return None


def speed_bump_tags(tags):
    # Traffic calming: bump/hump/table/cushion/etc. — a safety warning POI.
    tc = tags.get("traffic_calming")
    if tc:
        return {"kind": tc}
    return None


def toll_booth_tags(tags):
    # Toll booth / toll gantry — advance warning to have payment ready.
    if tags.get("barrier") == "toll_booth":
        return {"name": tags.get("name")}
    return None


def border_control_tags(tags):
    # Border crossing control — useful on international routes.
    if tags.get("barrier") == "border_control":
        return {"name": tags.get("name")}
    return None


def _to_int(v):
    try:
        return int(str(v).split()[0]) if v else None
    except (ValueError, IndexError):
        return None


# --- database ---------------------------------------------------------------

DDL = """
DROP TABLE IF EXISTS fuel;
CREATE TABLE fuel(
  id INTEGER, name TEXT, brand TEXT, operator TEXT,
  hours TEXT, fuel_types TEXT,
  lat REAL, lon REAL,
  minLat REAL, maxLat REAL, minLon REAL, maxLon REAL
);
CREATE INDEX idx_fuel_bbox ON fuel(minLat, maxLat);

DROP TABLE IF EXISTS charging;
CREATE TABLE charging(
  id INTEGER, name TEXT, capacity INTEGER, network TEXT,
  fee TEXT, charge TEXT, socket TEXT, hours TEXT,
  lat REAL, lon REAL,
  minLat REAL, maxLat REAL, minLon REAL, maxLon REAL
);
CREATE INDEX idx_charging_bbox ON charging(minLat, maxLat);

DROP TABLE IF EXISTS hospital;
CREATE TABLE hospital(
  id INTEGER, name TEXT, emergency TEXT, kind TEXT,
  lat REAL, lon REAL,
  minLat REAL, maxLat REAL, minLon REAL, maxLon REAL
);
CREATE INDEX idx_hospital_bbox ON hospital(minLat, maxLat);

DROP TABLE IF EXISTS rest_area;
CREATE TABLE rest_area(
  id INTEGER, name TEXT, kind TEXT,
  lat REAL, lon REAL,
  minLat REAL, maxLat REAL, minLon REAL, maxLon REAL
);
CREATE INDEX idx_rest_bbox ON rest_area(minLat, maxLat);

DROP TABLE IF EXISTS level_crossing;
CREATE TABLE level_crossing(
  id INTEGER, name TEXT, barrier TEXT, light TEXT,
  lat REAL, lon REAL,
  minLat REAL, maxLat REAL, minLon REAL, maxLon REAL
);
CREATE INDEX idx_levelx_bbox ON level_crossing(minLat, maxLat);

DROP TABLE IF EXISTS speed_bump;
CREATE TABLE speed_bump(
  id INTEGER, kind TEXT,
  lat REAL, lon REAL,
  minLat REAL, maxLat REAL, minLon REAL, maxLon REAL
);
CREATE INDEX idx_bump_bbox ON speed_bump(minLat, maxLat);

DROP TABLE IF EXISTS toll_booth;
CREATE TABLE toll_booth(
  id INTEGER, name TEXT,
  lat REAL, lon REAL,
  minLat REAL, maxLat REAL, minLon REAL, maxLon REAL
);
CREATE INDEX idx_toll_bbox ON toll_booth(minLat, maxLat);

DROP TABLE IF EXISTS border_control;
CREATE TABLE border_control(
  id INTEGER, name TEXT,
  lat REAL, lon REAL,
  minLat REAL, maxLat REAL, minLon REAL, maxLon REAL
);
CREATE INDEX idx_border_bbox ON border_control(minLat, maxLat);
"""

# ~22 m half-box so the indexed range query reliably finds a point
BBOX_M = 0.0002


def open_db(path):
    if not os.path.exists(path):
        sys.exit(f"db not found: {path} (run osm_to_speedlimitdb.py first)")
    con = sqlite3.connect(path)
    # ensure meta table exists (created by the speed-limit stage)
    con.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)")
    con.executescript(DDL)
    return con


def set_meta(con, key, value):
    con.execute(
        "INSERT INTO meta(key,value) VALUES(?,?) "
        "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        (key, str(value)))


def _bbox_point(lat, lon):
    return (lat - BBOX_M, lat + BBOX_M, lon - BBOX_M, lon + BBOX_M)


# --- extraction (needs osmium) ----------------------------------------------

def run(pbf, db_path, include_clinics=False, progress_cb=None):
    try:
        import osmium
    except ImportError:
        sys.exit("pyosmium is required for extraction: pip install osmium")

    if not os.path.exists(pbf):
        sys.exit(f"pbf not found: {pbf}")

    con = open_db(db_path)
    stats = {"fuel": 0, "charging": 0, "hospital": 0, "rest_area": 0,
             "level_crossing": 0, "speed_bump": 0, "toll_booth": 0,
             "border_control": 0}
    progress = {"nodes": 0, "ways": 0, "areas": 0}

    # The exact node total is already in the DB meta (total_nodes), written by the
    # base speed-limit stage and also used by add_parking — so the POI percentage
    # can be exact rather than estimated. Fall back to a file-size estimate only if
    # that meta value is missing (e.g. an older .db). Nodes come first in a PBF and
    # their phase dominates the runtime; the shown % is capped at 99 until the scan
    # actually finishes.
    total_nodes = 0
    try:
        row = con.execute("SELECT value FROM meta WHERE key='total_nodes'").fetchone()
        if row:
            total_nodes = int(row[0])
    except (sqlite3.Error, ValueError):
        total_nodes = 0
    if total_nodes <= 0:
        try:
            pbf_bytes = os.path.getsize(pbf)
        except OSError:
            pbf_bytes = 0
        total_nodes = max(1, int(pbf_bytes / 7.5))   # ~7 bytes/node fallback
        _exact = False
    else:
        _exact = True
    est_nodes = max(1, total_nodes)

    def _emit(kind):
        if progress_cb is not None:
            try:
                pct = min(99, int(progress["nodes"] * 100 / est_nodes))
                mark = "" if _exact else "~"   # ~ only when using the estimate
                progress_cb("pois", f"{kind:<5s}: {mark}{pct:3d}%  "
                            f"n={progress['nodes']:>10,} "
                            f"w={progress['ways']:>10,} "
                            f"a={progress['areas']:>8,} "
                            f"fuel={stats['fuel']} chg={stats['charging']} "
                            f"hosp={stats['hospital']} rest={stats['rest_area']} "
                            f"xing={stats['level_crossing']} "
                            f"bump={stats['speed_bump']} "
                            f"toll={stats['toll_booth']} "
                            f"bord={stats['border_control']}")
            except Exception:
                pass

    fuel_rows, chg_rows, hosp_rows, rest_rows = [], [], [], []
    levelx_rows, bump_rows, toll_rows, border_rows = [], [], [], []

    def _classify(osm_id, lat, lon, tags):
        f = fuel_tags(tags)
        if f is not None:
            fuel_rows.append((osm_id, f["name"], f["brand"], f["operator"],
                              f["hours"], f["fuel_types"],
                              lat, lon, *_bbox_point(lat, lon)))
            stats["fuel"] += 1
        c = charging_tags(tags)
        if c is not None:
            chg_rows.append((osm_id, c["name"], c["capacity"], c["network"],
                             c["fee"], c["charge"], c["socket"], c["hours"],
                             lat, lon, *_bbox_point(lat, lon)))
            stats["charging"] += 1
        ho = hospital_tags(tags, include_clinics)
        if ho is not None:
            hosp_rows.append((osm_id, ho["name"], ho["emergency"], ho["kind"],
                              lat, lon, *_bbox_point(lat, lon)))
            stats["hospital"] += 1
        ra = rest_area_tags(tags)
        if ra is not None:
            rest_rows.append((osm_id, ra["name"], ra["kind"],
                              lat, lon, *_bbox_point(lat, lon)))
            stats["rest_area"] += 1
        lx = level_crossing_tags(tags)
        if lx is not None:
            levelx_rows.append((osm_id, lx["name"], lx["barrier"], lx["light"],
                                lat, lon, *_bbox_point(lat, lon)))
            stats["level_crossing"] += 1
        sb = speed_bump_tags(tags)
        if sb is not None:
            bump_rows.append((osm_id, sb["kind"],
                              lat, lon, *_bbox_point(lat, lon)))
            stats["speed_bump"] += 1
        tb = toll_booth_tags(tags)
        if tb is not None:
            toll_rows.append((osm_id, tb["name"],
                              lat, lon, *_bbox_point(lat, lon)))
            stats["toll_booth"] += 1
        bc = border_control_tags(tags)
        if bc is not None:
            border_rows.append((osm_id, bc["name"],
                                lat, lon, *_bbox_point(lat, lon)))
            stats["border_control"] += 1

    def _centroid(coords):
        n = len(coords)
        return (sum(c[0] for c in coords) / n, sum(c[1] for c in coords) / n)

    class Handler(osmium.SimpleHandler):
        def node(self, n):
            progress["nodes"] += 1
            if progress["nodes"] % 50_000 == 0:
                _emit("nodes")
            if not n.location.valid():
                return
            if len(n.tags) == 0:      # skip untagged geometry nodes (the majority)
                return
            _classify(n.id, n.location.lat, n.location.lon, dict(n.tags))

        def way(self, w):
            progress["ways"] += 1
            if progress["ways"] % 25_000 == 0:
                _emit("ways")
            if len(w.tags) == 0:
                return
            tags = dict(w.tags)
            # only bother computing a centroid if the tags actually match
            if not (fuel_tags(tags) or charging_tags(tags)
                    or hospital_tags(tags, include_clinics) or rest_area_tags(tags)
                    or level_crossing_tags(tags) or speed_bump_tags(tags)
                    or toll_booth_tags(tags) or border_control_tags(tags)):
                return
            try:
                coords = [(nd.lat, nd.lon) for nd in w.nodes if nd.location.valid()]
            except osmium.InvalidLocationError:
                return
            if not coords:
                return
            lat, lon = _centroid(coords)
            _classify(w.id, lat, lon, tags)

    # Need node locations resolved for way centroids.
    h = Handler()
    h.apply_file(pbf, locations=True)

    con.executemany(
        "INSERT INTO fuel VALUES(" + ",".join("?" * 12) + ")", fuel_rows)
    con.executemany(
        "INSERT INTO charging VALUES(" + ",".join("?" * 14) + ")", chg_rows)
    con.executemany(
        "INSERT INTO hospital VALUES(" + ",".join("?" * 10) + ")", hosp_rows)
    con.executemany(
        "INSERT INTO rest_area VALUES(" + ",".join("?" * 9) + ")", rest_rows)
    con.executemany(
        "INSERT INTO level_crossing VALUES(" + ",".join("?" * 10) + ")", levelx_rows)
    con.executemany(
        "INSERT INTO speed_bump VALUES(" + ",".join("?" * 8) + ")", bump_rows)
    con.executemany(
        "INSERT INTO toll_booth VALUES(" + ",".join("?" * 8) + ")", toll_rows)
    con.executemany(
        "INSERT INTO border_control VALUES(" + ",".join("?" * 8) + ")", border_rows)

    for k, v in stats.items():
        set_meta(con, f"poi_{k}_count", v)
    set_meta(con, "poi_extracted", "1")   # marker: this db has the extra POIs
    con.commit()
    con.close()

    _emit("done")
    print(f"add_pois: fuel={stats['fuel']} charging={stats['charging']} "
          f"hospital={stats['hospital']} rest_area={stats['rest_area']} "
          f"level_crossing={stats['level_crossing']} "
          f"speed_bump={stats['speed_bump']} toll_booth={stats['toll_booth']} "
          f"border_control={stats['border_control']}")
    return stats


def main():
    ap = argparse.ArgumentParser(description="Extract extra POIs into a region .db")
    ap.add_argument("--pbf", required=True, help="Geofabrik .osm.pbf source")
    ap.add_argument("--db", required=True, help="region .db to add tables to")
    ap.add_argument("--clinics", action="store_true",
                    help="also include amenity=clinic as hospitals")
    args = ap.parse_args()
    run(args.pbf, args.db, include_clinics=args.clinics)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        # Gentle stop on Ctrl+C: no traceback. Any partial DB is left for
        # build_europe to clean/redo; a standalone run just exits.
        import sys as _sys
        print("\nInterrupted by user (Ctrl+C) — stopped.", file=_sys.stderr)
        _sys.exit(130)
