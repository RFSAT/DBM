#!/usr/bin/env python3
"""
build_europe.py — batch-process all European Geofabrik region extracts in a
folder into DBM region databases (speed limits are assumed already present via
your base converter; this stage adds parking + speed cameras), then generate the
index.json manifest the app downloads.

Design goals (per requirements):
  * Process EVERY *.osm.pbf in the current folder that is a recognised European
    COUNTRY region (the list at https://download.geofabrik.de/europe.html),
    EXCLUDING the "special sub regions" (Alps, Britain and Ireland, DACH, Great
    Britain) which duplicate data already in the country files.
  * Downloaded files may be OLD — that's fine; we process whatever is present.
  * Do NOT reprocess a file that has already been processed, UNLESS the source
    .pbf has changed (newer download) since it was last processed. This is
    tracked in a small .build_state.json keyed by the source file's size+mtime.
  * Progress monitoring during each map's processing (delegated to add_parking,
    which prints per-500k-element progress), plus a per-file SUMMARY and
    VERIFICATION after each file.
  * Emit index.json in the exact shape the app's MapCatalog parses.

This does NOT download anything. You place the .osm.pbf files in the folder
yourself (or with a separate fetch step). It also does NOT run your base map
converter — set BASE_CONVERTER below if you want it invoked automatically;
otherwise the script expects <region>.db to already exist (or will create a
parking/camera-only DB if RUN_BASE_CONVERTER is False and no base exists —
see notes).

Usage:
    python build_europe.py --dir . --base-url https://www.rfsat.com/products/maps
    python build_europe.py --dir . --base-url ... --force        # ignore state
    python build_europe.py --dir . --base-url ... --only greece,cyprus

Requires: add_parking.py in the same folder (imported), and pyosmium
(pip install osmium).
"""

import argparse
import hashlib
import json
import os
import sqlite3
import sys
import time
from datetime import datetime, timezone

# ---------------------------------------------------------------------------
# The recognised European COUNTRY regions from the Geofabrik europe.html page.
# Keys are the geofabrik region id (the .osm.pbf basename without -latest), and
# match the `id` MapCatalog.kt expects. Values are display names.
# The four "special sub regions" (alps, britain-and-ireland, dach,
# great-britain) are deliberately NOT here — they duplicate country data.
# ---------------------------------------------------------------------------
EUROPE_REGIONS = {
    "albania": "Albania",
    "andorra": "Andorra",
    "austria": "Austria",
    "azores": "Azores",
    "belarus": "Belarus",
    "belgium": "Belgium",
    "bosnia-herzegovina": "Bosnia-Herzegovina",
    "bulgaria": "Bulgaria",
    "croatia": "Croatia",
    "cyprus": "Cyprus",
    "czech-republic": "Czech Republic",
    "denmark": "Denmark",
    "estonia": "Estonia",
    "faroe-islands": "Faroe Islands",
    "finland": "Finland",
    "france": "France",
    "georgia": "Georgia",
    "germany": "Germany",
    "greece": "Greece",
    "guernsey-jersey": "Guernsey and Jersey",
    "hungary": "Hungary",
    "iceland": "Iceland",
    "ireland-and-northern-ireland": "Ireland and Northern Ireland",
    "isle-of-man": "Isle of Man",
    "italy": "Italy",
    "kosovo": "Kosovo",
    "latvia": "Latvia",
    "liechtenstein": "Liechtenstein",
    "lithuania": "Lithuania",
    "luxembourg": "Luxembourg",
    "macedonia": "Macedonia",
    "malta": "Malta",
    "moldova": "Moldova",
    "monaco": "Monaco",
    "montenegro": "Montenegro",
    "netherlands": "Netherlands",
    "norway": "Norway",
    "poland": "Poland",
    "portugal": "Portugal",
    "romania": "Romania",
    "russia": "Russian Federation",
    "serbia": "Serbia",
    "slovakia": "Slovakia",
    "slovenia": "Slovenia",
    "spain": "Spain",
    "sweden": "Sweden",
    "switzerland": "Switzerland",
    "turkey": "Turkey",
    "ukraine": "Ukraine",
    "united-kingdom": "United Kingdom",
}

# The special sub-regions we explicitly refuse even if present in the folder.
EXCLUDED_IDS = {"alps", "britain-and-ireland", "dach", "great-britain",
                "europe"}

STATE_FILE = ".build_state.json"

# The base speed-limit converter (osm_to_speedlimitdb.py) is run FIRST to create
# <region>.db with the `segments` table (schema 3), then add_parking augments it
# with parking + cameras (bumping to schema 5). Order matters: the speed-limit
# tool DROPs/creates tables, so it must run before add_parking, never after.
#
# POLICY: --drop-untagged-minor is intentionally NOT passed. Per project
# decision, we keep full road coverage (untagged minor roads included, with
# implicit defaults) and only consider dropping them if a region .db becomes
# impractically large. Do not add that flag here without that justification.
BASE_CONVERTER = ["python", "osm_to_speedlimitdb.py", "{pbf}", "{db}",
                  "--region", "{region}"]


def region_id_from_pbf(fname):
    """greece-latest.osm.pbf -> greece ; greece-260801.osm.pbf -> greece."""
    base = os.path.basename(fname)
    if not base.endswith(".osm.pbf"):
        return None
    stem = base[:-len(".osm.pbf")]
    # strip a trailing -latest or -YYMMDD date stamp
    for suffix in ("-latest",):
        if stem.endswith(suffix):
            stem = stem[:-len(suffix)]
            return stem
    # date-stamped: greece-260801
    parts = stem.rsplit("-", 1)
    if len(parts) == 2 and parts[1].isdigit():
        return parts[0]
    return stem


def sha256_of(path, chunk=1 << 20):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(chunk), b""):
            h.update(b)
    return h.hexdigest()


def load_state(d):
    p = os.path.join(d, STATE_FILE)
    if os.path.exists(p):
        try:
            return json.load(open(p))
        except Exception:
            return {}
    return {}


def save_state(d, state):
    json.dump(state, open(os.path.join(d, STATE_FILE), "w"), indent=2)


def src_signature(path):
    """A cheap fingerprint of the source .pbf: size + mtime. If the user
    downloads a newer file, this changes and we reprocess."""
    st = os.stat(path)
    return f"{st.st_size}:{int(st.st_mtime)}"


def pbf_date(path):
    """Best-effort OSM data date: the file's mtime as YYYY-MM-DD. (The true OSM
    timestamp is inside the .pbf header; mtime of a fresh Geofabrik download is a
    good proxy and needs no extra parsing.)"""
    return datetime.fromtimestamp(os.stat(path).st_mtime, timezone.utc)\
        .strftime("%Y-%m-%d")


def db_meta(path):
    con = sqlite3.connect(path)
    try:
        return dict(con.execute("SELECT key, value FROM meta").fetchall())
    except sqlite3.Error:
        return {}
    finally:
        con.close()


def verify_db(db_path):
    """Post-process verification. Returns (ok, dict-of-findings)."""
    findings = {}
    con = sqlite3.connect(db_path)
    try:
        def count(tbl):
            try:
                return con.execute(f"SELECT COUNT(*) FROM {tbl}").fetchone()[0]
            except sqlite3.Error:
                return None
        findings["schema_version"] = db_meta(db_path).get("schema_version")
        findings["segments"] = count("segments")            # speed limits
        findings["with_maxspeed"] = db_meta(db_path).get("with_maxspeed")
        findings["parking_lot"] = count("parking_lot")
        findings["parking_curb"] = count("parking_curb")
        findings["speed_camera"] = count("speed_camera")
        # sanity: coordinates within plausible European bounds
        bad = None
        try:
            bad = con.execute(
                "SELECT COUNT(*) FROM speed_camera "
                "WHERE lat < 34 OR lat > 72 OR lon < -32 OR lon > 45").fetchone()[0]
        except sqlite3.Error:
            bad = None
        findings["cameras_out_of_bounds"] = bad
    finally:
        con.close()
    # Full-feature DB must have ALL THREE: speed limits (segments), plus the
    # parking/camera tables present (schema 5). A missing segments table means
    # the base speed-limit stage didn't run — that's a hard fail.
    ok = (findings.get("schema_version") == "5"
          and findings.get("segments") not in (None, 0)
          and findings.get("speed_camera") is not None
          and findings.get("parking_lot") is not None
          and (findings.get("cameras_out_of_bounds") in (0, None)))
    return ok, findings


def process_one(pbf, region_id, region_name, add_parking, skip_base):
    """Build <region>.db with FULL features: speed limits (base converter) then
    parking + cameras (add_parking). Returns the db path.

    The two stages compose safely: osm_to_speedlimitdb creates segments+meta
    (schema 3); add_parking preserves segments and adds parking/camera tables
    (schema 5). Order is enforced here — base first, always.
    """
    db_path = f"{region_id}.db"

    if not skip_base:
        if not BASE_CONVERTER:
            sys.exit("BASE_CONVERTER not set — cannot produce speed limits.")
        cmd = [c.format(pbf=pbf, db=db_path, region=region_name)
               for c in BASE_CONVERTER]
        print(f"  [1/2] speed limits: {' '.join(cmd)}", flush=True)
        import subprocess
        subprocess.run(cmd, check=True)
    elif not os.path.exists(db_path):
        sys.exit(f"--skip-base given but {db_path} does not exist. Run the base "
                 f"speed-limit conversion first, or drop --skip-base.")

    # add_parking prints its own per-500k-element progress.
    print(f"  [2/2] parking + cameras", flush=True)
    add_parking.run(pbf, db_path)
    return db_path


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir", default=".", help="folder containing *.osm.pbf")
    ap.add_argument("--base-url", required=True,
                    help="https://www.rfsat.com/products/maps")
    ap.add_argument("--force", action="store_true",
                    help="reprocess even if state says up-to-date")
    ap.add_argument("--only", default="",
                    help="comma-separated region ids to limit to (e.g. greece,cyprus)")
    ap.add_argument("--skip-base", action="store_true",
                    help="skip the speed-limit stage; <region>.db must already "
                         "have a segments table (advanced/debug only)")
    a = ap.parse_args()

    d = os.path.abspath(a.dir)
    sys.path.insert(0, d)
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    try:
        import add_parking
    except ImportError:
        sys.exit("add_parking.py must be alongside this script (and pyosmium installed).")

    only = {x.strip() for x in a.only.split(",") if x.strip()}
    state = load_state(d)

    # Discover candidate .pbf files.
    pbfs = [f for f in os.listdir(d) if f.endswith(".osm.pbf")]
    if not pbfs:
        sys.exit(f"No *.osm.pbf files found in {d}")

    # Map them to region ids, filter to Europe country set, dedupe (prefer the
    # newest file if both greece-latest and greece-260801 exist).
    candidates = {}   # region_id -> (pbf_path, mtime)
    skipped_non_europe = []
    for f in sorted(pbfs):
        rid = region_id_from_pbf(f)
        if rid in EXCLUDED_IDS:
            skipped_non_europe.append((f, "special sub-region / excluded"))
            continue
        if rid not in EUROPE_REGIONS:
            skipped_non_europe.append((f, "not a recognised Europe country"))
            continue
        if only and rid not in only:
            continue
        path = os.path.join(d, f)
        mt = os.stat(path).st_mtime
        if rid not in candidates or mt > candidates[rid][1]:
            candidates[rid] = (path, mt)

    print(f"Found {len(candidates)} European region file(s) to consider"
          + (f"; skipping {len(skipped_non_europe)} non-Europe/special file(s)"
             if skipped_non_europe else "") + ".\n")
    for f, why in skipped_non_europe:
        print(f"  skip {f}: {why}")
    if skipped_non_europe:
        print()

    processed, skipped_uptodate, failed = [], [], []
    t_all = time.time()

    for rid in sorted(candidates):
        pbf, _ = candidates[rid]
        sig = src_signature(pbf)
        prev = state.get(rid, {})
        if not a.force and prev.get("src_sig") == sig and os.path.exists(f"{rid}.db"):
            print(f"== {EUROPE_REGIONS[rid]} ({rid}): up-to-date, skipping "
                  f"(source unchanged) ==")
            skipped_uptodate.append(rid)
            continue

        print(f"\n{'='*70}\n== {EUROPE_REGIONS[rid]} ({rid}) — processing "
              f"{os.path.basename(pbf)} ==\n{'='*70}", flush=True)
        t0 = time.time()
        try:
            db_path = process_one(pbf, rid, EUROPE_REGIONS[rid], add_parking, a.skip_base)
        except Exception as e:
            print(f"  FAILED: {e.__class__.__name__}: {e}", flush=True)
            failed.append(rid)
            continue
        dt = time.time() - t0

        ok, findings = verify_db(db_path)
        # Per-file SUMMARY + VERIFICATION
        print(f"\n  --- {EUROPE_REGIONS[rid]} summary ---")
        print(f"    time            : {dt:.0f}s")
        print(f"    schema_version  : {findings.get('schema_version')}")
        print(f"    segments (roads): {findings.get('segments')}  "
              f"(with maxspeed: {findings.get('with_maxspeed')})")
        print(f"    parking_lot     : {findings.get('parking_lot')}")
        print(f"    parking_curb    : {findings.get('parking_curb')}")
        print(f"    speed_camera    : {findings.get('speed_camera')}")
        oob = findings.get("cameras_out_of_bounds")
        print(f"    cameras OOB     : {oob}  "
              + ("(ok)" if oob in (0, None) else "(!! some cameras outside "
                 "Europe bounds — check the source)"))
        print(f"    VERIFICATION    : {'PASS' if ok else 'CHECK — see above'}")

        # Record state with the db's sha256 + size for the manifest step.
        size = os.path.getsize(db_path)
        sha = sha256_of(db_path)
        version = prev.get("version", 0)
        if prev.get("db_sha256") != sha:
            version = version + 1 if version else 1
        state[rid] = {
            "src_sig": sig,
            "db_sha256": sha,
            "db_size": size,
            "version": version,
            "data_date": pbf_date(pbf),
            "schema": findings.get("schema_version"),
            "counts": {k: findings.get(k) for k in
                       ("parking_lot", "parking_curb", "speed_camera")},
            "processed_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        }
        save_state(d, state)     # save after each file so a crash doesn't lose progress
        processed.append(rid)

    # ----- generate index.json from ALL region .db present in state -----
    regions = []
    for rid, info in sorted(state.items()):
        db_path = os.path.join(d, f"{rid}.db")
        if not os.path.exists(db_path):
            continue
        regions.append({
            "id": rid,
            "name": EUROPE_REGIONS.get(rid, rid.capitalize()),
            "country": EUROPE_REGIONS.get(rid, rid.capitalize()),
            "file": f"{rid}.db",
            "sizeBytes": info["db_size"],
            "sha256": info["db_sha256"],
            "dataDate": info["data_date"],
            "version": info["version"],
            "dbSchemaVersion": int(info.get("schema") or 5),
        })

    manifest = {
        "schemaVersion": 1,
        "baseUrl": a.base_url.rstrip("/"),
        "updated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "regions": regions,
    }
    with open(os.path.join(d, "index.json"), "w") as f:
        json.dump(manifest, f, indent=2)

    # ----- overall summary -----
    print(f"\n{'='*70}\nBATCH COMPLETE in {time.time()-t_all:.0f}s")
    print(f"  processed now   : {len(processed)}  {processed}")
    print(f"  up-to-date skip : {len(skipped_uptodate)}")
    print(f"  failed          : {len(failed)}  {failed}")
    print(f"  index.json      : {len(regions)} region(s) listed")
    print(f"\nUpload every changed .db AND index.json to {a.base_url}")
    print("(data files FIRST, index.json LAST — see SERVER-DEPLOYMENT.md)")


if __name__ == "__main__":
    main()
