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

Requires (all in the same folder as this script, i.e. the map folder):
  - add_parking.py            (imported; adds parking + speed cameras)
  - osm_to_speedlimitdb.py    (run as the base speed-limit stage)
  - pyosmium                  (pip install osmium)
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
# POLICY: --drop-untagged-minor IS now passed, to trim the combined DB. It drops
# only DRIVABLE roads whose highway class is uncommon (not one of the ~12 classes
# with an implicit default) AND that carry no maxspeed tag — e.g. highway=road
# (unknown class), highway=track. All common classes (motorway…residential,
# living_street, unclassified, service) keep their implicit default and are NOT
# dropped. The app latches the last known limit across such gaps, so a driver
# keeps a sensible limit on the few dropped roads. Remove the flag if you'd
# rather maximise coverage over size.
BASE_CONVERTER = ["python", "osm_to_speedlimitdb.py", "{pbf}", "{db}",
                  "--region", "{region}", "--drop-untagged-minor"]


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


def write_manifest(d, base_url, state):
    """Write index.json from the accumulated state — every region whose .db is
    present on disk. Called after EACH region so an interrupted run always leaves
    a complete, valid manifest covering everything finished so far (resume-safe).

    Written atomically (temp file + rename) so an interruption mid-write can
    never leave a truncated/corrupt index.json.
    Returns the number of regions listed.
    """
    regions = []
    for rid, info in sorted(state.items()):
        db_path = os.path.join(d, f"{rid}.db")
        if not os.path.exists(db_path):
            continue
        regions.append({
            "id": rid,
            "name": info.get("name") or EUROPE_REGIONS.get(rid, rid.capitalize()),
            "country": EUROPE_REGIONS.get(info.get("parent") or rid, rid.capitalize()),
            "parent": info.get("parent"),
            "file": f"{rid}.db",
            "sizeBytes": info["db_size"],
            "sha256": info["db_sha256"],
            "dataDate": info["data_date"],
            "version": info["version"],
            "dbSchemaVersion": int(info.get("schema") or 5),
            "counts": info.get("counts", {}),
            "bbox": info.get("bbox", ""),
        })
    manifest = {
        "schemaVersion": 1,
        "baseUrl": base_url.rstrip("/"),
        "updated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "regions": regions,
    }
    final = os.path.join(d, "index.json")
    tmp = final + ".tmp"
    with open(tmp, "w") as f:
        json.dump(manifest, f, indent=2)
    os.replace(tmp, final)          # atomic on the same filesystem
    return len(regions)


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


def process_one(pbf, region_id, region_name, add_parking, skip_base, log_fh=None):
    """Build <region>.db with FULL features: speed limits (base converter) then
    parking + cameras (add_parking). Returns the db path.

    The two stages compose safely: osm_to_speedlimitdb creates segments+meta
    (schema 3); add_parking preserves segments and adds parking/camera tables
    (schema 5). Order is enforced here — base first, always.

    ATOMICITY: when building fresh, both steps write to <region>.db.part and the
    result is renamed to <region>.db only after BOTH steps succeed. So an
    interrupted/killed build never leaves a partial <region>.db in place — it
    leaves at most a .part file (ignored by the resume check), and the region is
    cleanly rebuilt next run. (In --skip-base mode we operate on the existing
    <region>.db directly, since its roads are already there.)

    log_fh: if given (parallel mode), the base-converter SUBPROCESS's stdout/
    stderr are redirected to this file handle at the OS level. This is essential
    because the subprocess is a separate OS process — Python's redirect_stdout
    does NOT capture it, so without this its progress leaks onto the shared
    console. When None (sequential mode), the subprocess inherits the console as
    before.
    """
    final_db = f"{region_id}.db"

    if skip_base:
        if not os.path.exists(final_db):
            sys.exit(f"--skip-base given but {final_db} does not exist. Run the "
                     f"base speed-limit conversion first, or drop --skip-base.")
        # operate in place; roads already present
        print(f"\n  [2/2] parking + cameras", flush=True)
        add_parking.run(pbf, final_db)
        return final_db

    if not BASE_CONVERTER:
        sys.exit("BASE_CONVERTER not set — cannot produce speed limits.")
    work_db = f"{region_id}.db.part"
    # start clean: remove any leftover .part from a previous interrupted run
    if os.path.exists(work_db):
        os.remove(work_db)
    cmd = [c.format(pbf=pbf, db=work_db, region=region_name)
           for c in BASE_CONVERTER]
    print(f"\n  [1/2] speed limits", flush=True)
    import subprocess
    if log_fh is not None:
        # OS-level redirect of the subprocess: its stdout/stderr go to the log
        # file, not the shared console. flush first so ordering is sane.
        log_fh.flush()
        subprocess.run(cmd, check=True, stdout=log_fh, stderr=subprocess.STDOUT)
    else:
        subprocess.run(cmd, check=True)
    print(f"\n  [2/2] parking + cameras", flush=True)
    add_parking.run(pbf, work_db)
    # both steps done — atomically promote to the final name
    os.replace(work_db, final_db)     # atomic on the same filesystem
    return final_db


def _worker_ignore_sigint():
    """Pool-worker initializer: ignore SIGINT so Ctrl-C is handled only by the
    parent. MUST be module-level — on Windows (spawn) the pool pickles the
    initializer to each worker, and nested/local functions can't be pickled."""
    import signal
    try:
        signal.signal(signal.SIGINT, signal.SIG_IGN)
    except Exception:
        pass


def _worker_build_region(args):
    """Worker process: build ONE region's .db (both steps), writing this region's
    detailed progress to logs/<rid>.log instead of the shared console. Returns a
    plain-dict result the parent uses to record state + manifest. Must be
    module-level (picklable). Does NOT touch index.json / .build_state.json — the
    parent owns all shared-state writes to keep them consistent and resumable."""
    d, rid, name, pbf, skip_base = args
    import io
    import contextlib
    # Workers may be spawned (Windows) with a fresh cwd, but process_one runs the
    # base converter as `python osm_to_speedlimitdb.py` and writes `<rid>.db`
    # relative to cwd — so pin cwd to the map folder, exactly where the parent and
    # the sequential path run.
    try:
        os.chdir(d)
    except OSError:
        pass
    logdir = os.path.join(d, "logs")
    os.makedirs(logdir, exist_ok=True)
    logpath = os.path.join(logdir, f"{rid}.log")
    t0 = time.time()
    # add_parking is imported inside the worker (each process imports its own).
    try:
        import add_parking
    except Exception as e:
        return {"rid": rid, "ok": False, "error": f"import add_parking: {e}",
                "elapsed": 0.0, "log": logpath}
    try:
        with open(logpath, "w", encoding="utf-8") as lf, \
                contextlib.redirect_stdout(lf), contextlib.redirect_stderr(lf):
            # redirect_stdout captures this process's own print()s (e.g. from
            # add_parking, which runs in-process). The base converter is a
            # SUBPROCESS, so we ALSO pass lf explicitly to be redirected at the
            # OS level — otherwise its output leaks to the shared console.
            db_path = process_one(pbf, rid, name, add_parking, skip_base,
                                  log_fh=lf)
        ok, findings = verify_db(db_path)
        return {
            "rid": rid, "ok": True, "db_path": db_path, "findings": findings,
            "verify_ok": ok, "bbox": db_meta(db_path).get("bbox", ""),
            "size": os.path.getsize(db_path), "sha": sha256_of(db_path),
            "data_date": pbf_date(pbf), "elapsed": time.time() - t0,
            "log": logpath,
        }
    except Exception as e:
        return {"rid": rid, "ok": False,
                "error": f"{e.__class__.__name__}: {e}",
                "elapsed": time.time() - t0, "log": logpath}


def _dashboard_supported():
    """True only if stdout is a real terminal we can repaint (cursor up). Falls
    back to a plain append-only event log when output is redirected to a file,
    a pipe, or a terminal that doesn't report as a TTY (safest on odd consoles)."""
    try:
        return sys.stdout.isatty()
    except Exception:
        return False


class _Reporter:
    """Progress reporter for parallel mode. Renders a live repainting dashboard
    when the terminal supports it, else prints a clean append-only event log.
    Same public API either way: started(), finished(), footer()."""
    def __init__(self, total, jobs, use_dashboard):
        self.total = total
        self.jobs = jobs
        self.dash = use_dashboard
        self.running = {}          # rid -> start time
        self.done = 0
        self.failed = 0
        self.order = []            # completion order for the log
        self._last_lines = 0
        # enable ANSI on Windows 10+ consoles if we're going to repaint
        if self.dash and os.name == "nt":
            try:
                import ctypes
                k = ctypes.windll.kernel32
                k.SetConsoleMode(k.GetStdHandle(-11), 7)  # ENABLE_VT_PROCESSING
            except Exception:
                self.dash = False

    def _counts(self):
        # running = min(workers, outstanding); the pool runs at most `jobs` at
        # once even though all regions are submitted up front.
        outstanding = self.total - self.done - self.failed
        running = min(self.jobs, outstanding)
        queued = max(0, outstanding - running)
        return (f"[ {self.done}/{self.total} done · {running} running "
                f"· {queued} queued · {self.failed} failed ]")

    def started(self, rid):
        # Submit-time bookkeeping only. We do NOT print a "start" line: the pool
        # may not have actually begun this region yet (only `jobs` run at once),
        # so a start line would overstate what's running. Accurate lines are
        # printed when regions FINISH.
        self.running[rid] = time.time()
        if self.dash:
            self._repaint()

    def finished(self, res):
        rid = res["rid"]
        self.running.pop(rid, None)
        if res["ok"]:
            self.done += 1
            f = res.get("findings", {})
            summary = (f"{_fmt(f.get('segments'))} roads · "
                       f"{_fmt(f.get('parking_lot'))} parking · "
                       f"{_fmt(f.get('speed_camera'))} cams · "
                       f"{res['elapsed']:.0f}s")
            line = f"  \u2713 {rid:28s} {summary}"
        else:
            self.failed += 1
            line = f"  \u2717 FAILED {rid:28s} {res.get('error','?')} " \
                   f"(see {res.get('log','logs/'+rid+'.log')})"
        self.order.append(line)
        if not self.dash:
            print(f"{line}   {self._counts()}", flush=True)
        else:
            # Print this completed result as PERMANENT scrollback above the live
            # block: erase the current block, write the line, then repaint below.
            if self._last_lines:
                sys.stdout.write(f"\x1b[{self._last_lines}A\x1b[J")
                self._last_lines = 0
            sys.stdout.write(line + "\n")
            self._repaint()

    def _repaint(self):
        # Build a FIXED-HEIGHT block so the cursor-up count is always correct:
        # 1 header line + one line per worker slot (self.jobs). Completed results
        # are printed as permanent scrollback ABOVE the block (in finished()),
        # never inside it, so the block never grows or shrinks.
        rows = [self._counts()]
        running = sorted(self.running.items(), key=lambda kv: kv[1])
        for i in range(self.jobs):
            if i < len(running):
                rid, t = running[i]
                rows.append(f"  \u25B6 {rid:28s} {time.time()-t:5.0f}s")
            else:
                rows.append("  \u00b7")           # idle slot placeholder
        # move up over the previous block (same height every time)
        if self._last_lines:
            sys.stdout.write(f"\x1b[{self._last_lines}A")
        # write each row cleared to end-of-line; \r guards against stray columns
        buf = []
        for r in rows:
            buf.append("\r" + r + "\x1b[K")
        sys.stdout.write("\n".join(buf) + "\n")
        sys.stdout.flush()
        self._last_lines = len(rows)

    def footer(self):
        # when using the dashboard, drop a final plain copy of every result so
        # the completed run leaves a readable scrollback (not just the last frame)
        if self.dash:
            sys.stdout.write("\n")
            for ln in self.order:
                print(ln)
        print("\n" + self._counts(), flush=True)


def _fmt(n):
    try:
        return f"{int(n):,}"
    except (TypeError, ValueError):
        return str(n)


def _record_result(d, base_url, state, rid, name, parent, sig, res):
    """Record one finished region into state + rewrite manifest (PARENT ONLY).
    Shared by the sequential and parallel paths so resumability is identical."""
    findings = res["findings"]
    prev = state.get(rid, {})
    version = prev.get("version", 0)
    if prev.get("db_sha256") != res["sha"]:
        version = version + 1 if version else 1
    state[rid] = {
        "src_sig": sig,
        "name": name,
        "parent": parent,
        "db_sha256": res["sha"],
        "db_size": res["size"],
        "version": version,
        "data_date": res["data_date"],
        "schema": findings.get("schema_version"),
        "counts": {k: findings.get(k) for k in
                   ("segments", "with_maxspeed", "parking_lot",
                    "parking_curb", "speed_camera")},
        "bbox": res.get("bbox", ""),
        "processed_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    save_state(d, state)
    return write_manifest(d, base_url, state)


def _cleanup_part_files(d):
    """Remove leftover <region>.db.part files from interrupted builds. These are
    never valid outputs (a completed build renames .part -> .db atomically), so
    any .part on disk is debris from a killed worker and is safe to delete."""
    removed = 0
    try:
        for f in os.listdir(d):
            if f.endswith(".db.part"):
                try:
                    os.remove(os.path.join(d, f)); removed += 1
                except OSError:
                    pass
    except OSError:
        pass
    return removed


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
    ap.add_argument("--jobs", "-j", type=int, default=max(1, (os.cpu_count() or 2) - 1),
                    help="process this many regions in parallel (default: CPU "
                         "cores - 1). 1 = sequential with full per-region console "
                         "progress. >1 prints a clean append-only event log (one "
                         "line per start/finish), with each region's detailed "
                         "progress in logs/<region>.log.")
    ap.add_argument("--dashboard", action="store_true",
                    help="in parallel mode, use a live repainting dashboard instead "
                         "of the append-only event log. Only works on a capable "
                         "terminal; if lines overlap, don't use it.")
    a = ap.parse_args()

    d = os.path.abspath(a.dir)
    sys.path.insert(0, d)
    here = os.path.dirname(os.path.abspath(__file__))
    sys.path.insert(0, here)

    # Clear any <region>.db.part debris left by a previous hard-killed run
    # (SIGKILL / power loss, where the orderly Ctrl-C cleanup couldn't run).
    _leftover = _cleanup_part_files(d)
    if _leftover:
        print(f"cleaned {_leftover} leftover .part file(s) from a previous "
              f"interrupted run.", flush=True)

    # Both helper scripts must be present in the same folder as this one (the map
    # folder). Check them up front so a missing file fails immediately with a
    # clear message, rather than an obscure import error or a mid-run subprocess
    # failure part-way through the first region.
    missing = []
    for helper in ("add_parking.py", "osm_to_speedlimitdb.py"):
        if not (os.path.exists(os.path.join(here, helper))
                or os.path.exists(os.path.join(d, helper))):
            missing.append(helper)
    if missing:
        sys.exit("Missing required script(s) in the map folder: "
                 + ", ".join(missing)
                 + ".\nbuild_europe.py needs BOTH add_parking.py and "
                 "osm_to_speedlimitdb.py alongside it (plus the .pbf files).")

    try:
        import add_parking
    except ImportError as e:
        sys.exit(f"Could not import add_parking.py: {e}. It must be in the map "
                 "folder alongside build_europe.py.")

    try:
        import osmium  # noqa: F401  (used by the helper scripts)
    except ImportError:
        sys.exit("pyosmium is not installed. Run:  pip install osmium")

    only = {x.strip() for x in a.only.split(",") if x.strip()}
    state = load_state(d)

    # ------------------------------------------------------------------
    # Stale-entry cleanup: drop any region from state whose .db no longer
    # exists on disk, so deleting a .db file cleanly removes that region from
    # both .build_state.json and index.json on the next run. Reported below.
    # ------------------------------------------------------------------
    removed_ids = []
    for rid in list(state.keys()):
        if not os.path.exists(os.path.join(d, f"{rid}.db")):
            removed_ids.append(rid)
            del state[rid]
    if removed_ids:
        save_state(d, state)
        print("Removed from state (their .db is gone):")
        for rid in sorted(removed_ids):
            print(f"  - {rid}")
        print()

    # ------------------------------------------------------------------
    # Discover candidate .pbf files, in the root folder AND one level of
    # sub-folders. A sub-folder is treated as a PARENT region named by the
    # folder; each .pbf inside is a SUB-REGION with id "<parent>__<sub>".
    #   root/greece-latest.osm.pbf          -> id "greece"        (full country)
    #   root/germany/bayern-latest.osm.pbf  -> id "germany__bayern" parent "germany"
    # The "__" separator is unambiguous: Geofabrik ids use single hyphens only,
    # so a hyphenated country (ireland-and-northern-ireland) can never collide
    # with a parent__sub sub-region id or its .db filename.
    # candidates[id] = {pbf, mtime, name, parent}
    # ------------------------------------------------------------------
    candidates = {}
    skipped = []
    # display names + parent, filled as we discover
    disp = {}       # id -> display name
    parent_of = {}  # id -> parent id (or None)

    def consider(path, rid, name, parent):
        if only and rid not in only and (parent or rid) not in only:
            return
        mt = os.stat(path).st_mtime
        if rid not in candidates or mt > candidates[rid]["mtime"]:
            candidates[rid] = {"pbf": path, "mtime": mt, "name": name, "parent": parent}
            disp[rid] = name
            parent_of[rid] = parent

    # (1) root-level files: recognised full-country regions.
    root_pbfs = [f for f in os.listdir(d)
                 if f.endswith(".osm.pbf") and os.path.isfile(os.path.join(d, f))]
    for f in sorted(root_pbfs):
        rid = region_id_from_pbf(f)
        if rid in EXCLUDED_IDS:
            skipped.append((f, "special sub-region / excluded")); continue
        if rid not in EUROPE_REGIONS:
            skipped.append((f, "not a recognised Europe country")); continue
        consider(os.path.join(d, f), rid, EUROPE_REGIONS[rid], None)

    # Build a lookup from several folder-name spellings to the canonical region
    # id, so a sub-folder can be named by its Geofabrik id ("czech-republic"),
    # its display name ("Czech Republic", "Russian Federation"), or a mix of
    # case and space/hyphen. Without this, a folder named with a space or the
    # display name is silently ignored and its sub-regions never processed.
    def _norm(s):
        return s.strip().lower().replace(" ", "").replace("-", "").replace("_", "")
    folder_to_id = {}
    for _id, _disp in EUROPE_REGIONS.items():
        folder_to_id[_norm(_id)] = _id       # e.g. "czechrepublic" -> czech-republic
        folder_to_id[_norm(_disp)] = _id     # e.g. "russianfederation" -> russia

    # (2) sub-folders: each is a parent region; files inside are sub-regions.
    for entry in sorted(os.listdir(d)):
        sub = os.path.join(d, entry)
        if not os.path.isdir(sub):
            continue
        parent_id = folder_to_id.get(_norm(entry))
        if parent_id is None:
            # Not a folder named after a known country (by id or display name);
            # ignore unrelated dirs (e.g. __pycache__, output dirs).
            continue
        parent_name = EUROPE_REGIONS[parent_id]
        sub_pbfs = [f for f in os.listdir(sub) if f.endswith(".osm.pbf")]
        # Only let a sub-folder REPLACE the full-country file if it actually
        # contains sub-region .pbf files. An empty (or .pbf-less) folder must NOT
        # cause the full country to be dropped — that would silently skip the
        # region entirely.
        if not sub_pbfs:
            print(f"  note: '{entry}/' sub-folder has no .osm.pbf files — "
                  f"ignoring it and keeping the full {parent_name} file if present.")
            continue
        # If a full-country file for this parent is ALSO in root, warn & skip it
        # (sub-regions replace the full country to avoid the huge download).
        if parent_id in candidates and candidates[parent_id]["parent"] is None:
            print(f"  note: '{entry}/' sub-folder present, so ignoring the full "
                  f"{parent_name} file in root (sub-regions replace it).")
            del candidates[parent_id]
        for f in sorted(sub_pbfs):
            sub_stem = region_id_from_pbf(f)
            # Use "__" (never present in a Geofabrik region id, which uses only
            # single hyphens) to join parent and sub-region. This makes the id —
            # and therefore the .db filename — unambiguous: a full country like
            # "ireland-and-northern-ireland" can never collide with a sub-region
            # id, and parent-vs-sub is decidable from the filename by splitting on
            # "__". e.g. germany/bayern-latest.osm.pbf -> "germany__bayern".
            rid = f"{parent_id}__{sub_stem}"
            name = f"{parent_name} / {sub_stem.replace('-', ' ').title()}"
            consider(os.path.join(sub, f), rid, name, parent_id)

    if not candidates:
        sys.exit(f"No usable *.osm.pbf files found in {d} or its region sub-folders")

    n_sub = sum(1 for c in candidates.values() if c["parent"])
    print(f"Found {len(candidates)} region file(s) to consider "
          f"({len(candidates)-n_sub} full, {n_sub} sub-region)"
          + (f"; skipping {len(skipped)} non-Europe/special file(s)"
             if skipped else "") + ".\n")
    for f, why in skipped:
        print(f"  skip {f}: {why}")
    if skipped:
        print()

    processed, skipped_uptodate, failed = [], [], []
    added_ids = []
    t_all = time.time()

    # Build the work list: regions that actually need (re)building, after the
    # up-to-date skip. Each entry carries everything a worker needs.
    todo = []
    for rid in sorted(candidates):
        info = candidates[rid]
        pbf = info["pbf"]
        name = info["name"]
        sig = src_signature(pbf)
        prev = state.get(rid, {})
        if not a.force and prev.get("src_sig") == sig and os.path.exists(f"{rid}.db"):
            label = f"{name} ({rid})"
            print(f"  {label:<40s}  up-to-date, skipping (source unchanged)")
            skipped_uptodate.append(rid)
            continue
        todo.append((rid, info, pbf, name, sig))

    jobs = max(1, a.jobs)
    if jobs > 1 and len(todo) > 1:
        # ---------------- PARALLEL PATH ----------------
        import concurrent.futures as _cf
        n = min(jobs, len(todo))
        print(f"\nProcessing {len(todo)} region(s) with {n} parallel worker(s). "
              f"Per-region detail is in logs/<region>.log.", flush=True)
        print("  (Ctrl-C once to stop cleanly: in-progress regions are discarded, "
              "finished ones are saved; just re-run to resume.)\n", flush=True)
        rep = _Reporter(len(todo), n, a.dashboard and _dashboard_supported())
        meta = {rid: (info, name, sig) for (rid, info, pbf, name, sig) in todo}
        interrupted = False
        # Workers ignore SIGINT so a Ctrl-C goes only to the parent, which then
        # shuts the pool down in an orderly way (no stack-trace spew from every
        # child, no orphans). The initializer MUST be a module-level function:
        # on Windows the pool spawns workers and pickles the initializer, and
        # nested/local functions are not picklable.
        ex = _cf.ProcessPoolExecutor(max_workers=n,
                                     initializer=_worker_ignore_sigint)
        try:
            futs = {}
            for (rid, info, pbf, name, sig) in todo:
                fut = ex.submit(_worker_build_region,
                                (d, rid, name, pbf, a.skip_base))
                futs[fut] = rid
                rep.started(rid)
            for fut in _cf.as_completed(futs):
                res = fut.result()
                rid = res["rid"]
                info, name, sig = meta[rid]
                rep.finished(res)
                if res["ok"]:
                    # PARENT does all shared-state writes — never the workers.
                    if rid not in state:
                        added_ids.append(rid)
                    _record_result(d, a.base_url, state, rid, name,
                                   info["parent"], sig, res)
                    processed.append(rid)
                else:
                    failed.append(rid)
        except KeyboardInterrupt:
            interrupted = True
            print("\n\nInterrupted — stopping. Regions already finished are saved "
                  "in index.json; in-progress regions were discarded (no partial "
                  ".db left behind). Re-run the same command to resume where this "
                  "left off.", flush=True)
            # cancel_futures=True (py3.9+) drops not-yet-started tasks; running
            # workers ignore SIGINT and finish or are killed on shutdown. We do
            # NOT wait for the big in-flight regions — their .part files are
            # ignored on resume.
            ex.shutdown(wait=False, cancel_futures=True)
        else:
            ex.shutdown(wait=True)
        rep.footer()
        if interrupted:
            # Clean up any stray .part files from killed workers so the folder
            # stays tidy (resume ignores them regardless).
            _cleanup_part_files(d)
            print(f"\nStopped after {len(processed)} region(s) this run. "
                  f"Re-run to continue.", flush=True)
            return
    else:
        # ---------------- SEQUENTIAL PATH (unchanged behaviour) ----------------
        for (rid, info, pbf, name, sig) in todo:
            prev = state.get(rid, {})
            is_new = rid not in state
            print(f"\n{'='*70}\n== {name} ({rid}) — processing "
                  f"{os.path.basename(pbf)} ==\n{'='*70}", flush=True)
            t0 = time.time()
            try:
                db_path = process_one(pbf, rid, name, add_parking, a.skip_base)
            except Exception as e:
                print(f"  FAILED: {e.__class__.__name__}: {e}", flush=True)
                failed.append(rid)
                continue
            dt = time.time() - t0

            ok, findings = verify_db(db_path)
            print(f"\n  --- {name} summary ---")
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

            res = {
                "findings": findings, "sha": sha256_of(db_path),
                "size": os.path.getsize(db_path), "data_date": pbf_date(pbf),
                "bbox": db_meta(db_path).get("bbox", ""),
            }
            if is_new:
                added_ids.append(rid)
            n_listed = _record_result(d, a.base_url, state, rid, name,
                                      info["parent"], sig, res)
            print(f"  index.json updated: {n_listed} region(s) listed so far",
                  flush=True)
            processed.append(rid)

    # ----- final manifest refresh (also written after each region above) -----
    regions_listed = write_manifest(d, a.base_url, state)

    # ----- comprehensive final summary -----
    print(f"\n{'='*70}\nBATCH COMPLETE in {time.time()-t_all:.0f}s\n{'='*70}")

    # Changes to the JSON files this run.
    print("JSON changes this run:")
    if added_ids:
        print(f"  added ({len(added_ids)}): {', '.join(sorted(added_ids))}")
    if removed_ids:
        print(f"  removed ({len(removed_ids)}): {', '.join(sorted(removed_ids))}")
    reprocessed = [r for r in processed if r not in added_ids]
    if reprocessed:
        print(f"  updated ({len(reprocessed)}): {', '.join(sorted(reprocessed))}")
    if not (added_ids or removed_ids or reprocessed):
        print("  none")

    print(f"\nRun outcome:")
    print(f"  processed now   : {len(processed)}")
    print(f"  up-to-date skip : {len(skipped_uptodate)}")
    print(f"  failed          : {len(failed)}  {failed if failed else ''}")

    # Full per-region table + feature totals across everything in the manifest.
    print(f"\nAll regions in index.json ({regions_listed}):")
    print(f"  {'region':28s} {'roads':>9s} {'w/lim':>8s} {'parking':>8s} "
          f"{'curb':>6s} {'cams':>6s} {'MB':>7s}")
    tot = {"segments": 0, "with_maxspeed": 0, "parking_lot": 0,
           "parking_curb": 0, "speed_camera": 0, "bytes": 0}
    def _i(v):
        try: return int(v)
        except (TypeError, ValueError): return 0
    for rid in sorted(state.keys()):
        if not os.path.exists(os.path.join(d, f"{rid}.db")):
            continue
        info = state[rid]
        c = info.get("counts", {})
        seg = _i(c.get("segments")); wl = _i(c.get("with_maxspeed"))
        pl = _i(c.get("parking_lot")); pc = _i(c.get("parking_curb"))
        cam = _i(c.get("speed_camera")); mb = info.get("db_size", 0) / 1e6
        tot["segments"] += seg; tot["with_maxspeed"] += wl
        tot["parking_lot"] += pl; tot["parking_curb"] += pc
        tot["speed_camera"] += cam; tot["bytes"] += info.get("db_size", 0)
        print(f"  {info.get('name', rid)[:28]:28s} {seg:>9,} {wl:>8,} "
              f"{pl:>8,} {pc:>6,} {cam:>6,} {mb:>7.1f}")
    print(f"  {'-'*28} {'-'*9} {'-'*8} {'-'*8} {'-'*6} {'-'*6} {'-'*7}")
    print(f"  {'TOTAL':28s} {tot['segments']:>9,} {tot['with_maxspeed']:>8,} "
          f"{tot['parking_lot']:>8,} {tot['parking_curb']:>6,} "
          f"{tot['speed_camera']:>6,} {tot['bytes']/1e6:>7.1f}")

    print(f"\nUpload every changed .db AND index.json to {a.base_url}")
    print("(data files FIRST, index.json LAST — see SERVER-DEPLOYMENT.md)")


if __name__ == "__main__":
    main()
