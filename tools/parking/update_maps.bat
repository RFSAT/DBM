@echo off
REM ---------------------------------------------------------------------------
REM update_maps.bat - convenience wrapper for update_maps.py
REM
REM Downloads the latest Geofabrik .osm.pbf for every map in index.json (full
REM countries in this folder, sub-regions in their parent sub-folders), verifies
REM each against Geofabrik's .md5, and deletes the older .pbf after a successful
REM update. Special sub-regions (alps, dach, great-britain, etc.) are skipped.
REM
REM Run it from the map folder (the one that contains index.json), or pass --dir.
REM Requires Python 3 and curl.exe (both ship with / are easy to add on Win10+).
REM
REM   update_maps.bat                 update everything
REM   update_maps.bat --dry-run       show what would be downloaded
REM   update_maps.bat --only greece,germany
REM ---------------------------------------------------------------------------
setlocal
python "%~dp0update_maps.py" %*
if errorlevel 1 (
    echo.
    echo One or more downloads failed - see the log above.
    exit /b 1
)
endlocal
