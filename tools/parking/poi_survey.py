#!/usr/bin/env python3
r"""
poi_survey.py — READ-ONLY analysis of how many of each candidate POI/safety
feature exist in your GeoFabrik .pbf maps, so you can decide which are worth
adding to the .db (and which would bloat it) BEFORE changing the pipeline.

It writes NOTHING — no DB, no files. It just scans and prints counts.

Run it in the map folder (the one with index.json + the .pbf files, sub-regions
in their parent sub-folders), exactly like build_europe/update_maps:

    python poi_survey.py                 # survey every region in index.json
    python poi_survey.py --only greece,germany
    python poi_survey.py --top 20        # only the N largest regions (quicker)

For each region it prints a row of counts; at the end, grand totals + a
recommendation hint (which types are sparse enough to draw as map dots vs. which
are high-volume and better used as approaching-warnings only).

Requires: pyosmium (pip install osmium).

The candidate feature set (all standard OSM tags present in GeoFabrik extracts):

  SAFETY / WARN-WHEN-APPROACHING (usually better as alerts than map clutter)
    level_crossing   railway=level_crossing        railway crossings
    traffic_calming  traffic_calming=*             speed bumps/humps/tables
    stop             highway=stop                  stop signs
    give_way         highway=give_way              give-way / yield
    crossing         highway=crossing              pedestrian crossings
    traffic_signals  highway=traffic_signals       traffic lights
    hazard           hazard=*                      mapped hazards

  DRAW-ON-MAP CANDIDATES (usually sparse enough to plot)
    toll_booth       barrier=toll_booth
    border_control   barrier=border_control
    toilets          amenity=toilets
    viewpoint        tourism=viewpoint
    picnic_site      tourism=picnic_site
    school           amenity=school                (for school-zone logic)
    weighbridge      amenity=weighbridge           (truck-relevant)

  ALREADY EXTRACTED (shown for scale comparison)
    fuel, charging, hospital, rest_area
"""
import argparse
import json
import os
import sys

# node-level counters keyed by a short label; each is a predicate over tags
def _classifiers():
    return {
        # safety / warn
        "level_crossing": lambda t: t.get("railway") == "level_crossing",
        "traffic_calming": lambda t: "traffic_calming" in t,
        "stop":            lambda t: t.get("highway") == "stop",
        "give_way":        lambda t: t.get("highway") == "give_way",
        "crossing":        lambda t: t.get("highway") == "crossing",
        "traffic_signals": lambda t: t.get("highway") == "traffic_signals",
        "hazard":          lambda t: "hazard" in t,
        # draw-on-map candidates
        "toll_booth":      lambda t: t.get("barrier") == "toll_booth",
        "border_control":  lambda t: t.get("barrier") == "border_control",
        "toilets":         lambda t: t.get("amenity") == "toilets",
        "viewpoint":       lambda t: t.get("tourism") == "viewpoint",
        "picnic_site":     lambda t: t.get("tourism") == "picnic_site",
        "school":          lambda t: t.get("amenity") == "school",
        "weighbridge":     lambda t: t.get("amenity") == "weighbridge",
        # already extracted (scale reference)
        "fuel":            lambda t: t.get("amenity") == "fuel",
        "charging":        lambda t: t.get("amenity") == "charging_station",
        "hospital":        lambda t: t.get("amenity") == "hospital",
        "rest_area":       lambda t: t.get("highway") in ("rest_area", "services"),
    }

# order for printing
ORDER = ["level_crossing", "traffic_calming", "stop", "give_way", "crossing",
         "traffic_signals", "hazard", "toll_booth", "border_control", "toilets",
         "viewpoint", "picnic_site", "school", "weighbridge",
         "fuel", "charging", "hospital", "rest_area"]

EXCLUDED_IDS = {"alps", "britain-and-ireland", "dach", "great-britain", "europe"}


def pbf_path_for(rid, base_dir):
    """Mirror the build/update layout: full country -> <rid>-latest.osm.pbf in the
    main folder; sub-region parent__sub -> <parent>/<sub>-latest.osm.pbf. Also
    accepts date-stamped names as a fallback."""
    def _find(folder, stem):
        # prefer -latest, else any <stem>-*.osm.pbf, else <stem>.osm.pbf
        cand = os.path.join(folder, f"{stem}-latest.osm.pbf")
        if os.path.exists(cand):
            return cand
        if os.path.isdir(folder):
            for f in sorted(os.listdir(folder)):
                if f.endswith(".osm.pbf") and (f == f"{stem}.osm.pbf"
                                               or f.startswith(f"{stem}-")):
                    return os.path.join(folder, f)
        return None
    if "__" in rid:
        parent, sub = rid.split("__", 1)
        if parent in EXCLUDED_IDS or sub in EXCLUDED_IDS:
            return None
        return _find(os.path.join(base_dir, parent), sub)
    if rid in EXCLUDED_IDS:
        return None
    return _find(base_dir, rid)


def _survey_worker(args):
    """Worker: scan ONE region's .pbf and return (rid, counts, err). Module-level
    and picklable so it runs under ProcessPoolExecutor on Windows (spawn). If a
    progress queue is supplied, it reports ("start", rid, 0), periodic
    ("progress", rid, pct) during the scan, and ("done", rid, 100) at the end, so
    the parent can show a live per-map percentage. The % is estimated from the
    node count against the file size (~7.5 bytes/node) — approximate but a good
    'how far along' signal; nodes come first in a PBF and dominate the runtime."""
    if len(args) == 3:
        rid, pbf, q = args
    else:
        rid, pbf = args; q = None

    def _post(kind, pct):
        if q is not None:
            try:
                q.put((kind, rid, pct))
            except Exception:
                pass

    try:
        est_nodes = max(1, int(os.path.getsize(pbf) / 7.5))
    except OSError:
        est_nodes = 1
    _post("start", 0)

    import osmium
    counts = {k: 0 for k in ORDER}
    preds = _classifiers()
    seen = {"n": 0}

    class H(osmium.SimpleHandler):
        def _tally(self, tags):
            for k, pred in preds.items():
                try:
                    if pred(tags):
                        counts[k] += 1
                except Exception:
                    pass
        def node(self, n):
            seen["n"] += 1
            if seen["n"] % 100_000 == 0:
                _post("progress", min(99, int(seen["n"] * 100 / est_nodes)))
            self._tally(dict(n.tags))
        def way(self, w):
            self._tally(dict(w.tags))

    err = None
    try:
        H().apply_file(pbf)
    except Exception as e:
        err = str(e)
    _post("done", 100)
    return rid, counts, err


def main():
    ap = argparse.ArgumentParser(description="Count candidate POIs per region (read-only)")
    ap.add_argument("--dir", default=".", help="folder with index.json + .pbf files")
    ap.add_argument("--only", default="", help="comma-separated region ids")
    ap.add_argument("--top", type=int, default=0,
                    help="survey only the N largest .pbf files (quicker sample)")
    ap.add_argument("--jobs", "-j", type=int,
                    default=max(1, (os.cpu_count() or 2) - 1),
                    help="parallel workers (default: CPU cores - 1)")
    a = ap.parse_args()

    try:
        import osmium  # noqa: F401  (checked here; imported in each worker too)
    except ImportError:
        sys.exit("pyosmium required: pip install osmium")

    base_dir = os.path.abspath(a.dir)
    idx = os.path.join(base_dir, "index.json")
    if not os.path.exists(idx):
        sys.exit(f"index.json not found in {base_dir}")
    cat = json.load(open(idx, encoding="utf-8"))
    ids = [r["id"] for r in cat.get("regions", [])]
    only = {x.strip() for x in a.only.split(",") if x.strip()}
    if only:
        ids = [r for r in ids if r in only or r.split("__", 1)[0] in only]

    work = []
    for rid in ids:
        p = pbf_path_for(rid, base_dir)
        if p:
            work.append((rid, p, os.path.getsize(p)))
    if a.top > 0:
        work = sorted(work, key=lambda x: -x[2])[:a.top]
    work.sort(key=lambda x: x[2])   # smallest first
    if not work:
        sys.exit("no .pbf files found for the regions in index.json")

    jobs = max(1, a.jobs)

    # Two SEPARATE output areas:
    #   1) COMPLETED block — one clean row per finished region, printed once and
    #      never interleaved with live status, so it copy-pastes as a report.
    #   2) LIVE STATUS area — a few lines at the bottom, repainted in place,
    #      showing which maps are scanning now and each one's %.
    NW = 24
    CW = 8
    total = len(work)
    header = "region".ljust(NW) + "".join(k[:CW-1].rjust(CW) for k in ORDER)

    grand = {k: 0 for k in ORDER}
    tasks = {(rid): (rid, pbf) for rid, pbf, _ in work}
    size_of = {rid: sz for rid, pbf, sz in work}

    class _Status:
        """Bottom repainting status area: clears its own lines before a completed
        row is printed (keeping completed rows contiguous), then redraws."""
        def __init__(self):
            self.lines = 0
            self.live = sys.stdout.isatty()
            if self.live and os.name == "nt":
                try:
                    import ctypes
                    k = ctypes.windll.kernel32
                    k.SetConsoleMode(k.GetStdHandle(-11), 7)  # enable VT / ANSI
                except Exception:
                    pass
        def clear(self):
            if self.live and self.lines:
                sys.stdout.write(f"\x1b[{self.lines}A\x1b[0J")
                self.lines = 0
                sys.stdout.flush()
        def draw(self, rows):
            if not self.live:
                return
            self.clear()
            for ln in rows:
                sys.stdout.write(ln[:160] + "\x1b[K\n")
            self.lines = len(rows)
            sys.stdout.flush()

    status = _Status()

    def _emit_row(rid, counts, err, done):
        status.clear()                      # lift the live area
        row = (rid.ljust(NW)[:NW]
               + "".join(str(counts[k]).rjust(CW) for k in ORDER))
        if err:
            row += "  ERR"
        print(row, flush=True)              # contiguous completed row
        for k in ORDER:
            grand[k] += counts[k]

    print(f"Surveying {total} region(s) on {jobs} core(s) — read-only.")
    print("\nCOMPLETED (copy-paste ready):")
    print(header)
    print("-" * len(header))

    import concurrent.futures as cf
    import time as _time
    import multiprocessing as _mp
    t0 = _time.time()
    done = 0
    if jobs > 1 and total > 1:
        remaining = dict(tasks)
        mgr = _mp.Manager()
        q = mgr.Queue()
        running = {}     # rid -> {"start": t, "pct": p}
        try:
            with cf.ProcessPoolExecutor(max_workers=jobs) as ex:
                fut = {ex.submit(_survey_worker, (rid, pbf, q)): rid
                       for rid, (rid_, pbf) in tasks.items()}
                pending = set(fut.keys())

                def _drain():
                    while True:
                        try:
                            kind, rid, pct = q.get_nowait()
                        except Exception:
                            break
                        if kind == "start":
                            running[rid] = {"start": _time.time(), "pct": 0}
                        elif kind == "progress" and rid in running:
                            running[rid]["pct"] = pct
                        elif kind == "done":
                            running.pop(rid, None)

                def _status_lines(now):
                    el = int(now - t0)
                    rows = [f"  ── processing: {len(running)} active · "
                            f"{done}/{total} done · {el}s ──"]
                    for rid, st in sorted(running.items(),
                                          key=lambda kv: kv[1]["start"])[:jobs]:
                        pct = st["pct"]; secs = int(now - st["start"])
                        bar = "#" * (pct // 5) + "." * (20 - pct // 5)
                        rows.append(f"    {rid[:28]:28s} [{bar}] {pct:3d}%  {secs:4d}s")
                    return rows

                while pending:
                    finished, pending = cf.wait(
                        pending, timeout=1, return_when=cf.FIRST_COMPLETED)
                    now = _time.time()
                    _drain()
                    for f in finished:
                        rid = fut[f]
                        try:
                            r_rid, counts, err = f.result()
                        except Exception as e:
                            r_rid, counts, err = rid, {k: 0 for k in ORDER}, str(e)
                        remaining.pop(r_rid, None)
                        running.pop(r_rid, None)
                        done += 1
                        _emit_row(r_rid, counts, err, done)
                    status.draw(_status_lines(now))
                status.clear()
        except cf.process.BrokenProcessPool:
            status.clear()
            print(f"\n  a worker process died — finishing the remaining "
                  f"{len(remaining)} region(s) serially…", flush=True)
            for rid, args in remaining.items():
                r_rid, counts, err = _survey_worker(args)
                done += 1
                _emit_row(r_rid, counts, err, done)
    else:
        for rid, args in tasks.items():
            sz = size_of.get(rid, 0)
            status.draw([f"  ── processing: {rid} ({sz/1e6:,.0f} MB) ──"])
            rid2, counts, err = _survey_worker(args)
            done += 1
            _emit_row(rid2, counts, err, done)
        status.clear()

    print("-" * len(header))
    print("TOTAL".ljust(NW) + "".join(str(grand[k]).rjust(CW) for k in ORDER))

    print("\nColumns:", ", ".join(ORDER))
    print("\nVolume hint (across surveyed regions):")
    for k in ORDER:
        v = grand[k]
        if k in ("fuel", "charging", "hospital", "rest_area"):
            tag = "already extracted"
        elif v == 0:
            tag = "absent here"
        elif v < 5_000:
            tag = "sparse - fine to DRAW on map"
        elif v < 50_000:
            tag = "moderate - draw, or warn-on-approach"
        else:
            tag = "HIGH volume - better as WARN-on-approach"
        print(f"  {k:16s} {v:>10,}   {tag}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nInterrupted (Ctrl+C) — stopped.", file=sys.stderr)
        sys.exit(130)
