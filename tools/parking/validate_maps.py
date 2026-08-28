#!/usr/bin/env python3
"""
validate_maps.py — sanity-check the processed map databases and index.json.

Run this in the map folder AFTER a build to confirm every region's .db is
complete and coherent, and that index.json agrees with the .db files on disk —
before deploying. It never modifies anything; it only reports.

Two groups of checks:

  A. Per-.db integrity (does the file itself contain sensible, complete data)
     - opens as SQLite and runs a quick integrity_check
     - required tables present: meta, segments, parking_lot, parking_curb,
       speed_camera
     - schema_version in meta is the final 5 (roads + parking + cameras), not 3
       (roads only — means the parking stage never ran)
     - segments table is non-empty (a region with zero roads is almost always a
       failed/partial build)
     - with_maxspeed <= segments, and bbox is present and sane (min<max, within
       Europe-ish bounds), computed from the segments if missing from meta
     - no leftover <region>.db.part beside a real <region>.db

  B. .db <-> index.json coherence (do the manifest and the files agree)
     - every manifest region has its .db file on disk (and vice-versa: .db files
       with no manifest entry are flagged as orphans)
     - manifest counts == actual COUNT(*) in each table
     - manifest sizeBytes == real file size; sha256 == real file hash
     - manifest bbox == db bbox; dbSchemaVersion == db schema_version

Exit code is 0 when everything passes (warnings allowed), 1 when any ERROR is
found — so it can gate a deploy script.

Usage:
    python validate_maps.py                 # check ./ (map folder)
    python validate_maps.py --dir /maps
    python validate_maps.py --skip-hash     # faster: skip sha256 recompute
    python validate_maps.py --quiet         # only show problems + summary
"""
import argparse
import hashlib
import json
import os
import sqlite3
import sys

FINAL_SCHEMA = 5
REQUIRED_TABLES = ("meta", "segments", "parking_lot", "parking_curb",
                   "speed_camera")
# Rough Europe bounds (with margin for overseas territories the data may include,
# e.g. French Guyane/Reunion) — used only as a soft sanity check on bbox.
EUR_LAT = (-30.0, 82.0)
EUR_LON = (-70.0, 70.0)


class Findings:
    def __init__(self):
        self.errors = []
        self.warnings = []
        self.checked = 0

    def error(self, rid, msg):
        self.errors.append((rid, msg))

    def warn(self, rid, msg):
        self.warnings.append((rid, msg))


def table_exists(con, name):
    return con.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND "
                       "name=?", (name,)).fetchone() is not None


def meta_get(con, key):
    if not table_exists(con, "meta"):
        return None
    row = con.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
    return row[0] if row else None


def count(con, table):
    return con.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]


def sha256_of(path, buf=1 << 20):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(buf), b""):
            h.update(chunk)
    return h.hexdigest()


def compute_bbox(con):
    row = con.execute("SELECT MIN(minLat), MIN(minLon), MAX(maxLat), MAX(maxLon) "
                      "FROM segments").fetchone()
    if row and all(v is not None for v in row):
        return row  # (minLat, minLon, maxLat, maxLon)
    return None


def parse_bbox(s):
    try:
        parts = [float(x) for x in str(s).split(",")]
        return parts if len(parts) == 4 else None
    except (TypeError, ValueError):
        return None


def check_db_internal(rid, db_path, f):
    """Group A: is this .db internally complete and sane? Returns the actual
    table counts + bbox for the coherence step (or None if unopenable)."""
    # leftover .part beside the real .db?
    if os.path.exists(db_path + ".part"):
        f.warn(rid, "leftover .db.part beside the finished .db "
                    "(debris from an interrupted run — safe to delete)")
    try:
        con = sqlite3.connect(db_path)
    except sqlite3.Error as e:
        f.error(rid, f"cannot open .db: {e}")
        return None
    try:
        # quick integrity check
        try:
            res = con.execute("PRAGMA integrity_check").fetchone()
            if res and res[0] != "ok":
                f.error(rid, f"SQLite integrity_check failed: {res[0]}")
        except sqlite3.DatabaseError as e:
            f.error(rid, f"not a valid SQLite database: {e}")
            return None

        # required tables
        missing = [t for t in REQUIRED_TABLES if not table_exists(con, t)]
        if missing:
            f.error(rid, f"missing table(s): {', '.join(missing)}"
                         + ("  (schema 3 only? parking stage never ran)"
                            if "parking_lot" in missing else ""))
        # if segments missing we can't do much more
        if not table_exists(con, "segments"):
            return None

        # schema version must be the final one
        sv = meta_get(con, "schema_version")
        if sv is None:
            f.warn(rid, "meta.schema_version missing")
        elif str(sv) != str(FINAL_SCHEMA):
            f.error(rid, f"schema_version={sv}, expected {FINAL_SCHEMA} "
                         f"(a value of 3 means only speed limits were written — "
                         f"parking/cameras stage did not complete)")

        counts = {t: count(con, t) for t in REQUIRED_TABLES if table_exists(con, t)}
        segs = counts.get("segments", 0)
        if segs == 0:
            f.error(rid, "segments table is EMPTY — region has no roads "
                         "(almost always a failed/partial build)")

        # with_maxspeed coherence
        wm = meta_get(con, "with_maxspeed")
        if wm is not None:
            try:
                wm = int(wm)
                if wm > segs:
                    f.error(rid, f"with_maxspeed ({wm}) > segments ({segs})")
                elif segs > 0 and wm == 0:
                    f.warn(rid, "with_maxspeed=0 — no road has a speed limit "
                                "(suspicious for a populated region)")
            except ValueError:
                f.warn(rid, f"with_maxspeed not an integer: {wm!r}")

        # bbox: from meta or computed; sanity-check ranges
        bbox = parse_bbox(meta_get(con, "bbox")) or (
            list(compute_bbox(con)) if segs else None)
        if bbox is None:
            if segs:
                f.warn(rid, "no bbox in meta and could not compute one")
        else:
            minLat, minLon, maxLat, maxLon = bbox
            if not (minLat < maxLat and minLon < maxLon):
                f.error(rid, f"bbox is degenerate/inverted: {bbox}")
            if not (EUR_LAT[0] <= minLat <= EUR_LAT[1] and
                    EUR_LAT[0] <= maxLat <= EUR_LAT[1] and
                    EUR_LON[0] <= minLon <= EUR_LON[1] and
                    EUR_LON[0] <= maxLon <= EUR_LON[1]):
                f.warn(rid, f"bbox outside expected Europe bounds: {bbox} "
                            "(ok for overseas territories, else check source)")

        return {"counts": counts,
                "bbox": bbox,
                "schema_version": sv}
    finally:
        con.close()


def check_coherence(rid, db_path, entry, db_info, f, skip_hash):
    """Group B: does index.json agree with the .db on disk?"""
    if db_info is None:
        return  # couldn't read db; group A already reported it

    # counts
    m_counts = entry.get("counts") or {}
    actual = db_info["counts"]
    name_map = {"segments": "segments", "with_maxspeed": None,
                "parking_lot": "parking_lot", "parking_curb": "parking_curb",
                "speed_camera": "speed_camera"}
    for mkey, tbl in name_map.items():
        if mkey not in m_counts:
            continue
        if tbl is None:  # with_maxspeed lives in meta, not a table
            continue
        if tbl in actual and int(m_counts[mkey]) != actual[tbl]:
            f.error(rid, f"manifest counts.{mkey}={m_counts[mkey]} but .db has "
                         f"{actual[tbl]} row(s) in {tbl}")

    # size
    real_size = os.path.getsize(db_path)
    if "sizeBytes" in entry and int(entry["sizeBytes"]) != real_size:
        f.error(rid, f"manifest sizeBytes={entry['sizeBytes']} but real file is "
                     f"{real_size} bytes (index.json is stale for this region)")

    # schema
    if "dbSchemaVersion" in entry and db_info["schema_version"] is not None:
        if int(entry["dbSchemaVersion"]) != int(db_info["schema_version"]):
            f.error(rid, f"manifest dbSchemaVersion={entry['dbSchemaVersion']} != "
                         f"db schema_version={db_info['schema_version']}")

    # bbox
    m_bbox = parse_bbox(entry.get("bbox"))
    if m_bbox and db_info["bbox"]:
        if any(abs(a - b) > 1e-4 for a, b in zip(m_bbox, db_info["bbox"])):
            f.warn(rid, f"manifest bbox {m_bbox} differs from db bbox "
                        f"{db_info['bbox']}")

    # sha256 (strongest coherence check — catches any post-manifest change)
    if not skip_hash and entry.get("sha256"):
        real = sha256_of(db_path)
        if real != entry["sha256"]:
            f.error(rid, "sha256 mismatch: .db on disk differs from what "
                         "index.json recorded (rebuild or re-run enrich, and "
                         "re-upload this .db + index.json together)")


def main():
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir", default=".", help="map folder (default: current)")
    ap.add_argument("--skip-hash", action="store_true",
                    help="skip sha256 recompute (faster on large sets)")
    ap.add_argument("--quiet", action="store_true",
                    help="only print problems and the final summary")
    a = ap.parse_args()

    d = os.path.abspath(a.dir)
    index_path = os.path.join(d, "index.json")
    if not os.path.exists(index_path):
        sys.exit(f"no index.json in {d}")
    index = json.load(open(index_path))
    regions = index.get("regions", [])
    by_id = {r["id"]: r for r in regions}

    f = Findings()

    # every .db on disk (for orphan detection)
    db_files = {fn[:-3] for fn in os.listdir(d)
                if fn.endswith(".db") and os.path.isfile(os.path.join(d, fn))}

    # --- manifest-level checks -------------------------------------------
    manifest_ids = set(by_id)
    orphan_dbs = sorted(db_files - manifest_ids)
    missing_dbs = sorted(manifest_ids - db_files)
    for rid in orphan_dbs:
        f.warn(rid, ".db exists on disk but has NO entry in index.json "
                    "(won't be offered to the app; enrich or remove it)")
    for rid in missing_dbs:
        f.error(rid, "listed in index.json but its .db is MISSING on disk "
                     "(the app will try to download a file that isn't there)")

    # --- per-region checks -----------------------------------------------
    bbox_ok = []
    bbox_missing_fixable = []    # has roads, no bbox -> enrich_index.py fills it
    bbox_missing_noroads = []    # no roads -> needs a rebuild
    for rid in sorted(manifest_ids):
        entry = by_id[rid]
        db_path = os.path.join(d, entry.get("file") or f"{rid}.db")
        if not os.path.exists(db_path):
            continue  # already reported as missing
        f.checked += 1
        db_info = check_db_internal(rid, db_path, f)
        check_coherence(rid, db_path, entry, db_info, f, a.skip_hash)
        # bbox coverage: distinguish "has roads but no bbox" (fixable by
        # enrich_index.py) from "no roads" (needs a rebuild — can't derive bbox).
        if db_info is not None:
            # Coverage is about what the MANIFEST carries (that's what the app
            # reads). db_info["bbox"] may be computed from segments even when the
            # manifest lacks it — that's precisely the "fixable by enrich" case.
            manifest_has_bbox = bool(entry.get("bbox"))
            can_compute = bool(db_info.get("bbox"))
            has_roads = (db_info["counts"].get("segments", 0) > 0)
            if manifest_has_bbox:
                bbox_ok.append(rid)
            elif can_compute or has_roads:
                bbox_missing_fixable.append(rid)
            else:
                bbox_missing_noroads.append(rid)
        if not a.quiet and db_info:
            c = db_info["counts"]
            print(f"  {rid:34s} roads={c.get('segments',0):>8,} "
                  f"park={c.get('parking_lot',0):>7,} "
                  f"cams={c.get('speed_camera',0):>5,}  ok")

    # --- report ----------------------------------------------------------
    print()
    print(f"Checked {f.checked} region(s) in {d}")

    # bbox coverage summary — the recurring "bbox not filled" question, answered
    # plainly every run, separating fixable-by-enrich from needs-rebuild.
    total_bbox = len(bbox_ok) + len(bbox_missing_fixable) + len(bbox_missing_noroads)
    if total_bbox:
        print(f"bbox present: {len(bbox_ok)}/{total_bbox}"
              f" · missing-with-roads: {len(bbox_missing_fixable)}"
              f" · missing-no-roads: {len(bbox_missing_noroads)}")
        if bbox_missing_fixable:
            print(f"  {len(bbox_missing_fixable)} region(s) have roads but no bbox"
                  f" — run: python enrich_index.py --apply  (computes bbox from "
                  f"the .db, no reprocessing)")
            for rid in bbox_missing_fixable:
                f.warn(rid, "no bbox but has roads — fixable with "
                            "enrich_index.py --apply")
        if bbox_missing_noroads:
            print(f"  {len(bbox_missing_noroads)} region(s) have no roads AND no "
                  f"bbox — these need REPROCESSING (build_europe.py --force)")
    if f.warnings:
        print(f"\n{len(f.warnings)} WARNING(S):")
        for rid, msg in f.warnings:
            print(f"  [warn] {rid}: {msg}")
    if f.errors:
        print(f"\n{len(f.errors)} ERROR(S):")
        for rid, msg in f.errors:
            print(f"  [ERROR] {rid}: {msg}")
        print("\nRESULT: FAILED — fix the errors above before deploying.")
        sys.exit(1)
    else:
        print("\nRESULT: PASS" + (" (with warnings)" if f.warnings else "")
              + " — databases are complete and coherent with index.json.")
        sys.exit(0)


if __name__ == "__main__":
    main()
