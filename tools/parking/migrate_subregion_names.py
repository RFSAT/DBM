#!/usr/bin/env python3
"""
migrate_subregion_names.py — one-shot migration for the sub-region separator
change (single hyphen -> double underscore) so already-processed sub-region
databases DON'T need to be rebuilt.

build_europe.py used to name a sub-region <parent>-<sub> (germany-bayern.db).
It now uses <parent>__<sub> (germany__bayern.db) so the id can't be confused
with a hyphenated full-country name. The .db CONTENT is identical either way —
only the name changed — so this script renames the files and updates the two
JSON manifests in place. No reprocessing.

SAFE and CONSERVATIVE:
  * Migrates ONLY the parent countries you name (from a bare filename,
    "germany-bayern" is genuinely ambiguous — that's why the scheme changed —
    so it never guesses).
  * Full-country files are never touched.
  * Refuses to overwrite an existing new-name file.
  * Dry-run by default; pass --apply to actually rename.

Usage (from the map folder):
    python migrate_subregion_names.py --parents germany,italy
    python migrate_subregion_names.py --parents germany,italy --apply
"""
import argparse
import json
import os
import sys


def load(path):
    return json.load(open(path)) if os.path.exists(path) else None


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir", default=".", help="map folder (default: current)")
    ap.add_argument("--parents", required=True,
                    help="comma-separated parent country ids built as sub-regions, "
                         "e.g. germany,italy,france")
    ap.add_argument("--apply", action="store_true",
                    help="actually rename (default is a dry run)")
    a = ap.parse_args()

    d = os.path.abspath(a.dir)
    parents = [p.strip() for p in a.parents.split(",") if p.strip()]
    if not parents:
        sys.exit("give at least one --parents value")

    state_path = os.path.join(d, ".build_state.json")
    index_path = os.path.join(d, "index.json")
    state = load(state_path) or {}
    index = load(index_path)

    # Build old_id -> (new_id, parent) for every old-scheme sub-region id.
    renames = {}
    for parent in parents:
        prefix = parent + "-"
        for rid in list(state.keys()):
            if rid.startswith(prefix) and "__" not in rid:
                sub = rid[len(prefix):]
                renames[rid] = (f"{parent}__{sub}", parent)

    if not renames:
        print(f"Nothing to migrate: no old-scheme sub-region ids for {parents}.")
        print("(If your processed files are all full countries, you're already "
              "fine — nothing to do.)")
        return

    print(f"{'APPLYING' if a.apply else 'DRY RUN'} — sub-region renames:")
    done = 0
    for old_id, (new_id, parent) in sorted(renames.items()):
        old_db = os.path.join(d, f"{old_id}.db")
        new_db = os.path.join(d, f"{new_id}.db")
        exists = os.path.exists(old_db)
        clash = os.path.exists(new_db)
        status = "OK" if (exists and not clash) else \
                 ("MISSING .db (state only)" if not exists else
                  "TARGET EXISTS — skip")
        print(f"  {old_id}.db  ->  {new_id}.db   [{status}]")
        if not a.apply or clash:
            continue
        if exists:
            os.rename(old_db, new_db)
        # Move the state entry (even if the .db was missing, keep state consistent
        # only when we actually have the file; otherwise skip to avoid lying).
        if exists and old_id in state:
            entry = state.pop(old_id)
            entry["parent"] = parent
            state[new_id] = entry
            done += 1

    if a.apply:
        json.dump(state, open(state_path, "w"), indent=2)
        if index and "regions" in index:
            for r in index["regions"]:
                if r["id"] in renames:
                    new_id, parent = renames[r["id"]]
                    r["id"] = new_id
                    r["file"] = f"{new_id}.db"
                    r["parent"] = parent
            json.dump(index, open(index_path, "w"), indent=2)
        print(f"\nDone: migrated {done} sub-region(s). Updated "
              ".build_state.json"
              + (" + index.json" if index else "")
              + ". No reprocessing needed — re-run build_europe.py normally.")
    else:
        print("\nDry run only. Re-run with --apply to perform the renames.")


if __name__ == "__main__":
    main()
