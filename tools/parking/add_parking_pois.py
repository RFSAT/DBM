#!/usr/bin/env python3
r"""
add_parking_pois.py — combined single-pass extractor for parking + speed cameras
AND the extra POIs (fuel / charging / hospital / rest area).

Why this exists: add_parking.py and add_pois.py each make a FULL pass over the
PBF with locations=True (the expensive node-location index build). Running both
means building that index twice. This module does BOTH in ONE pass, roughly
halving the work of the two lightweight feature stages, while the heavy
speed-limit stage stays separate (modularity + its own top-up flow preserved).

CRITICAL: this module DOES NOT reimplement any classification. It IMPORTS the
pure tag-classifiers, DDL, bbox/pack helpers and meta conventions from
add_parking.py and add_pois.py, so there is a single source of truth and the
combined pass produces byte-for-byte the same tables the two standalone modules
would. add_parking.run() and add_pois.run() remain fully usable on their own
(e.g. the POI top-up path in build_europe.py still calls add_pois.run()).

    python add_parking_pois.py --pbf greece-latest.osm.pbf --db greece.db

Requires: pyosmium (pip install osmium), and add_parking.py + add_pois.py beside
this file.
"""
import argparse
import os
import sqlite3
import sys
import time as _time

import add_parking as ap
import add_pois as ap2


def run(pbf, db_path, include_clinics=False, progress_cb=None):
    """Single pass: parking lots + curb + speed cameras + fuel/charging/hospital/
    rest area. Same outputs as add_parking.run() followed by add_pois.run()."""
    try:
        import osmium
    except ImportError:
        sys.exit("pyosmium is required for extraction: pip install osmium")
    if not os.path.exists(pbf):
        sys.exit(f"pbf not found: {pbf}")

    con = sqlite3.connect(db_path)
    # Create BOTH modules' tables. Reusing their DDL keeps schemas identical.
    con.executescript(ap.DDL)      # parking_curb / parking_lot / speed_camera
    con.executescript(ap2.DDL)     # fuel / charging / hospital / rest_area
    con.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)")

    # exact node total (written by the base stage) for a real percentage
    total_nodes = 0
    try:
        row = con.execute(
            "SELECT value FROM meta WHERE key='total_nodes'").fetchone()
        if row:
            total_nodes = int(row[0])
    except (sqlite3.Error, ValueError):
        total_nodes = 0
    est_nodes = max(1, total_nodes) if total_nodes > 0 else \
        max(1, int((os.path.getsize(pbf) if os.path.exists(pbf) else 0) / 7.5))
    exact = total_nodes > 0

    stats = {"lot": 0, "curb": 0, "cam": 0,
             "fuel": 0, "charging": 0, "hospital": 0, "rest_area": 0,
             "level_crossing": 0, "speed_bump": 0, "toll_booth": 0,
             "border_control": 0}
    progress = {"nodes": 0, "ways": 0}
    curb_rows, lot_rows, cam_rows = [], [], []
    # camera node ids, and id -> (parent way bearing, oneway): filled in the way
    # phase so each camera's monitored travel direction can be resolved (v6).
    cam_ids, cam_way = set(), {}
    fuel_rows, chg_rows, hosp_rows, rest_rows = [], [], [], []
    levelx_rows, bump_rows, toll_rows, border_rows = [], [], [], []

    def _emit(kind):
        if progress_cb is None:
            return
        pct = min(99, int(progress["nodes"] * 100 / est_nodes))
        mark = "" if exact else "~"
        try:
            progress_cb("parking+pois",
                        f"{kind:<5s}: {mark}{pct:3d}%  "
                        f"n={progress['nodes']:>10,} "
                        f"w={progress['ways']:>10,}  "
                        f"lots={stats['lot']} curb={stats['curb']} "
                        f"cams={stats['cam']} dir={len(cam_way)} "
                        f"fuel={stats['fuel']} "
                        f"chg={stats['charging']} hosp={stats['hospital']} "
                        f"rest={stats['rest_area']} "
                        f"xing={stats['level_crossing']} bump={stats['speed_bump']} "
                        f"toll={stats['toll_booth']} bord={stats['border_control']}")
        except Exception:
            pass

    M = 0.0002   # ~22 m point bbox (same as both modules)

    def _lot_from_tags(osm_id, tags, coords):
        info = ap.lot_tags(tags)
        if info is None or not coords:
            return
        mnla, mxla, mnlo, mxlo = ap.bbox(coords)
        lot_rows.append((
            osm_id, info["name"], info["kind"], info["access"], info["fee"],
            info["capacity"], info["maxstay"], info["hours"],
            info["charge"], info["fee_cond"],
            sum(c[0] for c in coords) / len(coords),
            sum(c[1] for c in coords) / len(coords),
            mnla, mxla, mnlo, mxlo))
        stats["lot"] += 1

    def _pois(osm_id, lat, lon, tags):
        # reuse add_pois classifiers verbatim
        f = ap2.fuel_tags(tags)
        if f is not None:
            fuel_rows.append((osm_id, f["name"], f["brand"], f["operator"],
                              f["hours"], f["fuel_types"],
                              lat, lon, *ap2._bbox_point(lat, lon)))
            stats["fuel"] += 1
        c = ap2.charging_tags(tags)
        if c is not None:
            chg_rows.append((osm_id, c["name"], c["capacity"], c["network"],
                             c["fee"], c["charge"], c["socket"], c["hours"],
                             lat, lon, *ap2._bbox_point(lat, lon)))
            stats["charging"] += 1
        ho = ap2.hospital_tags(tags, include_clinics)
        if ho is not None:
            hosp_rows.append((osm_id, ho["name"], ho["emergency"], ho["kind"],
                              lat, lon, *ap2._bbox_point(lat, lon)))
            stats["hospital"] += 1
        ra = ap2.rest_area_tags(tags)
        if ra is not None:
            rest_rows.append((osm_id, ra["name"], ra["kind"],
                              lat, lon, *ap2._bbox_point(lat, lon)))
            stats["rest_area"] += 1
        lx = ap2.level_crossing_tags(tags)
        if lx is not None:
            levelx_rows.append((osm_id, lx["name"], lx["barrier"], lx["light"],
                                lat, lon, *ap2._bbox_point(lat, lon)))
            stats["level_crossing"] += 1
        sb = ap2.speed_bump_tags(tags)
        if sb is not None:
            bump_rows.append((osm_id, sb["kind"],
                              lat, lon, *ap2._bbox_point(lat, lon)))
            stats["speed_bump"] += 1
        tb = ap2.toll_booth_tags(tags)
        if tb is not None:
            toll_rows.append((osm_id, tb["name"],
                              lat, lon, *ap2._bbox_point(lat, lon)))
            stats["toll_booth"] += 1
        bc = ap2.border_control_tags(tags)
        if bc is not None:
            border_rows.append((osm_id, bc["name"],
                                lat, lon, *ap2._bbox_point(lat, lon)))
            stats["border_control"] += 1

    class Handler(osmium.SimpleHandler):
        def node(self, n):
            progress["nodes"] += 1
            if progress["nodes"] % 50_000 == 0:
                _emit("nodes")
            if not n.location.valid():
                return
            # FAST PATH: the vast majority of OSM nodes are untagged geometry
            # points — skip them without building a dict or running classifiers.
            tg = n.tags
            if len(tg) == 0:
                return
            lat, lon = n.location.lat, n.location.lon
            tags = dict(tg)                       # build the tag dict ONCE
            _lot_from_tags(n.id, tags, [(lat, lon)])
            cam = ap.camera_tags(tags)
            if cam is not None:
                cam_rows.append((
                    n.id, lat, lon, cam["maxspeed"], cam["direction"], cam["kind"],
                    lat - M, lat + M, lon - M, lon + M))
                cam_ids.add(n.id)
                stats["cam"] += 1
            _pois(n.id, lat, lon, tags)

        def way(self, w):
            progress["ways"] += 1
            if progress["ways"] % 25_000 == 0:
                _emit("ways")
            tg = w.tags
            if len(tg) == 0:
                return
            tags = dict(tg)
            # Camera direction resolution runs BEFORE the interest test below:
            # the camera's parent road is usually of no other interest here, and
            # this scan is deliberately cheap (set lookup per node ref, no
            # geometry built), so it does not reintroduce the per-way coord cost.
            ap.scan_way_for_cameras(w, tags, cam_ids, cam_way)
            # Decide if this way is of interest BEFORE building coords (which
            # iterates every node location — expensive on a road-dense extract).
            want_lot = ap.lot_tags(tags) is not None
            want_curb = bool(ap.curb_tags(tags))
            want_poi = (ap2.fuel_tags(tags) or ap2.charging_tags(tags)
                        or ap2.hospital_tags(tags, include_clinics)
                        or ap2.rest_area_tags(tags)
                        or ap2.level_crossing_tags(tags) or ap2.speed_bump_tags(tags)
                        or ap2.toll_booth_tags(tags) or ap2.border_control_tags(tags))
            if not (want_lot or want_curb or want_poi):
                return
            try:
                coords = [(nd.lat, nd.lon) for nd in w.nodes if nd.location.valid()]
            except osmium.InvalidLocationError:
                return
            if not coords:
                return
            if len(coords) >= 2:
                if want_lot:
                    _lot_from_tags(w.id, tags, coords)
                if want_curb:
                    sides = ap.curb_tags(tags)
                    blob = ap.pack_coords(coords)
                    mnla, mxla, mnlo, mxlo = ap.bbox(coords)
                    for s in sides:
                        curb_rows.append((
                            w.id, s["side"], s["parking"], s["restriction"],
                            s["restr_cond"], s["access"], s["access_cond"],
                            s["permit"], s["zone"], s["maxstay"], s["maxstay_cond"],
                            s["fee"], blob, mnla, mxla, mnlo, mxlo))
                        stats["curb"] += 1
            if want_poi:
                nc = len(coords)
                clat = sum(c[0] for c in coords) / nc
                clon = sum(c[1] for c in coords) / nc
                _pois(w.id, clat, clon, tags)

    t0 = _time.time()
    Handler().apply_file(pbf, locations=True)

    # write all tables (arities match each module's INSERTs exactly)
    con.executemany("INSERT INTO parking_curb VALUES(" + ",".join("?" * 17) + ")",
                    curb_rows)
    con.executemany("INSERT INTO parking_lot VALUES(" + ",".join("?" * 16) + ")",
                    lot_rows)
    cam_rows13, cam_resolved = ap.camera_rows_resolved(cam_rows, cam_way)
    con.executemany("INSERT INTO speed_camera VALUES(" + ",".join("?" * 13) + ")",
                    cam_rows13)
    con.executemany("INSERT INTO fuel VALUES(" + ",".join("?" * 12) + ")", fuel_rows)
    con.executemany("INSERT INTO charging VALUES(" + ",".join("?" * 14) + ")",
                    chg_rows)
    con.executemany("INSERT INTO hospital VALUES(" + ",".join("?" * 10) + ")",
                    hosp_rows)
    con.executemany("INSERT INTO rest_area VALUES(" + ",".join("?" * 9) + ")",
                    rest_rows)
    con.executemany("INSERT INTO level_crossing VALUES(" + ",".join("?" * 10) + ")",
                    levelx_rows)
    con.executemany("INSERT INTO speed_bump VALUES(" + ",".join("?" * 8) + ")",
                    bump_rows)
    con.executemany("INSERT INTO toll_booth VALUES(" + ",".join("?" * 8) + ")",
                    toll_rows)
    con.executemany("INSERT INTO border_control VALUES(" + ",".join("?" * 8) + ")",
                    border_rows)

    # meta markers — identical to what each standalone module writes
    ap.set_meta(con, "parking_curb_rows", stats["curb"])
    ap.set_meta(con, "parking_lot_rows", stats["lot"])
    ap.set_meta(con, "speed_camera_rows", stats["cam"])
    ap.set_meta(con, "speed_camera_directed", cam_resolved)
    ap.set_meta(con, "schema_version", ap.SCHEMA_VERSION)
    for k in ("fuel", "charging", "hospital", "rest_area",
              "level_crossing", "speed_bump", "toll_booth", "border_control"):
        ap2.set_meta(con, f"poi_{k}_count", stats[k])
    ap2.set_meta(con, "poi_extracted", "1")
    con.commit()
    con.close()

    if progress_cb is None:
        el = _time.time() - t0
        print(f"add_parking_pois: lots={stats['lot']} curb={stats['curb']} "
              f"cams={stats['cam']} (dir {cam_resolved}) "
              f"fuel={stats['fuel']} chg={stats['charging']} "
              f"hosp={stats['hospital']} rest={stats['rest_area']} "
              f"xing={stats['level_crossing']} bump={stats['speed_bump']} "
              f"toll={stats['toll_booth']} bord={stats['border_control']}  "
              f"({el:.0f}s, one pass)")
    return stats


def main():
    ap_ = argparse.ArgumentParser(
        description="One-pass parking+cameras+POIs extractor")
    ap_.add_argument("--pbf", required=True)
    ap_.add_argument("--db", required=True)
    ap_.add_argument("--clinics", action="store_true",
                     help="also include amenity=clinic as hospitals")
    a = ap_.parse_args()
    run(a.pbf, a.db, include_clinics=a.clinics)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        # Gentle stop on Ctrl+C: no traceback. Any partial DB is left for
        # build_europe to clean/redo; a standalone run just exits.
        import sys as _sys
        print("\nInterrupted by user (Ctrl+C) — stopped.", file=_sys.stderr)
        _sys.exit(130)
