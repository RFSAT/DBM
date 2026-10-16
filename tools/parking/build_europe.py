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
  - add_pois.py               (imported; adds fuel/charging/hospital/rest area —
                               optional stage 3; skipped if already present or
                               with --skip-pois; absence does not fail the build)
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
            return json.load(open(p, encoding="utf-8"))
        except Exception:
            return {}
    # No build state (fresh folder, or it was reset/lost). Seed each region's
    # version + sha from a previously PUBLISHED index.json in the same folder, so
    # a rebuild continues the version sequence instead of resetting everyone to 1
    # — otherwise phones holding the old v1 never see the new maps as an update.
    return _seed_state_from_index(d)


def _seed_state_from_index(d):
    idx = os.path.join(d, "index.json")
    if not os.path.exists(idx):
        return {}
    try:
        cat = json.load(open(idx, encoding="utf-8"))
    except Exception:
        return {}
    seeded = {}
    for r in cat.get("regions", []):
        rid = r.get("id")
        if not rid:
            continue
        # Carry forward version + sha only. src_sig is deliberately omitted so the
        # region is treated as "needs rebuild" (the .db content is about to change);
        # _record_result then bumps the version because the new sha won't match.
        seeded[rid] = {
            "name": r.get("name"),
            "parent": r.get("parent"),
            "db_sha256": r.get("sha256", ""),
            "version": int(r.get("version", 0) or 0),
        }
    if seeded:
        print(f"note: no {STATE_FILE} found — seeded versions for "
              f"{len(seeded)} region(s) from the existing index.json so rebuilt "
              f"maps get a higher version.", flush=True)
    return seeded


def save_state(d, state):
    json.dump(state, open(os.path.join(d, STATE_FILE), "w"), indent=2)


# Cache of the PUBLISHED index.json (the version phones currently hold), read once
# and used to anchor new versions so a rebuild is always seen as an update.
_PUBLISHED = None


def _published_index(d):
    """{rid: {version, dataDate, sha256}} from the index.json in the build folder
    — treated as the currently-published catalogue. Read once and cached."""
    global _PUBLISHED
    if _PUBLISHED is not None:
        return _PUBLISHED
    _PUBLISHED = {}
    idx = os.path.join(d, "index.json")
    if os.path.exists(idx):
        try:
            cat = json.load(open(idx, encoding="utf-8"))
            for r in cat.get("regions", []):
                rid = r.get("id")
                if rid:
                    _PUBLISHED[rid] = {
                        "version": int(r.get("version", 0) or 0),
                        "dataDate": r.get("dataDate", ""),
                        "sha256": r.get("sha256", ""),
                    }
        except Exception:
            pass
    return _PUBLISHED


def _next_version(d, rid, new_date, new_sha, prev_state_version):
    """Decide a region's version so updates ALWAYS work, robust to a lost/reset
    build state (which kept resetting everyone to 1). Rules:
      - Anchor to the PUBLISHED index.json version (what phones currently have).
      - If the new data is NEWER (by dataDate) or CHANGED (by sha256), publish a
        HIGHER number than both the published and the prior state version.
      - Otherwise keep the published version (no spurious bump).
      - Never go backwards.
    A --min-version floor (DBM_MIN_VERSION) can force at least a given number.
    """
    pub = _published_index(d).get(rid, {})
    pub_ver = int(pub.get("version", 0) or 0)
    pub_date = pub.get("dataDate", "")
    pub_sha = pub.get("sha256", "")
    floor = int(os.environ.get("DBM_MIN_VERSION", "0") or "0")

    base = max(pub_ver, int(prev_state_version or 0), floor)
    changed = (new_sha != pub_sha) or (str(new_date) > str(pub_date))
    if base == 0:
        # brand-new region never published before
        return max(1, floor)
    if changed:
        # newer/changed data -> must exceed what's out there so the app updates
        return base + 1 if (new_sha != pub_sha or str(new_date) > str(pub_date)) \
            else base
    return base


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
        entry = {
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
        }
        # Compression: only advertise it if the .db.gz actually exists on disk, so
        # the app never tries to fetch a .gz that isn't deployed.
        if info.get("compressed") and os.path.exists(db_path + ".gz"):
            entry["compressed"] = True
            entry["compressedSize"] = info.get("compressed_size", 0)
        regions.append(entry)
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
        # optional extra POIs (present only after add_pois has run)
        findings["fuel"] = count("fuel")
        findings["charging"] = count("charging")
        findings["hospital"] = count("hospital")
        findings["rest_area"] = count("rest_area")
        findings["level_crossing"] = count("level_crossing")
        findings["speed_bump"] = count("speed_bump")
        findings["toll_booth"] = count("toll_booth")
        findings["border_control"] = count("border_control")
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
    # parking/camera tables present. Schema 5 = roads + parking + cameras; 6 adds
    # the cameras' resolved travel direction. Both are complete, so either passes.
    # A missing segments table means the base speed-limit stage didn't run —
    # that's a hard fail.
    ok = (str(findings.get("schema_version")) in ("5", "6")
          and findings.get("segments") not in (None, 0)
          and findings.get("speed_camera") is not None
          and findings.get("parking_lot") is not None
          and (findings.get("cameras_out_of_bounds") in (0, None)))
    return ok, findings


def _pois_already_done(db_path):
    """True if this db already has the extra POIs extracted (meta marker set and
    at least the tables present), so the POI stage can be skipped to avoid a
    second full PBF scan."""
    try:
        con = sqlite3.connect(db_path)
        try:
            m = dict(con.execute("SELECT key,value FROM meta").fetchall())
            if m.get("poi_extracted") != "1":
                return False
            # confirm the tables actually exist (guards a stale marker)
            names = {r[0] for r in con.execute(
                "SELECT name FROM sqlite_master WHERE type='table'").fetchall()}
            return {"fuel", "charging", "hospital", "rest_area"}.issubset(names)
        finally:
            con.close()
    except sqlite3.Error:
        return False


def _run_pois(pbf, db_path, region_name, emit, progress_cb):
    """Step 3: extract the extra POIs (fuel/charging/hospital/rest area) into the
    db. Skips the work if this db already has them (avoids a second PBF scan).
    Best-effort: if the add_pois module or pyosmium is unavailable, it logs and
    returns without failing the build — base + parking data remain valid."""
    if os.environ.get("DBM_SKIP_POIS") == "1":
        print("  [3/3] extra POIs: skipped (--skip-pois)", flush=True)
        return
    if _pois_already_done(db_path):
        emit("pois", "extra POIs already present — skipping")
        print("  [3/3] extra POIs: already present, skipped", flush=True)
        return
    try:
        import add_pois
    except ImportError as e:
        print(f"  [3/3] extra POIs: add_pois not available ({e}) — skipped",
              flush=True)
        return
    emit("pois")
    print(f"\n  [3/3] extra POIs (fuel/charging/hospital/rest)", flush=True)
    try:
        add_pois.run(pbf, db_path,
                     progress_cb=(lambda ph, ln: emit(ph, ln))
                     if progress_cb is not None else None)
    except SystemExit as e:
        # add_pois.run calls sys.exit on missing pyosmium; downgrade to a warning
        # so the overall build still completes with base + parking data.
        print(f"  [3/3] extra POIs skipped: {e}", flush=True)


def _run_parking_pois(pbf, db_path, region_name, add_parking, emit, progress_cb):
    """Steps 2+3 in ONE pass: parking+cameras AND the extra POIs, via
    add_parking_pois.run(). Falls back to the two separate stages if that module
    (or pyosmium) is unavailable, so a missing file never breaks the build. POIs
    are skipped here entirely if --skip-pois was given (then only parking runs)."""
    skip_pois = os.environ.get("DBM_SKIP_POIS") == "1"
    if not skip_pois:
        try:
            import add_parking_pois
            print("  [2/2 combined] parking + cameras + POIs (single pass)",
                  flush=True)
            emit("parking+pois")
            add_parking_pois.run(
                pbf, db_path,
                progress_cb=(lambda ph, ln: emit(ph, ln))
                if progress_cb is not None else None)
            return
        except ImportError:
            print("  note: add_parking_pois not available — running the two "
                  "stages separately", flush=True)
        except SystemExit as e:
            print(f"  combined pass unavailable ({e}) — running stages separately",
                  flush=True)
    # Fallback (or --skip-pois): the original two-stage flow.
    emit("parking")
    print("  [2/3] parking + cameras", flush=True)
    add_parking.run(pbf, db_path,
                    progress_cb=(lambda ph, ln: emit(ph, ln))
                    if progress_cb is not None else None)
    _run_pois(pbf, db_path, region_name, emit, progress_cb)


def process_one(pbf, region_id, region_name, add_parking, skip_base, log_fh=None,
                progress_cb=None):
    """Build <region>.db with FULL features: speed limits (base converter) then
    parking + cameras (add_parking). Returns the db path.

    The two stages compose safely: osm_to_speedlimitdb creates segments+meta
    (schema 3); add_parking preserves segments and adds parking/camera tables
    (schema 5). Order is enforced here — base first, always.

    ATOMICITY: when building fresh, both steps write to <region>.db.part and the
    result is renamed to <region>.db only after BOTH steps succeed.

    log_fh: if given, the base-converter SUBPROCESS's output is written there.
    progress_cb: if given, called with (phase, latest_line) as work proceeds so a
    parent dashboard can show live per-region progress. Both can be set together
    (parallel mode): full detail to the log, latest line to the dashboard.
    """
    final_db = f"{region_id}.db"

    def emit(phase, line=""):
        if progress_cb is not None:
            try:
                progress_cb(phase, line)
            except Exception:
                pass

    if skip_base:
        if not os.path.exists(final_db):
            # Raised, not sys.exit: in a parallel run this is ONE region's
            # problem, and SystemExit would bypass the worker's error handling
            # and take the whole sweep down with it.
            raise RuntimeError(
                f"--skip-base given but {final_db} does not exist. Run the "
                f"base speed-limit conversion first, or drop --skip-base.")
        _run_parking_pois(pbf, final_db, region_name, add_parking, emit,
                          progress_cb)
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
    emit("scanning")
    import subprocess
    if progress_cb is not None:
        # Stream the subprocess output so we can forward the latest progress line
        # to the dashboard AND write the full detail to the log. We read combined
        # stdout+stderr line by line. \r-updated counter lines are split on \r so
        # we forward the most recent counter, not a whole buffered blob.
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, bufsize=1,
                                universal_newlines=True)
        buf = ""
        for chunk in iter(lambda: proc.stdout.read(1), ""):
            if chunk in ("\n", "\r"):
                line = buf.strip()
                buf = ""
                if line:
                    if log_fh is not None:
                        log_fh.write(line + "\n"); log_fh.flush()
                    # forward a compact version as the live line
                    emit("scanning", line)
            else:
                buf += chunk
        proc.stdout.close()
        rc = proc.wait()
        if rc != 0:
            raise subprocess.CalledProcessError(rc, cmd)
    elif log_fh is not None:
        log_fh.flush()
        subprocess.run(cmd, check=True, stdout=log_fh, stderr=subprocess.STDOUT)
    else:
        subprocess.run(cmd, check=True)
    _run_parking_pois(pbf, work_db, region_name, add_parking, emit, progress_cb)
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
    """Worker process: build ONE region's .db (both steps). Sends live progress
    to the parent via a shared queue (rid, kind, phase, line, elapsed) so the
    parent can paint a per-region dashboard, AND writes full detail to
    logs/<rid>.log. Returns a plain-dict result the parent uses to record state.
    Must be module-level (picklable). Never touches index.json/.build_state.json."""
    d, rid, name, pbf, skip_base, q = args
    import contextlib
    try:
        os.chdir(d)
    except OSError:
        pass
    logdir = os.path.join(d, "logs")
    os.makedirs(logdir, exist_ok=True)
    logpath = os.path.join(logdir, f"{rid}.log")
    t0 = time.time()

    def send(kind, phase="", line=""):
        if q is not None:
            try:
                q.put((rid, kind, phase, line, time.time() - t0))
            except Exception:
                pass

    def progress_cb(phase, line=""):
        send("progress", phase, line)

    try:
        import add_parking
    except Exception as e:
        send("error", "", f"import add_parking: {e}")
        return {"rid": rid, "ok": False, "error": f"import add_parking: {e}",
                "elapsed": 0.0, "log": logpath}
    send("start")
    try:
        with open(logpath, "w", encoding="utf-8") as lf:
            # add_parking runs in-process; capture its prints into the log too.
            with contextlib.redirect_stdout(lf), contextlib.redirect_stderr(lf):
                db_path = process_one(pbf, rid, name, add_parking, skip_base,
                                      log_fh=lf, progress_cb=progress_cb)
        ok, findings = verify_db(db_path)
        res = {
            "rid": rid, "ok": True, "db_path": db_path, "findings": findings,
            "verify_ok": ok, "bbox": db_meta(db_path).get("bbox", ""),
            "size": os.path.getsize(db_path), "sha": sha256_of(db_path),
            "data_date": pbf_date(pbf), "elapsed": time.time() - t0,
            "log": logpath,
        }
        send("done", "done")
        return res
    except Exception as e:
        send("error", "", f"{e.__class__.__name__}: {e}")
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


class _Dashboard:
    """Live per-region progress display for parallel mode. Shows one line per
    ACTIVE region with its current phase and latest progress detail, repainted in
    place, plus a header counter. Completed regions scroll above as permanent
    lines. Falls back to plain append-only output when the terminal can't repaint
    (redirected to a file, dumb console, or --no-dashboard).

    Fed by worker queue messages via update(); completions via finish(); the
    parent calls tick() regularly to repaint on a steady cadence."""

    _SPIN = "|/-\\"

    def __init__(self, total, jobs, use_live):
        self.total = total
        self.jobs = jobs
        self.live = use_live
        self.done = 0
        self.failed = 0
        self.t_start = time.time()
        self.active = {}       # rid -> {phase, line, elapsed, last}
        self.slots = [None] * jobs   # slot index -> rid (stable display order)
        self._last_lines = 0
        self._spin_i = 0
        self._last_paint = 0.0
        self._fallback_last = {}   # rid -> last phase we printed (fallback mode)
        if self.live and os.name == "nt":
            # enable ANSI/VT on Windows 10+ consoles
            try:
                import ctypes
                k = ctypes.windll.kernel32
                mode = ctypes.c_uint32()
                h = k.GetStdHandle(-11)
                k.GetConsoleMode(h, ctypes.byref(mode))
                k.SetConsoleMode(h, mode.value | 0x0004)  # ENABLE_VT_PROCESSING
            except Exception:
                self.live = False

    def _counts(self):
        outstanding = self.total - self.done - self.failed
        running = min(self.jobs, max(0, outstanding))
        queued = max(0, outstanding - running)
        el = int(time.time() - self.t_start)
        m, s = divmod(el, 60)
        return (f"[ {self.done}/{self.total} done · {running} running · "
                f"{queued} queued · {self.failed} failed ]  {m}m{s:02d}s")

    # ---- inbound events -----------------------------------------------------
    def update(self, rid, kind, phase, line, elapsed):
        if kind in ("start", "progress"):
            if rid not in self.active:
                # first time we see this region — give it the first free slot
                # and keep it there until it finishes (stable row order).
                self.active[rid] = {"phase": "", "line": "", "elapsed": 0}
                for i in range(len(self.slots)):
                    if self.slots[i] is None:
                        self.slots[i] = rid
                        break
            a = self.active[rid]
            if phase:
                a["phase"] = phase
            if line:
                a["line"] = line
            a["elapsed"] = elapsed
            # fallback mode: print a line only when the PHASE changes, so we
            # don't spam, but still show meaningful movement per region.
            if not self.live:
                ph = a["phase"]
                if self._fallback_last.get(rid) != ph and ph:
                    self._fallback_last[rid] = ph
                    print(f"  · {rid:28s} {ph}", flush=True)
        elif kind in ("done", "error"):
            # The worker has signalled it finished this region. Free its live slot
            # NOW (and drop it from the active set) so a queued region can take the
            # row immediately — don't wait for the future to be reaped, which can
            # lag the queue message and leave finished regions pinned in the list
            # (showing e.g. "pois: done") while new ones can't appear. The
            # authoritative finish() (recording + the permanent ✓/✗ line) still
            # runs when the future is reaped; it's a no-op on the slot by then.
            self.active.pop(rid, None)
            for i in range(len(self.slots)):
                if self.slots[i] == rid:
                    self.slots[i] = None
                    break

    def finish(self, rid, res, ok):
        self.active.pop(rid, None)
        # free this region's slot so a queued region can take it
        for i in range(len(self.slots)):
            if self.slots[i] == rid:
                self.slots[i] = None
                break
        if ok:
            self.done += 1
            f = res.get("findings", {})
            # Fixed, aligned columns — every feature type is always in the same
            # position (zeros shown) so regions line up and are comparable at a
            # glance. Each is "label=<count>" with a right-padded count.
            def _n(key):
                try:
                    return int(f.get(key) or 0)
                except (TypeError, ValueError):
                    return 0
            cells = [
                ("roads", _n("segments"), 7),
                ("park",  _n("parking_lot"), 5),
                ("cam",   _n("speed_camera"), 4),
                ("fuel",  _n("fuel"), 4),
                ("chrg",  _n("charging"), 4),
                ("hosp",  _n("hospital"), 4),
                ("rest",  _n("rest_area"), 4),
                ("xing",  _n("level_crossing"), 5),
                ("bump",  _n("speed_bump"), 6),
                ("toll",  _n("toll_booth"), 4),
                ("bord",  _n("border_control"), 4),
            ]
            summary = " ".join(f"{lbl}={v:>{w},}" for lbl, v, w in cells)
            line = f"  \u2713 {rid:26s} {summary}  {res['elapsed']:.0f}s"
        else:
            self.failed += 1
            line = (f"  \u2717 FAILED {rid:28s} {res.get('error','?')} "
                    f"(see {res.get('log','logs/'+rid+'.log')})")
        # print the completed line as permanent scrollback above the live block
        if self.live and self._last_lines:
            sys.stdout.write(f"\x1b[{self._last_lines}A\x1b[J")
            self._last_lines = 0
        print(line, flush=True)
        if self.live:
            self._paint(force=True)

    # ---- painting -----------------------------------------------------------
    def tick(self):
        if self.live:
            self._paint()

    def _shorten(self, s, width):
        s = s.strip()
        return s if len(s) <= width else s[:width - 1] + "…"

    def _fit(self, s, width):
        """Truncate-or-pad to EXACTLY width chars so columns always align."""
        s = s.strip()
        if len(s) > width:
            return s[:width - 1] + "…"
        return s + " " * (width - len(s))

    def _paint(self, force=False):
        now = time.time()
        if not force and (now - self._last_paint) < 0.25:
            return
        self._last_paint = now
        self._spin_i = (self._spin_i + 1) % len(self._SPIN)
        spin = self._SPIN[self._spin_i]
        NAME_W = 26      # region id column (truncated/padded to this)
        PHASE_W = 8      # phase column
        # Detail fills the rest of the terminal width. Fixed overhead before the
        # detail column is: 2 (indent) +1 (spin) +1 +NAME_W +1 +5 (" 9999s") +1
        # +PHASE_W +1 = NAME_W + PHASE_W + 12.
        try:
            term_w = os.get_terminal_size().columns
        except OSError:
            term_w = 128
        # Use the FULL terminal width. (Floor of 80 only applies if detection
        # gave something unusable.)
        total_w = max(80, term_w)
        DETAIL_W = max(20, total_w - (NAME_W + PHASE_W + 12))
        # fixed-height block: header + one row per worker SLOT, in stable order
        rows = ["  " + self._counts()]
        for i in range(self.jobs):
            rid = self.slots[i] if i < len(self.slots) else None
            if rid and rid in self.active:
                a = self.active[rid]
                phase = (a.get("phase", "") or "…")
                el = int(a.get("elapsed", 0))
                name = self._fit(rid, NAME_W)
                ph = self._fit(phase, PHASE_W)
                detail = self._fit(a.get("line", ""), DETAIL_W)
                rows.append(f"  {spin} {name} {el:4d}s {ph} {detail}")
            else:
                rows.append("  ·")
        if self._last_lines:
            sys.stdout.write(f"\x1b[{self._last_lines}A")
        out = [("\r" + r + "\x1b[K") for r in rows]
        sys.stdout.write("\n".join(out) + "\n")
        sys.stdout.flush()
        self._last_lines = len(rows)

    def stop(self):
        # clear the live block; the completed scrollback lines remain above
        if self.live and self._last_lines:
            sys.stdout.write(f"\x1b[{self._last_lines}A\x1b[J")
            self._last_lines = 0
            sys.stdout.flush()
        print("  " + self._counts(), flush=True)


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
    # Version anchored to the published index.json + data date, so a rebuild with
    # newer/changed data always outranks what phones hold (fixes the "stuck at 1"
    # problem where a reset build state reset every version to 1).
    version = _next_version(d, rid, res.get("data_date", ""), res["sha"],
                            prev.get("version", 0))
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
                    "parking_curb", "speed_camera",
                    "fuel", "charging", "hospital", "rest_area",
                    "level_crossing", "speed_bump", "toll_booth",
                    "border_control")},
        "bbox": res.get("bbox", ""),
        "processed_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    # Optional gzip for deployment: produce <rid>.db.gz alongside the .db so the
    # server can host the compressed form and the app streams+decompresses it.
    # sha256/sizeBytes in the manifest stay as the DECOMPRESSED values, so the
    # app verifies the final .db regardless of transport compression.
    if os.environ.get("DBM_COMPRESS") == "1":
        db_path = os.path.join(d, f"{rid}.db")
        gz_path = db_path + ".gz"
        try:
            import gzip as _gzip, shutil as _shutil
            with open(db_path, "rb") as fin, \
                 _gzip.open(gz_path, "wb", compresslevel=9) as fout:
                _shutil.copyfileobj(fin, fout, length=1 << 20)
            state[rid]["compressed"] = True
            state[rid]["compressed_size"] = os.path.getsize(gz_path)
        except OSError as e:
            print(f"  warn: gzip failed for {rid}: {e} — serving uncompressed",
                  flush=True)
            state[rid]["compressed"] = False
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
    ap.add_argument("--skip-pois", action="store_true",
                    help="skip the extra-POI stage (fuel/charging/hospital/rest "
                         "area). The base speed-limit + parking/camera data is "
                         "still built. Use if pyosmium POI extraction is unwanted.")
    ap.add_argument("--min-version", type=int, default=0,
                    help="force every (re)built region to at least this version "
                         "number. Use once to jump past a manually-corrected "
                         "index.json version (e.g. --min-version 5 when your maps "
                         "are currently v4).")
    ap.add_argument("--no-compress", dest="compress", action="store_false",
                    help="do NOT gzip the maps. By default each built <region>.db "
                         "is also written as <region>.db.gz and marked in "
                         "index.json, so the server hosts the compressed form and "
                         "the app decompresses on download (~60-70%% smaller; "
                         "sha256 stays the decompressed value). Pass this to keep "
                         "plain uncompressed .db only (e.g. for local testing).")
    ap.set_defaults(compress=True)
    ap.add_argument("--jobs", "-j", type=int, default=max(1, (os.cpu_count() or 2) - 1),
                    help="process this many regions in parallel (default: CPU "
                         "cores - 1). 1 = sequential with full per-region console "
                         "progress. >1 prints a clean append-only event log (one "
                         "line per start/finish), with each region's detailed "
                         "progress in logs/<region>.log.")
    ap.add_argument("--no-dashboard", action="store_true",
                    help="in parallel mode, disable the live per-region dashboard "
                         "and print plain phase-change lines instead. Use this if "
                         "your terminal can't repaint cleanly. (The dashboard also "
                         "auto-disables when output is redirected to a file.)")
    a = ap.parse_args()

    # Propagate --skip-pois via env so parallel worker processes (spawned) inherit
    # it; _run_pois reads DBM_SKIP_POIS.
    if a.skip_pois:
        os.environ["DBM_SKIP_POIS"] = "1"
    # Compression is on by default (--no-compress opts out).
    if a.compress:
        os.environ["DBM_COMPRESS"] = "1"
    else:
        os.environ.pop("DBM_COMPRESS", None)
    if a.min_version and a.min_version > 0:
        os.environ["DBM_MIN_VERSION"] = str(a.min_version)

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
    poi_topup = []      # up-to-date regions that only need the POI stage added
    for rid in sorted(candidates):
        info = candidates[rid]
        pbf = info["pbf"]
        name = info["name"]
        sig = src_signature(pbf)
        prev = state.get(rid, {})
        if not a.force and prev.get("src_sig") == sig and os.path.exists(f"{rid}.db"):
            label = f"{name} ({rid})"
            # Source unchanged AND base/parking already built. But if this db was
            # built before POI extraction existed, it still lacks the extra POIs —
            # add just those (a quick top-up) rather than a full rebuild or an
            # outright skip. If it already has POIs, it's genuinely up to date.
            if not _pois_already_done(f"{rid}.db"):
                print(f"  {label:<40s}  up-to-date; adding missing POIs")
                poi_topup.append((rid, info, pbf, name))
            else:
                print(f"  {label:<40s}  up-to-date, skipping (source unchanged)")
                skipped_uptodate.append(rid)
            continue
        todo.append((rid, info, pbf, name, sig))

    # Process SMALLEST maps first (biggest last), by source .pbf size. With many
    # parallel workers this lets the large majority of regions finish quickly and
    # leaves only the few giants (Germany, France, Italy...) running at the end —
    # so the completed-map count climbs fast instead of stalling on a giant early.
    def _pbf_size(entry):
        try:
            return os.path.getsize(entry[2])   # entry = (rid, info, pbf, name, sig)
        except OSError:
            return 0
    todo.sort(key=_pbf_size)

    # Run the POI top-ups (in-process, sequential — each is just a PBF POI scan on
    # an existing db, no base/parking rework). After each, RE-RECORD the region so
    # its new size/sha/version and the refreshed manifest are written — otherwise
    # the .db grows but index.json / .build_state.json never change.
    if poi_topup and not getattr(a, "skip_pois", False):
        poi_topup.sort(key=lambda e: (os.path.getsize(e[2])
                                      if os.path.exists(e[2]) else 0))
        print(f"\nAdding POIs to {len(poi_topup)} up-to-date region(s):", flush=True)
        for idx_tu, (rid, info, pbf, name) in enumerate(poi_topup, 1):
            print(f"\n  [{idx_tu}/{len(poi_topup)}] POI scan: {name} ({rid})",
                  flush=True)
            db_path = f"{rid}.db"
            # live single-line progress, repainted in place as the PBF is scanned.
            # _t0 is bound as a default arg so each region's timer is independent
            # (a plain closure would share the loop's last _t0).
            _t0 = time.time()

            def _poi_progress(phase, line="", _t0=_t0):
                el = int(time.time() - _t0)
                msg = (line or phase).strip()
                sys.stdout.write(f"\r      {el:4d}s  {msg[:90]:<90s}")
                sys.stdout.flush()

            try:
                _run_pois(pbf, db_path, name, _poi_progress, _poi_progress)
                sys.stdout.write("\n")   # finish the in-place line
            except Exception as e:
                sys.stdout.write("\n")
                print(f"  POI top-up FAILED for {rid}: {e}", flush=True)
                failed.append(rid)
                continue
            # Re-verify + re-hash and re-record so state + index.json reflect the
            # now-larger db (and bump its version so the app re-downloads it).
            ok, findings = verify_db(db_path)
            res = {
                "findings": findings, "sha": sha256_of(db_path),
                "size": os.path.getsize(db_path), "data_date": pbf_date(pbf),
                "bbox": db_meta(db_path).get("bbox", ""),
            }
            sig = src_signature(pbf)
            n_listed = _record_result(d, a.base_url, state, rid, name,
                                      info["parent"], sig, res)
            print(f"    recorded: v{state[rid]['version']}, "
                  f"{res['size']/1e6:.1f} MB; index.json now {n_listed} region(s)",
                  flush=True)
            processed.append(rid)

    jobs = max(1, a.jobs)
    if jobs > 1 and len(todo) > 1:
        # ---------------- PARALLEL PATH (live per-region dashboard) ----------
        import concurrent.futures as _cf
        import multiprocessing as _mp
        import queue as _queue
        n = min(jobs, len(todo))
        print(f"\nProcessing {len(todo)} region(s) with {n} parallel worker(s).",
              flush=True)
        print("  (Ctrl-C once to stop cleanly: in-progress regions are discarded, "
              "finished ones are saved; just re-run to resume.)\n", flush=True)
        meta = {rid: (info, name, sig) for (rid, info, pbf, name, sig) in todo}
        interrupted = False
        # Shared queue: workers push live (rid, kind, phase, line, elapsed); the
        # parent drains it to paint a per-region dashboard.
        mgr = _mp.Manager()
        q = mgr.Queue()
        dash = _Dashboard(len(todo), n,
                          use_live=(not a.no_dashboard) and _dashboard_supported())
        ex = _cf.ProcessPoolExecutor(max_workers=n,
                                     initializer=_worker_ignore_sigint)
        try:
            futs = {}
            for (rid, info, pbf, name, sig) in todo:
                fut = ex.submit(_worker_build_region,
                                (d, rid, name, pbf, a.skip_base, q))
                futs[fut] = rid
            pending = set(futs.keys())
            while pending:
                # 1) drain all queued progress messages (non-blocking)
                drained = False
                try:
                    while True:
                        rid, kind, phase, line, elapsed = q.get_nowait()
                        dash.update(rid, kind, phase, line, elapsed)
                        drained = True
                except _queue.Empty:
                    pass
                # 2) reap any finished futures (short timeout so we keep the
                #    dashboard ticking even when nothing is finishing)
                done, pending = _cf.wait(pending, timeout=0.4,
                                         return_when=_cf.FIRST_COMPLETED)
                for fut in done:
                    res = fut.result()
                    rid = res["rid"]
                    info, name, sig = meta[rid]
                    if res["ok"]:
                        if rid not in state:
                            added_ids.append(rid)
                        _record_result(d, a.base_url, state, rid, name,
                                       info["parent"], sig, res)
                        processed.append(rid)
                        dash.finish(rid, res, ok=True)
                    else:
                        failed.append(rid)
                        dash.finish(rid, res, ok=False)
                # 3) repaint on a steady cadence
                dash.tick()
        except KeyboardInterrupt:
            interrupted = True
            dash.stop()
            print("\n\nInterrupted — stopping. Regions already finished are saved "
                  "in index.json; in-progress regions were discarded (no partial "
                  ".db left behind). Re-run the same command to resume.", flush=True)
            ex.shutdown(wait=False, cancel_futures=True)
        else:
            ex.shutdown(wait=True)
            dash.stop()
        try:
            mgr.shutdown()
        except Exception:
            pass
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

    # Explain the logs/ folder count. Log files accumulate across runs (each run
    # only rewrites logs for the regions IT processed), so logs/ can legitimately
    # hold more files than this run processed — the extras are from earlier runs.
    # Point this out so the count isn't mistaken for a mismatch.
    logdir = os.path.join(d, "logs")
    if os.path.isdir(logdir):
        nlogs = len([f for f in os.listdir(logdir) if f.endswith(".log")])
        nproc = len(processed) + len(failed)
        if nlogs != nproc:
            print(f"note: logs/ holds {nlogs} .log file(s) but this run processed "
                  f"{nproc} region(s). Log files are kept across runs (one per "
                  f"region, overwritten only when that region is reprocessed), so "
                  f"the extra {nlogs - nproc} are from earlier runs — harmless. "
                  f"Delete logs/ anytime to reset.")

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

    # With --compress, guarantee EVERY deployed map is gzipped — including regions
    # that were skipped as up-to-date this run (they weren't rebuilt, so their
    # .gz might be missing or stale). This makes "build_europe --compress" a
    # one-command produce-compressed-maps step (no separate pack step needed).
    if os.environ.get("DBM_COMPRESS") == "1":
        import gzip as _gzip, shutil as _shutil
        swept = 0
        for rid in list(state.keys()):
            db_path = os.path.join(d, f"{rid}.db")
            if not os.path.exists(db_path):
                continue
            gz_path = db_path + ".gz"
            fresh = (os.path.exists(gz_path) and
                     os.path.getmtime(gz_path) >= os.path.getmtime(db_path))
            if fresh:
                # make sure state/manifest still reflect it
                if not state[rid].get("compressed"):
                    state[rid]["compressed"] = True
                    state[rid]["compressed_size"] = os.path.getsize(gz_path)
                    swept += 1
                continue
            try:
                tmp = gz_path + ".part"
                with open(db_path, "rb") as fin, \
                     _gzip.open(tmp, "wb", compresslevel=9) as fout:
                    _shutil.copyfileobj(fin, fout, length=1 << 20)
                if os.path.exists(gz_path):
                    os.remove(gz_path)
                os.replace(tmp, gz_path)
                state[rid]["compressed"] = True
                state[rid]["compressed_size"] = os.path.getsize(gz_path)
                swept += 1
            except OSError as e:
                print(f"  warn: compress-sweep failed for {rid}: {e}", flush=True)
        if swept:
            save_state(d, state)
            write_manifest(d, a.base_url, state)
            print(f"\ncompress-sweep: ensured .db.gz for {swept} region(s) "
                  f"(incl. up-to-date ones); index.json refreshed.")

    print(f"\nRun outcome:")
    print(f"  processed now   : {len(processed)}")
    print(f"  up-to-date skip : {len(skipped_uptodate)}")
    print(f"  failed          : {len(failed)}  {failed if failed else ''}")

    # Full per-region table + feature totals across everything in the manifest.
    print(f"\nAll regions in index.json ({regions_listed}):")
    # Full per-region breakdown: roads/parking + every POI type, one aligned row
    # per map, so the completion report shows exactly what was extracted for each.
    # Each column has its own width (roads/segments can reach millions) and a
    # leading space, so values never run together.
    cols = [("roads", "segments", 10), ("w/lim", "with_maxspeed", 10),
            ("park", "parking_lot", 8), ("curb", "parking_curb", 7),
            ("cams", "speed_camera", 6), ("fuel", "fuel", 7),
            ("chrg", "charging", 7), ("hosp", "hospital", 6),
            ("rest", "rest_area", 6), ("xing", "level_crossing", 7),
            ("bump", "speed_bump", 8), ("toll", "toll_booth", 6),
            ("bord", "border_control", 6)]
    hdr = f"  {'region':24s}" + "".join(f" {h:>{w}s}" for h, _, w in cols) + \
          f" {'MB':>8s}"
    print(hdr)
    print("  " + "-" * (len(hdr) - 2))
    tot = {key: 0 for _, key, _ in cols}
    tot["bytes"] = 0

    def _i(v):
        try: return int(v)
        except (TypeError, ValueError): return 0

    for rid in sorted(state.keys()):
        if not os.path.exists(os.path.join(d, f"{rid}.db")):
            continue
        info = state[rid]
        c = info.get("counts", {})
        vals = [(key, w, _i(c.get(key))) for _, key, w in cols]
        for key, _w, v in vals:
            tot[key] += v
        tot["bytes"] += info.get("db_size", 0)
        mb = info.get("db_size", 0) / 1e6
        row = f"  {info.get('name', rid)[:24]:24s}" + \
              "".join(f" {v:>{w},}" for _key, w, v in vals) + f" {mb:>8.1f}"
        print(row)

    print("  " + "-" * (len(hdr) - 2))
    trow = f"  {'TOTAL':24s}" + \
           "".join(f" {tot[key]:>{w},}" for _, key, w in cols) + \
           f" {tot['bytes']/1e6:>8.1f}"
    print(trow)

    print(f"\nUpload every changed .db AND index.json to {a.base_url}")
    print("(data files FIRST, index.json LAST — see SERVER-DEPLOYMENT.md)")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        # Gentle stop on Ctrl+C — no traceback. Regions already finished are
        # recorded in index.json/.build_state.json; any in-progress .db.part is
        # cleaned on the next run. Just re-run the same command to continue.
        print("\nInterrupted by user (Ctrl+C) — stopped. Finished regions are "
              "saved; re-run the same command to continue.", file=sys.stderr)
        sys.exit(130)
