#!/usr/bin/env python3
"""
greece_coverage_check.py — quick coverage pre-check for Greece via Overpass.

Before building the full region database, this tells you HOW MUCH parking and
speed-camera data OSM actually has for Greece, so you know what to expect. It
queries the Overpass API (no download of the big .pbf needed) for national
counts, plus a central-Athens sample box.

Runs anywhere with internet + Python 3 (stdlib only). Overpass can be slow for
national queries; be patient or narrow the area if it times out.

    python greece_coverage_check.py
"""
import json
import sys
import urllib.request
import urllib.parse

OVERPASS_MIRRORS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
]

# Greece country area id in Overpass = 3600000000 + OSM relation id (192307).
GR_AREA = 3600192307

QUERIES = {
    "speed cameras (Greece)":
        f'[out:json][timeout:180];area({GR_AREA})->.gr;'
        'node["highway"="speed_camera"](area.gr);out count;',
    "car parks amenity=parking (Greece)":
        f'[out:json][timeout:180];area({GR_AREA})->.gr;'
        'nwr["amenity"="parking"](area.gr);out count;',
    "speed cameras (central Athens box)":
        '[out:json][timeout:60];'
        'node["highway"="speed_camera"](37.96,23.71,38.01,23.78);out count;',
    "car parks (central Athens box)":
        '[out:json][timeout:60];'
        'nwr["amenity"="parking"](37.96,23.71,38.01,23.78);out count;',
}


def run(q):
    data = urllib.parse.urlencode({"data": q}).encode()
    last = None
    for base in OVERPASS_MIRRORS:
        try:
            req = urllib.request.Request(base, data=data,
                                         headers={"User-Agent": "DBM-coverage-check"})
            with urllib.request.urlopen(req, timeout=200) as r:
                j = json.load(r)
            for el in j.get("elements", []):
                if "tags" in el and "total" in el["tags"]:
                    return el["tags"]["total"]
            return str(len(j.get("elements", [])))
        except Exception as e:
            last = e
            continue
    raise last if last else RuntimeError("all mirrors failed")


def main():
    print("Greece OSM coverage pre-check (via Overpass)\n" + "-" * 44)
    for label, q in QUERIES.items():
        try:
            n = run(q)
            print(f"  {label:42s}: {n}")
        except Exception as e:
            print(f"  {label:42s}: ERROR ({e.__class__.__name__}: {e})")
    print("\nInterpretation:")
    print("  * A healthy national speed-camera count (hundreds+) means the")
    print("    warning feature will have real data to work with.")
    print("  * A low count means cameras are sparsely mapped in Greece; the")
    print("    feature stays silent where there's no data (correct, not a bug).")
    print("  * Car parks are usually well mapped, so expect a solid number.")


if __name__ == "__main__":
    main()
