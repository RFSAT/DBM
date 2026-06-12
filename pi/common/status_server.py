#!/usr/bin/env python3
"""DMS node status endpoint.

GET http://<node>:8080/status ->
{ "role": "...", "uptime_s": 123, "temp_c": 48.2, "throttled": "0x0",
  "stream_ready": true, "stream_readers": 1 }
"""
import json
import subprocess
import sys
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

ROLE = sys.argv[1] if len(sys.argv) > 1 else "unknown"
START = time.time()


def _cmd(args):
    try:
        return subprocess.check_output(args, text=True).strip()
    except Exception:
        return ""


def _stream_info():
    try:
        with urllib.request.urlopen(
                "http://127.0.0.1:9997/v3/paths/get/" + ROLE, timeout=2) as r:
            d = json.loads(r.read())
            return bool(d.get("ready")), len(d.get("readers", []))
    except Exception:
        return False, 0


class H(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/status":
            self.send_error(404)
            return
        temp = _cmd(["vcgencmd", "measure_temp"])      # temp=48.2'C
        throttled = _cmd(["vcgencmd", "get_throttled"])  # throttled=0x0
        ready, readers = _stream_info()
        body = json.dumps({
            "role": ROLE,
            "uptime_s": int(time.time() - START),
            "temp_c": float(temp.split("=")[1].split("'")[0]) if "=" in temp else None,
            "throttled": throttled.split("=")[1] if "=" in throttled else None,
            "stream_ready": ready,
            "stream_readers": readers,
        }).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):  # quiet
        pass


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8080), H).serve_forever()
