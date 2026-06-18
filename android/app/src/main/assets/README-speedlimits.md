# Bundled OSM speed-limit map

The speed-limit fusion loads a bundled OpenStreetMap extract named
`speedlimits.osm` from this assets folder. It is OPTIONAL: if absent, the app
runs fine using camera signs + the remembered-sign cache only (no map layer).

## How to produce speedlimits.osm

1. Go to https://overpass-turbo.eu
2. Pan/zoom to the area you drive (cover it with margin).
3. Run this query, then Export -> raw OSM data:

   [out:xml];
   way["highway"]({{bbox}});
   (._;>;);
   out;

   (Use way["highway"] WITHOUT the ["maxspeed"] filter — untagged roads must be
   included so the matcher snaps to the correct road even where the limit is
   unknown. The fuser reports "unknown" there rather than mis-matching to a far
   tagged road.)

4. Save the file as `speedlimits.osm` in this folder and rebuild.

## Size guidance
- A route corridor or small region (a few MB) bundles fine.
- A whole large city may be tens of MB — if so, trim to the driven corridor, or
  we move to a pre-built spatial DB (SQLite/R-tree) instead of raw XML.

The map data is read-only and never transmitted; matching happens entirely
on-device.
