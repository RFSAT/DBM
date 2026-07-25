# Deploying parking data to the RFSAT server — step by step

The DBM app downloads region databases from
`https://www.rfsat.com/products/maps/`, driven by the manifest `index.json`
there. Adding parking means: rebuild each region `.db` with the parking tables,
regenerate `index.json`, and upload both. No server-side code runs — it is all
static files over HTTPS.

---

## What the app expects (the contract you are satisfying)

* A manifest at **`https://www.rfsat.com/products/maps/index.json`**.
* Region files at **`<baseUrl>/<file>`**, e.g.
  `https://www.rfsat.com/products/maps/greece.db`.
* Each region entry carries a **`sha256`** and **`sizeBytes`**; the app verifies
  the hash after download and refuses a file that doesn't match.
* Each entry carries **`dbSchemaVersion`**. Parking databases are **schema 4**.
  The app build that supports parking is **v1.20.17+** (`SUPPORTED_DB_SCHEMA = 4`).
  Older app installs will mark a schema-4 region "unsupported" and keep running
  their existing map until the user updates the app — they will not crash.
* The app updates a region only when its **`version`** integer increases.

---

## Prerequisites (on the machine that builds the maps)

This is the same machine/workflow you already use to build the speed-limit
databases — likely your PC, not the web server itself. You need:

1. Python 3 with `pyosmium`:  `pip install osmium`
2. Your existing map converter (the tool that currently makes `<region>.db`).
3. `add_parking.py` and `build_region.py` from `tools/parking/` in the repo.
4. The Geofabrik extract for each region, e.g. `greece-latest.osm.pbf` from
   <https://download.geofabrik.de/>.

> One-time edit: open `build_region.py` and set `CONVERTER` to however you invoke
> your existing converter. If you prefer to keep running your converter by hand,
> you can skip the `build` subcommand and only use `manifest` (Step 3).

---

## Step 1 — Rebuild each region database WITH parking

For every region you publish, rebuild the `.db` so it contains the parking
tables and is stamped schema 4. Either use the helper:

```bash
python build_region.py build --region greece \
    --pbf greece-latest.osm.pbf --dir ./out
```

…or, if you run your own converter manually, just add the parking step to your
existing process:

```bash
# 1. your existing converter produces ./out/greece.db  (speed limits, etc.)
# 2. then:
python add_parking.py --pbf greece-latest.osm.pbf --db ./out/greece.db
```

Either way you should see, at the end:

```
parking_lot  :     526 features
parking_curb :       0 side-records
```

A **0** curb count is fine (Greece has little curbside data) — the car-park
finder still works; restriction advisories simply stay silent there. Repeat for
every region you host (`ireland.db`, etc.).

> Make sure `add_parking.py` set `schema_version = 4` in the `.db`'s `meta`
> table. `build_region.py` prints it; or check with
> `sqlite3 out/greece.db "SELECT value FROM meta WHERE key='schema_version';"`

---

## Step 2 — Keep your existing DBs and new DBs in one folder

Put every region `.db` you intend to publish in a single directory (e.g.
`./out`). The manifest step scans this folder, so whatever `.db` files are here
become the published set. If a previous `index.json` is in this folder, the
generator reads it to preserve display names and to bump `version` only for
files that actually changed.

---

## Step 3 — Regenerate index.json

```bash
python build_region.py manifest --dir ./out \
    --base-url https://www.rfsat.com/products/maps
```

This computes `sizeBytes` + `sha256` for every `.db`, reads `dbSchemaVersion`
and `dataDate` from each file's `meta` table, and increments `version` for any
file whose content changed since the last manifest. It prints a summary:

```
  greece               61.7 MB  schema 4  changed -> v3
  ireland              44.2 MB  schema 4  unchanged v5
wrote ./out/index.json (2 region(s))
```

`changed -> vN` means that file must be re-uploaded; `unchanged` means the
server copy is still current.

> The **version bump is what triggers the update on users' phones.** Because it
> keys off the sha256, simply rebuilding a region with newer OSM data and
> regenerating the manifest is enough — the app will offer the update.

---

## Step 4 — Upload to the server

Upload to the web root that serves `https://www.rfsat.com/products/maps/`
(via SFTP/SCP/your host's file manager). Upload:

* **every changed `.db`** (the ones marked `changed` in Step 3), and
* **`index.json`** (always).

```bash
# example with scp — adjust host/path to your hosting
scp ./out/greece.db ./out/index.json \
    user@rfsat.com:/var/www/rfsat.com/products/maps/
```

**Order matters:** upload the `.db` files FIRST, then `index.json` last. The
manifest is what tells apps a new version exists; if it arrives before the file
it points to, an app could try to download a `.db` that isn't there yet and fail
its hash check. Uploading data first, manifest last, avoids that window.

---

## Step 5 — Verify from outside

From any machine (not the server), confirm the files are reachable and correct:

```bash
# manifest is served as JSON
curl -s https://www.rfsat.com/products/maps/index.json | head -20

# the .db is reachable and the size matches the manifest
curl -sI https://www.rfsat.com/products/maps/greece.db | grep -i content-length

# the hash matches what the manifest claims (the app will check this)
curl -s https://www.rfsat.com/products/maps/greece.db | sha256sum
```

That last hash must equal the `sha256` for `greece.db` in `index.json`. If they
differ, the upload was incomplete — re-upload the `.db`.

**MIME/type note:** most servers send `.db` as
`application/octet-stream`, which is correct. Ensure `.db` is **not** being
rewritten, gzip-transcoded, or blocked by any rule — the app downloads the raw
bytes and hashes them, so any on-the-fly modification breaks the hash check.
`index.json` should be served as `application/json` (or `text/plain`); the app
parses it either way.

---

## Step 6 — Confirm in the app

On a device running DBM **v1.20.17+**:

1. Settings → the map/region manager → it fetches `index.json`.
2. Regions you rebuilt should show **Update available**; download one.
3. The download bar completes and the hash verifies (a mismatch shows an error
   and discards the file — that's the safety net working).
4. Enable **Settings → Detection elements → "Parking advice when stopped"**,
   then stop somewhere with known parking data. You should see the advisory
   banner, or "No parking data for this area" where coverage is absent.

---

## Ongoing updates (the routine, once set up)

Each refresh cycle is just:

```bash
# fresh OSM data -> rebuild -> regenerate manifest -> upload changed files
python build_region.py build --region greece --pbf greece-latest.osm.pbf --dir ./out
python build_region.py manifest --dir ./out --base-url https://www.rfsat.com/products/maps
# upload the changed .db(s) then index.json
```

Because `version` auto-increments only on real change, you can safely rebuild
everything each cycle and upload only what the summary marks `changed`.

---

## Rollback

If a freshly uploaded region misbehaves, restore the previous `.db` for that
region and either restore the previous `index.json` or re-run the manifest step
against the restored file. Since the app keys on `version`, the safest rollback
is to keep the version integer the SAME as the good build (overwrite the bad
file with the good one and regenerate) so no phone is left on the bad copy.
Keeping the prior `index.json` as `index.json.bak` before each upload makes this
one step.
