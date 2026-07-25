#!/usr/bin/env python3
"""
build_region.py — build a parking-enabled region .db and (re)generate the
index.json manifest the DBM app downloads from rfsat.com.

This is the SERVER/BUILD-side tool. It does two jobs:

  1. build   — run the existing map converter + add_parking.py for a region,
               producing <region>.db with schema_version = 4.
  2. manifest — scan a directory of built .db files and write index.json with
               the correct sizeBytes, sha256, dataDate, version and
               dbSchemaVersion for each, in the exact shape MapCatalog.kt parses.

You upload the resulting <region>.db files and index.json to
    https://www.rfsat.com/products/maps/
The app reads index.json, compares versions, and downloads updated .db files,
verifying the sha256 before installing.

Typical use:

    # after building greece.db (with parking tables) in ./out/
    python build_region.py manifest --dir ./out \
        --base-url https://www.rfsat.com/products/maps

Requires only the Python standard library for the manifest step. The `build`
step shells out to your existing converter (adjust CONVERTER below).
"""

import argparse
import hashlib
import json
import os
import sqlite3
import subprocess
import sys
from datetime import datetime, timezone

# --- adjust to your existing converter invocation --------------------------
# The command that turns a Geofabrik .osm.pbf into the base <region>.db
# (speed limits etc.). add_parking.py is then run on top of it.
CONVERTER = ["python", "convert_map.py"]      # <-- your existing tool
ADD_PARKING = ["python", "add_parking.py"]


def sha256_of(path, chunk=1 << 20):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(chunk), b""):
            h.update(b)
    return h.hexdigest()


def db_meta(path):
    """Read schema_version and data date out of the built .db's meta table."""
    con = sqlite3.connect(path)
    try:
        rows = dict(con.execute("SELECT key, value FROM meta").fetchall())
    except sqlite3.Error:
        rows = {}
    finally:
        con.close()
    return rows


def build(args):
    region = args.region
    pbf = args.pbf
    out_dir = args.dir
    os.makedirs(out_dir, exist_ok=True)
    db_path = os.path.join(out_dir, f"{region}.db")

    print(f"[1/2] base convert {pbf} -> {db_path}")
    subprocess.run(CONVERTER + ["--pbf", pbf, "--db", db_path, "--region", region],
                   check=True)

    print(f"[2/2] add parking tables")
    subprocess.run(ADD_PARKING + ["--pbf", pbf, "--db", db_path], check=True)

    meta = db_meta(db_path)
    print(f"done: {db_path}")
    print(f"  schema_version   = {meta.get('schema_version')}")
    print(f"  parking_lot_rows = {meta.get('parking_lot_rows')}")
    print(f"  parking_curb_rows= {meta.get('parking_curb_rows')}")
    print("Next: regenerate the manifest with the 'manifest' subcommand.")


def manifest(args):
    out_dir = args.dir
    dbs = sorted(f for f in os.listdir(out_dir) if f.endswith(".db"))
    if not dbs:
        sys.exit(f"No .db files in {out_dir}")

    # Load previous manifest (if any) so we can auto-increment version when a
    # file's content actually changed, and preserve display names.
    prev = {}
    manifest_path = os.path.join(out_dir, "index.json")
    if os.path.exists(manifest_path) and not args.reset_versions:
        with open(manifest_path) as f:
            old = json.load(f)
        for r in old.get("regions", []):
            prev[r["id"]] = r

    regions = []
    for db in dbs:
        rid = db[:-3]                          # greece.db -> greece
        path = os.path.join(out_dir, db)
        meta = db_meta(path)
        sha = sha256_of(path)
        size = os.path.getsize(path)
        schema = int(meta.get("schema_version", 2))
        data_date = meta.get("data_date") or meta.get("osm_date") or ""

        old = prev.get(rid, {})
        # Bump version only when the file content changed (sha differs).
        version = old.get("version", 0)
        if old.get("sha256") != sha:
            version = version + 1 if version else 1

        regions.append({
            "id": rid,
            "name": old.get("name") or rid.capitalize(),
            "file": db,
            "sizeBytes": size,
            "sha256": sha,
            "dataDate": data_date,
            "version": version,
            "dbSchemaVersion": schema,
        })
        flag = "changed -> v%d" % version if old.get("sha256") != sha else "unchanged v%d" % version
        print(f"  {rid:20s} {size/1e6:7.1f} MB  schema {schema}  {flag}")

    out = {
        "schemaVersion": 1,
        "baseUrl": args.base_url.rstrip("/"),
        "updated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "regions": regions,
    }
    with open(manifest_path, "w") as f:
        json.dump(out, f, indent=2)
    print(f"\nwrote {manifest_path} ({len(regions)} region(s))")
    print("Upload every changed .db AND index.json to the server.")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    b = sub.add_parser("build", help="build one region .db (base + parking)")
    b.add_argument("--region", required=True, help="e.g. greece")
    b.add_argument("--pbf", required=True, help="Geofabrik .osm.pbf")
    b.add_argument("--dir", default="./out", help="output dir")
    b.set_defaults(func=build)

    m = sub.add_parser("manifest", help="(re)generate index.json for a dir of .db files")
    m.add_argument("--dir", default="./out", help="dir of built .db files")
    m.add_argument("--base-url", required=True,
                   help="https://www.rfsat.com/products/maps")
    m.add_argument("--reset-versions", action="store_true",
                   help="ignore previous index.json and start versions at 1")
    m.set_defaults(func=manifest)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
