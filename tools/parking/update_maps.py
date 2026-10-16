#!/usr/bin/env python3
r"""
update_maps.py — download the latest Geofabrik source .osm.pbf for every map in
index.json (full countries + sub-regions), into the same folder layout the build
pipeline expects, using curl. After a map updates, the previous .osm.pbf for that
same region is deleted.

Layout (unchanged from how build_europe.py reads it):
  full country   greece          -> ./greece-latest.osm.pbf
  sub-region     germany__bayern -> ./germany/bayern-latest.osm.pbf   (sub-folder
                                     named after the parent region)

Geofabrik URLs (https://download.geofabrik.de/europe.html):
  full country   europe/<id>-latest.osm.pbf
  sub-region     europe/<parent>/<sub>-latest.osm.pbf

Geofabrik URLs are resolved from GeoFabrik's own machine-readable catalogue
(index-v1-nogeom.json), so the script adapts automatically to whatever index.json
lists — now or in future — including any region whose GeoFabrik path differs from
its catalogue id. Regions not found in that catalogue fall back to the
conventional europe/<...> path and are reported as "guessed".

SPECIAL SUB-REGIONS ARE NEVER DOWNLOADED (they duplicate country data):
  alps, britain-and-ireland, dach, great-britain, europe.

Only changed maps are fetched: curl -z uses the local file's timestamp, so an
unchanged region is skipped by the server (304 / not-newer). Each download is
verified against Geofabrik's .md5 before it replaces anything.

Usage:
    python update_maps.py                 # update everything in index.json
    python update_maps.py --only greece,germany
    python update_maps.py --dir "D:\...\Maps"   # folder holding index.json
    python update_maps.py --dry-run       # show what would be downloaded

Requires: curl on PATH (curl.exe ships with Windows 10+).

Interrupting: press Ctrl+C to STOP the file currently downloading and skip to the
next map (its partial is kept and resumes on a later run). Press Ctrl+C again to
abort the whole run. Re-running always resumes any partial .part downloads and
skips maps that are already up to date.
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys

GEOFABRIK = "https://download.geofabrik.de/europe"

# GeoFabrik's machine-readable catalogue of every region and its exact download
# URL. Resolving against this (rather than guessing id->path) is what lets this
# script adapt to whatever index.json contains now or in future — including
# regions whose GeoFabrik path doesn't match their catalogue id.
GEOFABRIK_INDEX = "https://download.geofabrik.de/index-v1-nogeom.json"

# Special sub-regions that must NOT be downloaded (they overlap real countries).
EXCLUDED_IDS = {"alps", "britain-and-ireland", "dach", "great-britain", "europe"}


def load_geofabrik_index():
    """Fetch GeoFabrik's region catalogue -> {leaf_id: {parent, pbf_url, name}}.
    Returns {} if unreachable (the caller then falls back to guessed URLs)."""
    try:
        out = subprocess.run([curl_exe(), "-sfL", GEOFABRIK_INDEX],
                             capture_output=True, text=True,
                             encoding="utf-8", errors="replace", timeout=90)
        if out.returncode != 0 or not (out.stdout or "").strip():
            return {}
        data = json.loads(out.stdout)
    except (subprocess.SubprocessError, OSError, ValueError):
        return {}
    idx = {}
    for feat in data.get("features", []):
        p = feat.get("properties", {})
        rid = p.get("id")
        pbf = (p.get("urls") or {}).get("pbf")
        if rid and pbf:
            idx[rid] = {"parent": p.get("parent"), "pbf": pbf,
                        "name": p.get("name")}
    return idx


def curl_exe():
    # On Windows, prefer curl.exe explicitly (plain 'curl' may alias to
    # Invoke-WebRequest in some shells; from a .py subprocess it's the real curl,
    # but being explicit is harmless and robust).
    return "curl.exe" if os.name == "nt" and shutil.which("curl.exe") else "curl"


def parse_region(rid, gf_index=None):
    """(url, local_path, is_sub, resolved_by) for a catalog region id, or None if
    excluded. The GeoFabrik URL is taken from GeoFabrik's own catalogue when
    available (authoritative — adapts to any path quirk); otherwise it falls back
    to the conventional europe/<...> guess.

       greece          -> ./greece-latest.osm.pbf
       germany__bayern -> ./germany/bayern-latest.osm.pbf
    """
    gf_index = gf_index or {}
    if "__" in rid:
        parent, sub = rid.split("__", 1)
        if parent in EXCLUDED_IDS or sub in EXCLUDED_IDS:
            return None
        local = os.path.join(parent, f"{sub}-latest.osm.pbf")
        # authoritative: GeoFabrik leaf id == sub, and its parent must match
        feat = gf_index.get(sub)
        if feat and feat.get("parent") == parent and feat.get("pbf"):
            return feat["pbf"], local, True, "geofabrik-index"
        # fallback: conventional path
        return f"{GEOFABRIK}/{parent}/{sub}-latest.osm.pbf", local, True, "guessed"
    else:
        if rid in EXCLUDED_IDS:
            return None
        local = f"{rid}-latest.osm.pbf"
        feat = gf_index.get(rid)
        if feat and feat.get("pbf"):
            return feat["pbf"], local, False, "geofabrik-index"
        return f"{GEOFABRIK}/{rid}-latest.osm.pbf", local, False, "guessed"


def remote_size(url):
    """Content-Length of the pbf in bytes (via a HEAD request), or None."""
    try:
        head = subprocess.run([curl_exe(), "-sIL", url],
                              capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=60)
        for line in (head.stdout or "").splitlines():
            if line.lower().startswith("content-length:"):
                return int(line.split(":", 1)[1].strip())
    except (subprocess.SubprocessError, OSError, ValueError):
        pass
    return None


def remote_md5(url):
    """Fetch Geofabrik's .md5 for a pbf url; return the hex digest or None."""
    try:
        out = subprocess.run(
            [curl_exe(), "-sfL", url + ".md5"],
            capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=60)
        if out.returncode == 0 and (out.stdout or "").strip():
            return out.stdout.split()[0].strip().lower()
    except (subprocess.SubprocessError, OSError):
        pass
    return None


def file_md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest().lower()


def download_one(rid, base_dir, dry_run, prefix="", gf_index=None):
    parsed = parse_region(rid, gf_index)
    if parsed is None:
        return ("excluded", rid, "special sub-region — not downloaded")
    url, rel, is_sub, how = parsed
    dest = os.path.join(base_dir, rel)
    os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)

    # Skip the fetch entirely if we already have this exact file (md5 match).
    md5 = remote_md5(url)
    if md5 and os.path.exists(dest) and file_md5(dest) == md5:
        return ("up-to-date", rid, os.path.relpath(dest, base_dir))

    if dry_run:
        return ("would-download", rid, f"[{how}] {url}  ->  {rel}")

    # Show what's about to be fetched (with its size) so large maps are obvious,
    # then let curl draw a live progress bar for THIS file. Overall position
    # (region N of M, running MB total) is printed by the caller.
    sz = remote_size(url)
    sz_txt = f"{sz/1e6:,.1f} MB" if sz else "size unknown"
    print(f"    {prefix}downloading {rid}  ({sz_txt})", flush=True)

    # Download to a .part next to the target. If a .part from a previous
    # interrupted run exists, resume it with "-C -" (continue where it stopped)
    # rather than restarting from zero — important for the large maps. "-#" is
    # curl's compact live progress bar.
    part = dest + ".part"
    resuming = os.path.exists(part) and os.path.getsize(part) > 0
    cmd = [curl_exe(), "-fL", "-#", "--retry", "3", "--retry-delay", "3",
           "-o", part, url]
    if resuming:
        cmd += ["-C", "-"]           # resume the partial file
        print(f"      resuming from {os.path.getsize(part)/1e6:,.1f} MB", flush=True)
    elif os.path.exists(dest):
        cmd += ["-z", dest]          # else only fetch if remote is newer
    try:
        r = subprocess.run(cmd, timeout=60 * 60)
    except KeyboardInterrupt:
        # Ctrl+C: keep the .part so this file can resume next run, and signal the
        # caller to move on to the next map (or abort on a second Ctrl+C).
        print(f"\n      interrupted — kept partial for resume", flush=True)
        return ("interrupted", rid, os.path.relpath(dest, base_dir))
    except (subprocess.SubprocessError, OSError) as e:
        _rm(part)
        return ("failed", rid, f"curl error: {e}")
    if r.returncode != 0:
        # Non-zero: keep the .part (curl may have written some) so a re-run
        # resumes rather than restarts; report it as failed for this pass.
        return ("failed", rid, f"curl exit {r.returncode}")

    # -z with no newer data leaves no .part (or an empty one): treat as up-to-date.
    if not os.path.exists(part) or os.path.getsize(part) == 0:
        _rm(part)
        return ("up-to-date", rid, os.path.relpath(dest, base_dir))

    # verify md5 before committing
    if md5 and file_md5(part) != md5:
        _rm(part)
        return ("failed", rid, "md5 mismatch — download discarded")

    # Commit: replace the target with the freshly downloaded file, then delete any
    # OLDER .pbf variants for this same region (dated names, previous latest).
    _promote_and_clean(part, dest)
    size_mb = os.path.getsize(dest) / 1e6
    return ("updated", rid, f"{os.path.relpath(dest, base_dir)}  ({size_mb:.1f} MB)")


def _rm(path):
    try:
        if os.path.exists(path):
            os.remove(path)
    except OSError:
        pass


def _promote_and_clean(part, dest):
    """Move the .part over dest, then delete older .osm.pbf files for the same
    region in the same folder (e.g. greece-260801.osm.pbf), keeping only the new
    -latest file."""
    folder = os.path.dirname(dest) or "."
    base = os.path.basename(dest)                      # e.g. greece-latest.osm.pbf
    stem = base[:-len("-latest.osm.pbf")]              # e.g. greece
    # atomic-ish replace
    if os.path.exists(dest):
        _rm(dest)
    os.replace(part, dest)
    # delete other variants: <stem>-<anything>.osm.pbf (not the -latest we kept)
    for f in os.listdir(folder):
        if not f.endswith(".osm.pbf"):
            continue
        if f == base:
            continue
        # same region stem, e.g. greece-260801.osm.pbf or greece.osm.pbf
        if f == f"{stem}.osm.pbf" or f.startswith(f"{stem}-"):
            _rm(os.path.join(folder, f))
            print(f"      removed old: {f}")


def main():
    ap = argparse.ArgumentParser(
        description="Download Geofabrik updates for all maps in index.json")
    ap.add_argument("--dir", default=".",
                    help="folder containing index.json (default: current)")
    ap.add_argument("--only", default="",
                    help="comma-separated region ids (or parent ids) to limit to")
    ap.add_argument("--dry-run", action="store_true",
                    help="list what would be downloaded, fetch nothing")
    a = ap.parse_args()

    base_dir = os.path.abspath(a.dir)
    idx_path = os.path.join(base_dir, "index.json")
    if not os.path.exists(idx_path):
        sys.exit(f"index.json not found in {base_dir}")
    catalog = json.load(open(idx_path, encoding="utf-8"))
    ids = [r["id"] for r in catalog.get("regions", [])]
    if not ids:
        sys.exit("index.json has no regions")

    only = {x.strip() for x in a.only.split(",") if x.strip()}
    if only:
        ids = [rid for rid in ids
               if rid in only or rid.split("__", 1)[0] in only]

    print(f"{len(ids)} region(s) from index.json"
          + (f" (filtered to {sorted(only)})" if only else "")
          + (f"  [DRY RUN]" if a.dry_run else ""))

    # Load GeoFabrik's authoritative catalogue once, so URLs come from GeoFabrik
    # itself rather than a guessed path. This is what makes the script adapt to
    # whatever index.json lists now or later. If it's unreachable we fall back to
    # guessed paths and say so.
    gf_index = load_geofabrik_index()
    if gf_index:
        print(f"resolved against GeoFabrik catalogue ({len(gf_index)} regions)")
    else:
        print("note: GeoFabrik catalogue unavailable — using guessed URLs")

    # Pre-flight: report any region whose GeoFabrik URL had to be GUESSED (not
    # found in the catalogue) so a future/renamed region that can't be resolved is
    # visible before the run, not a surprise 404 mid-download.
    if gf_index:
        guessed = []
        for rid in ids:
            p = parse_region(rid, gf_index)
            if p is not None and p[3] == "guessed":
                guessed.append(rid)
        if guessed:
            print(f"warning: {len(guessed)} region(s) not in the GeoFabrik "
                  f"catalogue — will try a guessed URL (may 404): "
                  f"{', '.join(guessed[:12])}"
                  + (" …" if len(guessed) > 12 else ""))

    tally = {"updated": [], "up-to-date": [], "excluded": [], "failed": [],
             "would-download": [], "interrupted": []}
    total = len(ids)
    total_mb = 0.0
    for i, rid in enumerate(ids, 1):
        prefix = f"[{i}/{total}] "
        try:
            status, _, detail = download_one(rid, base_dir, a.dry_run, prefix,
                                             gf_index)
        except KeyboardInterrupt:
            # A second Ctrl+C (between files, or during setup) aborts the run.
            print("\n\nAborted by user (Ctrl+C). Partial downloads were kept and "
                  "will resume on the next run.")
            break
        tally[status].append(rid)
        if status == "updated":
            # detail ends with "(NN.N MB)" — pull the number for a running total
            import re as _re
            m = _re.search(r"\(([\d.]+) MB\)", detail)
            if m:
                total_mb += float(m.group(1))
        tag = {"updated": "UPDATED", "up-to-date": "current",
               "excluded": "skipped", "failed": "FAILED",
               "would-download": "would get", "interrupted": "SKIPPED"}[status]
        done_txt = f"  ·  {total_mb:,.0f} MB downloaded so far" \
            if (status == "updated" and total_mb) else ""
        print(f"  {prefix}[{tag:9s}] {rid:30s} {detail}{done_txt}", flush=True)

    print("\nsummary:")
    for k in ("updated", "up-to-date", "excluded", "failed", "would-download",
              "interrupted"):
        if tally[k]:
            print(f"  {k:15s}: {len(tally[k])}")
    if total_mb:
        print(f"  {'downloaded':15s}: {total_mb:,.0f} MB total")
    if tally["interrupted"]:
        print("  (interrupted maps kept their partial .part — re-run to resume "
              "and finish the rest)")
    if tally["failed"]:
        print("  failed ids:", ", ".join(tally["failed"]))
        sys.exit(1)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        # Gentle stop on Ctrl+C — no traceback. Completed maps are kept; any
        # partial .part resumes on the next run.
        print("\nInterrupted by user (Ctrl+C) — stopped. Re-run to resume.",
              file=sys.stderr)
        sys.exit(130)
