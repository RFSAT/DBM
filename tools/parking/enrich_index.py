#!/usr/bin/env python3
"""
enrich_index.py — back-fill index.json with per-region counts + bounding box by
reading the already-built .db files. NO .pbf and NO reprocessing needed.

Why this exists: newer builds of build_europe.py write content counts (roads,
roads-with-limit, parking lots, curbside rules, speed cameras) and a bounding box
into index.json, which the app shows in its region-info panel. Regions built
before that won't have these in the manifest — but the data is all still inside
the .db, so we can extract it and add it to index.json in place.

Everything is read straight from each <region>.db:
  * counts        -> SELECT COUNT(*) on segments / parking_lot / parking_curb /
                     speed_camera
  * with_maxspeed -> meta value if present, else COUNT(*) WHERE maxspeed > 0
  * bbox          -> meta 'bbox' if present, else computed from the segments
                     table's own minLat/maxLat/minLon/maxLon columns
So even .db files built before bbox was ever stored get a bounding box.

Usage (from the map folder that holds index.json and the .db files):
    python enrich_index.py                # dry run: shows what WOULD change
    python enrich_index.py --apply        # write the enriched index.json
    python enrich_index.py --dir /maps --apply
"""
import argparse
import json
import os
import sqlite3
import sys


def table_exists(con, name):
    return con.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
        (name,)).fetchone() is not None


def count(con, name):
    if not table_exists(con, name):
        return None
    return con.execute(f"SELECT COUNT(*) FROM {name}").fetchone()[0]


def meta_get(con, key):
    if not table_exists(con, "meta"):
        return None
    row = con.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
    return row[0] if row else None


def extract(db_path):
    """Return (counts_dict, bbox_str) read/computed from the .db, or (None, '')."""
    con = sqlite3.connect(db_path)
    try:
        segments = count(con, "segments")
        if segments is None:
            # not a speed-limit db (no segments table) — nothing useful to add
            return None, ""
        # with_maxspeed: prefer the stored meta value; else compute it.
        wm = meta_get(con, "with_maxspeed")
        if wm is not None:
            with_maxspeed = int(wm)
        else:
            with_maxspeed = con.execute(
                "SELECT COUNT(*) FROM segments WHERE maxspeed > 0").fetchone()[0]
        counts = {
            "segments": segments,
            "with_maxspeed": with_maxspeed,
            "parking_lot": count(con, "parking_lot") or 0,
            "parking_curb": count(con, "parking_curb") or 0,
            "speed_camera": count(con, "speed_camera") or 0,
        }
        # bbox: stored meta value first, else compute from the segments columns.
        bbox = meta_get(con, "bbox") or ""
        if not bbox:
            row = con.execute(
                "SELECT MIN(minLat), MIN(minLon), MAX(maxLat), MAX(maxLon) "
                "FROM segments").fetchone()
            if row and all(v is not None for v in row):
                bbox = ",".join(f"{v:.6f}" for v in row)
        return counts, bbox
    finally:
        con.close()


def main():
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir", default=".", help="map folder (default: current)")
    ap.add_argument("--apply", action="store_true",
                    help="write changes (default is a dry run)")
    a = ap.parse_args()

    d = os.path.abspath(a.dir)
    index_path = os.path.join(d, "index.json")
    if not os.path.exists(index_path):
        sys.exit(f"no index.json in {d}")
    index = json.load(open(index_path))
    regions = index.get("regions", [])
    if not regions:
        sys.exit("index.json has no regions")

    changed = 0
    missing_db = 0
    print(f"{'APPLYING' if a.apply else 'DRY RUN'} — enriching {len(regions)} "
          f"region(s) from their .db files:")
    for r in regions:
        rid = r.get("id", "?")
        db_file = r.get("file") or f"{rid}.db"
        db_path = os.path.join(d, db_file)
        if not os.path.exists(db_path):
            print(f"  {rid:32s} .db MISSING ({db_file}) — skipped")
            missing_db += 1
            continue
        counts, bbox = extract(db_path)
        if counts is None:
            print(f"  {rid:32s} no segments table — skipped")
            continue
        # what's new vs what's already there?
        had_counts = r.get("counts") or {}
        need_counts = had_counts != counts
        need_bbox = bbox and r.get("bbox", "") != bbox
        if not need_counts and not need_bbox:
            print(f"  {rid:32s} already up to date")
            continue
        bits = []
        if need_counts:
            bits.append(f"counts(roads={counts['segments']:,}, "
                        f"parking={counts['parking_lot']:,}, "
                        f"cams={counts['speed_camera']:,})")
        if need_bbox:
            bits.append(f"bbox={bbox}")
        print(f"  {rid:32s} + {', '.join(bits)}")
        if a.apply:
            r["counts"] = counts
            if bbox:
                r["bbox"] = bbox
        changed += 1

    if a.apply and changed:
        # atomic write
        tmp = index_path + ".tmp"
        json.dump(index, open(tmp, "w"), indent=2)
        os.replace(tmp, index_path)
        print(f"\nWrote {index_path}: enriched {changed} region(s)"
              + (f", {missing_db} with missing .db" if missing_db else "") + ".")
    elif a.apply:
        print("\nNothing to change — index.json already enriched.")
    else:
        print(f"\nDry run only ({changed} region(s) would change). "
              f"Re-run with --apply to write index.json.")


if __name__ == "__main__":
    main()
