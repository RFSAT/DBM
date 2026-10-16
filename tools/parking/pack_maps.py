#!/usr/bin/env python3
r"""
pack_maps.py — gzip-compress ALREADY-BUILT .db map files for server deployment,
without rebuilding them. Produces "<region>.db.gz" next to each .db and updates
index.json with the compression fields the app reads (v1.20.107+):

    compressed:      true
    compressedSize:  <bytes of the .db.gz>

sha256 and sizeBytes in index.json stay as the DECOMPRESSED .db values, so the
app still verifies the final .db after it decompresses on download.

Layout is the same as the rest of the pipeline — full country .db in the main
folder, sub-region .db in its parent sub-folder — and the .db paths come from
index.json's "file" field (+ the parent sub-folder for "parent__sub" ids).

    python pack_maps.py                    # pack every region in index.json
    python pack_maps.py --only greece,germany
    python pack_maps.py --level 9          # gzip level (1..9, default 9 = smallest)
    python pack_maps.py --jobs 8           # parallel workers (default: cores-1)
    python pack_maps.py --force            # re-pack even if an up-to-date .gz exists
    python pack_maps.py --clean            # ALSO delete .db.gz for regions no longer
                                           # in index.json (tidy the server tree)

Deploy: upload the resulting <region>.db.gz files AND the updated index.json.
(You can keep or remove the plain .db files on the server — the app fetches .gz
when a region is marked compressed.)

Only .db files are read; nothing about the maps' contents changes. Safe to re-run
(idempotent): a region whose .gz already matches its .db is skipped.
"""
import argparse
import gzip
import hashlib
import json
import os
import shutil
import sys
import time


def db_path_for(rid, file_field, base_dir):
    """Local path of a region's .db. The built .db files are FLAT in the map
    folder and named by index.json's "file" field (e.g. "greece.db",
    "czech-republic__jihocesky.db") — they are NOT in parent sub-folders (only the
    source .pbf files are). So just join the file name to the base dir. Falls back
    to a couple of sensible names if "file" is missing, and — for robustness —
    also checks a <parent>/<leaf>.db sub-folder layout in case a future build uses
    one."""
    fname = file_field or f"{rid}.db"
    flat = os.path.join(base_dir, fname)
    if os.path.exists(flat):
        return flat
    # fallbacks: id-named flat file, then a possible parent/leaf sub-folder layout
    alt = os.path.join(base_dir, f"{rid}.db")
    if os.path.exists(alt):
        return alt
    if "__" in rid:
        parent, sub = rid.split("__", 1)
        sub_layout = os.path.join(base_dir, parent, f"{sub}.db")
        if os.path.exists(sub_layout):
            return sub_layout
    # nothing found — return the primary expected path so the "missing" report
    # names the file the user most likely intended.
    return flat


def _sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _gz_is_current(db, gz):
    """True if <gz> exists and is newer than <db> (so we can skip re-packing).
    A .gz older than its .db means the map was rebuilt since — re-pack it."""
    try:
        return os.path.exists(gz) and os.path.getmtime(gz) >= os.path.getmtime(db)
    except OSError:
        return False


def pack_worker(args):
    """Compress ONE .db -> .db.gz atomically. Module-level & picklable for the
    parallel pool. Returns (rid, status, detail)."""
    rid, db, level, force = args
    if not os.path.exists(db):
        return rid, "missing", os.path.basename(db)
    gz = db + ".gz"
    if not force and _gz_is_current(db, gz):
        return rid, "up-to-date", os.path.basename(gz)
    part = gz + ".part"
    try:
        with open(db, "rb") as fin, \
             gzip.open(part, "wb", compresslevel=level) as fout:
            shutil.copyfileobj(fin, fout, length=1 << 20)
        if os.path.exists(gz):
            os.remove(gz)
        os.replace(part, gz)   # atomic
    except OSError as e:
        try:
            if os.path.exists(part):
                os.remove(part)
        except OSError:
            pass
        return rid, "failed", str(e)
    dsz = os.path.getsize(db)
    csz = os.path.getsize(gz)
    pct = (csz * 100 // dsz) if dsz else 0
    return rid, "packed", (csz, dsz, pct)


def main():
    ap = argparse.ArgumentParser(
        description="Gzip already-built .db maps for server delivery")
    ap.add_argument("--dir", default=".", help="folder with index.json + .db files")
    ap.add_argument("--only", default="", help="comma-separated region ids")
    ap.add_argument("--level", type=int, default=9,
                    help="gzip level 1..9 (default 9 = smallest / your ~32%%)")
    ap.add_argument("--jobs", "-j", type=int,
                    default=max(1, (os.cpu_count() or 2) - 1),
                    help="parallel workers (default: CPU cores - 1)")
    ap.add_argument("--force", action="store_true",
                    help="re-pack even if the .gz is already up to date")
    ap.add_argument("--clean", action="store_true",
                    help="delete .db.gz files for regions no longer in index.json")
    a = ap.parse_args()

    base_dir = os.path.abspath(a.dir)
    idx = os.path.join(base_dir, "index.json")
    if not os.path.exists(idx):
        sys.exit(f"index.json not found in {base_dir}")
    cat = json.load(open(idx, encoding="utf-8"))
    regions = cat.get("regions", [])
    if not regions:
        sys.exit("index.json has no regions")

    only = {x.strip() for x in a.only.split(",") if x.strip()}
    level = max(1, min(9, a.level))

    # build the work list (smallest .db first, so the many small ones fly through)
    work = []
    for r in regions:
        rid = r.get("id")
        if not rid:
            continue
        if only and not (rid in only or rid.split("__", 1)[0] in only):
            continue
        db = db_path_for(rid, r.get("file"), base_dir)
        sz = os.path.getsize(db) if os.path.exists(db) else 0
        work.append((rid, db, sz))
    work.sort(key=lambda x: x[2])

    if not work:
        sys.exit("no matching regions to pack")

    jobs = max(1, a.jobs)
    total = len(work)
    print(f"Packing {total} .db file(s) at gzip level {level} on {jobs} core(s).\n",
          flush=True)

    tasks = {rid: (rid, db, level, a.force) for rid, db, _ in work}
    results = {}         # rid -> compressedSize (for the manifest update)
    tally = {"packed": [], "up-to-date": [], "missing": [], "failed": []}
    packed_bytes = orig_bytes = 0
    done = 0
    t0 = time.time()

    def _handle(rid, status, detail):
        nonlocal packed_bytes, orig_bytes, done
        done += 1
        tally[status].append(rid)
        if status == "packed":
            csz, dsz, pct = detail
            results[rid] = csz
            packed_bytes += csz
            orig_bytes += dsz
            print(f"  [{done:>3}/{total}] packed     {rid:28s} "
                  f"{dsz/1e6:7,.1f} -> {csz/1e6:6,.1f} MB ({pct}%)", flush=True)
        elif status == "up-to-date":
            # still need its size for the manifest
            gz = tasks[rid][1] + ".gz"
            if os.path.exists(gz):
                results[rid] = os.path.getsize(gz)
            print(f"  [{done:>3}/{total}] current    {rid}", flush=True)
        else:
            print(f"  [{done:>3}/{total}] {status.upper():9s} {rid}  {detail}",
                  flush=True)

    import concurrent.futures as cf
    if jobs > 1 and total > 1:
        remaining = dict(tasks)
        try:
            with cf.ProcessPoolExecutor(max_workers=jobs) as ex:
                fut = {ex.submit(pack_worker, args): rid
                       for rid, args in tasks.items()}
                pend = set(fut)
                while pend:
                    fin, pend = cf.wait(pend, timeout=15,
                                        return_when=cf.FIRST_COMPLETED)
                    for f in fin:
                        rid = fut[f]
                        try:
                            r_rid, status, detail = f.result()
                        except Exception as e:
                            r_rid, status, detail = rid, "failed", str(e)
                        remaining.pop(r_rid, None)
                        _handle(r_rid, status, detail)
                    if not fin and pend:
                        el = int(time.time() - t0)
                        still = [fut[f] for f in pend][:3]
                        print(f"      … working: {done}/{total} done, {el}s, "
                              f"{len(pend)} in progress (e.g. {', '.join(still)})",
                              flush=True)
        except cf.process.BrokenProcessPool:
            print(f"\n  a worker died — finishing {len(remaining)} serially…",
                  flush=True)
            for rid, args in remaining.items():
                r_rid, status, detail = pack_worker(args)
                _handle(r_rid, status, detail)
    else:
        for rid, args in tasks.items():
            r_rid, status, detail = pack_worker(args)
            _handle(r_rid, status, detail)

    # --- update index.json with compression fields --------------------------
    changed = 0
    for r in regions:
        rid = r.get("id")
        gz_size = results.get(rid)
        # a region is compressed iff its .gz exists (packed now or already current)
        db = db_path_for(rid, r.get("file"), base_dir)
        if gz_size is None and os.path.exists(db + ".gz"):
            gz_size = os.path.getsize(db + ".gz")
        if gz_size is not None:
            if not r.get("compressed") or r.get("compressedSize") != gz_size:
                changed += 1
            r["compressed"] = True
            r["compressedSize"] = gz_size
        else:
            # no .gz for this region — make sure it's not falsely flagged
            if r.pop("compressed", None) is not None:
                changed += 1
            r.pop("compressedSize", None)

    if changed:
        cat["updated"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        tmp = idx + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(cat, f, ensure_ascii=False, indent=1)
        os.replace(tmp, idx)
        print(f"\nindex.json updated: {changed} region(s) marked compressed; "
              f"updated={cat['updated']}")
    else:
        print("\nindex.json unchanged (all compression fields already current)")

    # --- optional: remove orphan .gz for regions no longer listed -----------
    if a.clean:
        listed = {r.get("id") for r in regions}
        # also accept the exact "file" names as valid (in case an id differs)
        listed_files = {r.get("file") for r in regions if r.get("file")}
        removed = 0
        for root, _dirs, files in os.walk(base_dir):
            for fn in files:
                if fn.endswith(".db.gz"):
                    db_name = fn[:-3]                 # strip ".gz" -> "<x>.db"
                    stem = db_name[:-3]               # strip ".db"  -> "<x>"
                    # the .gz belongs to a listed region if its .db name or its
                    # stem (used as an id) is still in the catalogue.
                    if db_name in listed_files or stem in listed:
                        continue
                    try:
                        os.remove(os.path.join(root, fn)); removed += 1
                        print(f"  cleaned orphan: "
                              f"{os.path.relpath(os.path.join(root, fn), base_dir)}")
                    except OSError:
                        pass
        print(f"clean: removed {removed} orphan .db.gz")

    # --- summary ------------------------------------------------------------
    print("\nsummary:")
    for k in ("packed", "up-to-date", "missing", "failed"):
        if tally[k]:
            print(f"  {k:12s}: {len(tally[k])}")
    if orig_bytes:
        saved = orig_bytes - packed_bytes
        print(f"  packed {orig_bytes/1e6:,.0f} MB of .db -> {packed_bytes/1e6:,.0f} "
              f"MB of .gz  ({packed_bytes*100//orig_bytes}%, saved "
              f"{saved/1e6:,.0f} MB)")
    if tally["missing"]:
        print("  missing .db (build or download them first):",
              ", ".join(tally["missing"][:10]))
    if tally["failed"]:
        sys.exit(1)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nInterrupted (Ctrl+C) — stopped. Re-run to finish (already-packed "
              "files are skipped).", file=sys.stderr)
        sys.exit(130)
