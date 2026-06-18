# Speed-limit map databases

The map data is NOT bundled in the app. It is a SQLite + R-tree database
(produced by dbm-tools/osm_to_speedlimitdb.py) that the app loads from device
storage and queries spatially.

## Where the app looks for the database (in order)
1. <app files>/maps/<name>.db          - where the downloader (step 3) places it
2. <app external files>/maps/<name>.db
3. /sdcard/Download/<name>.db           - for manual adb-push during development

Current default name: greece.db

## Testing now (before the downloader exists)
Build greece.db with the pre-processor, then push it to the phone:

    adb push greece.db /sdcard/Download/greece.db

The app finds it there and the map layer activates. If no database is present,
fusion runs camera-signs + remembered-cache only (no crash).

## Production (step 3)
The region-download manager fetches per-region .db files from
https://www.rfsat.com/public/maps/v1/ (with an index.json listing regions),
into <app files>/maps/. Nothing is bundled or manually pushed.
