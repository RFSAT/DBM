# Greece — first fully-supported region: build & deploy runbook

Greece is the first region with full parking + speed-camera support. OSM coverage
pre-check (via Overpass, run greece_coverage_check.py to refresh):

    speed cameras (Greece)      : ~413      <- enough to be useful
    car parks (Greece)          : ~15,572   <- richly mapped
    central Athens sample       : 2 cameras, 437 car parks

That 413-camera national count is the go signal: real data, not so sparse the
feature feels dead. Cameras cluster on highways / ring roads more than dense city
centres, which is why central Athens shows only a couple.

--------------------------------------------------------------------------------
## Prerequisites (one-time)

    pip install osmium            # for add_parking.py
    # plus your existing base-map converter (the tool that builds greece.db with
    # speed limits). add_parking.py augments that .db; it does not replace it.

--------------------------------------------------------------------------------
## Step 1 — Download the Greece extract

From https://download.geofabrik.de/europe/greece.html download:

    greece-latest.osm.pbf         (~400-500 MB; updated daily)

Geofabrik does not subdivide Greece, so the whole country is one file and one
region id: `greece` -> `greece.db`. This matches MapCatalog.kt's expectation.

--------------------------------------------------------------------------------
## Step 2 — Build the base Greece database (your existing process)

Run your current converter to produce `greece.db` with speed limits etc., exactly
as today. Nothing changes here.

--------------------------------------------------------------------------------
## Step 3 — Add parking + speed cameras

    python add_parking.py --pbf greece-latest.osm.pbf --db greece.db

One pass over the .pbf adds parking_lot, parking_curb and speed_camera tables and
stamps schema_version = 5. Expected output (numbers approximate, will grow as OSM
improves):

    parking_lot  :  ~15000 features
    parking_curb :    (varies; Greek curbside data is sparse -> can be low/zero)
    speed_camera :   ~400+ cameras

If speed_camera comes back near the ~413 the pre-check showed, extraction worked.
A big shortfall means the .pbf is a partial extract or the tag filter missed —
re-check with greece_coverage_check.py.

--------------------------------------------------------------------------------
## Step 4 — Verify the Greece database

    sqlite3 greece.db "SELECT value FROM meta WHERE key='schema_version';"     # -> 5
    sqlite3 greece.db "SELECT COUNT(*) FROM speed_camera;"                      # -> ~413
    sqlite3 greece.db "SELECT COUNT(*) FROM parking_lot;"                       # -> ~15000
    sqlite3 greece.db "SELECT lat,lon,maxspeed FROM speed_camera LIMIT 5;"

Spot-check a couple of camera coordinates on a map to confirm they land on real
Greek roads you recognise — Greece being home turf is exactly why it's the ideal
first region to ground-truth.

--------------------------------------------------------------------------------
## Step 5 — Regenerate the manifest and deploy

Follow SERVER-DEPLOYMENT.md. In short:
  1. Put greece.db in your maps output folder.
  2. Regenerate index.json (bump greece's `version`, set dbSchemaVersion=5,
     recompute sha256 + sizeBytes).
  3. Upload greece.db FIRST, then index.json LAST, to
     https://www.rfsat.com/products/maps/
  4. Verify from outside:
        curl -sI https://www.rfsat.com/products/maps/greece.db | grep -i content-length
        curl -s  https://www.rfsat.com/products/maps/greece.db | sha256sum
     The sha256 must equal index.json's entry for greece.db.

--------------------------------------------------------------------------------
## Step 6 — Confirm in the app (v1.20.20+)

  1. App must be v1.20.20+ (it accepts schema-5 DBs; older builds refuse them and
     keep their current map — safe behaviour).
  2. Map manager shows Greece -> "Update available" -> download.
  3. Enable Settings > Detection elements > "Speed camera warnings" (OFF by
     default; note the legal caveat).
  4. Drive toward a mapped camera -> amber banner ~10 s ahead, once per camera.
  5. Parking advice: enable "Parking advice when stopped"; stop near a mapped car
     park -> advisory banner.

--------------------------------------------------------------------------------
## Legal note (Greece / EU)

Dynamic speed-camera warnings are restricted in some EU countries. Greece is
generally permissive for camera-location information, but confirm the current
position before promoting the feature. It ships OFF by default with an in-app
caveat placing responsibility on the driver, consistent with OSM guidance.

--------------------------------------------------------------------------------
## Refresh cadence

Geofabrik updates daily; monthly rebuilds are plenty for camera/parking data.
Each refresh: re-download greece-latest.osm.pbf -> Steps 2-5. The manifest
`version` bump is what pushes the update to phones.
