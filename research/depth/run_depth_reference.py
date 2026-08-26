#!/usr/bin/env python3
"""
run_depth_reference.py — Phase-0 reference harness.

Runs full-size Depth Anything V2 (PyTorch, on the PC) over a capture session
recorded by DBM's in-app depth capture, producing "gold" reference depth maps.
These are the ground truth we later compare the on-phone TFLite/LiteRT output
against, so conversion/quantisation loss can be separated from model limitation.

It also computes the two things Phase 2 needs, purely from the recorded data:
  1. per-frame optical flow (Farneback) on the road region, and
  2. a flow-vs-speed scale probe — for static road points, depth ~= speed*dt/flow,
     which is the sensor-grounded scale that makes monocular depth METRIC without
     any human input. This is a first look at whether that relationship is clean
     enough to calibrate on.

Input: a session dir from the phone (depth_capture/session_*/) containing
frame_000001.jpg ... and telemetry.csv (frame,filename,tMs,dtMs,speedKmh,speedSrc,
lat,lon,headingDeg).

Usage:
    pip install torch transformers pillow numpy opencv-python matplotlib
    python run_depth_reference.py --session /path/to/session_XXXX --out ./ref_out

Outputs into --out:
    depth/frame_000001.npy ...    reference depth maps (float, metres if metric head)
    depth/frame_000001_vis.png    colourised previews
    flow_scale.csv                per-frame median flow, speed, implied scale
    flow_scale.png                scatter of implied depth-scale vs speed
    summary.txt                   quick verdict on scale-relationship tightness

Runs on a PC (needs torch). Cannot run in the DBM build sandbox.
"""

import argparse
import csv
import os
import sys


def load_model():
    import torch
    from transformers import AutoModelForDepthEstimation, AutoImageProcessor
    model_id = "depth-anything/Depth-Anything-V2-Metric-Outdoor-Small-hf"
    print(f"loading {model_id} ...")
    proc = AutoImageProcessor.from_pretrained(model_id)
    model = AutoModelForDepthEstimation.from_pretrained(model_id).eval()
    return proc, model


def read_telemetry(session):
    rows = []
    with open(os.path.join(session, "telemetry.csv")) as f:
        for r in csv.DictReader(f):
            rows.append(r)
    if not rows:
        sys.exit("telemetry.csv is empty — was capture actually running while driving?")
    return rows


def depth_for(proc, model, pil_img):
    import torch
    inp = proc(images=pil_img, return_tensors="pt")
    with torch.no_grad():
        out = model(**inp)
    d = out.predicted_depth[0].cpu().numpy()   # [H,W], metres for metric head
    return d


def colourise(depth):
    import numpy as np
    d = depth.copy()
    lo, hi = np.percentile(d, 2), np.percentile(d, 98)
    d = np.clip((d - lo) / max(1e-6, hi - lo), 0, 1)
    # simple turbo-ish map without matplotlib dependency in the hot path
    return (d * 255).astype("uint8")


def run(session, out_dir):
    import numpy as np
    from PIL import Image
    import cv2

    os.makedirs(os.path.join(out_dir, "depth"), exist_ok=True)
    proc, model = load_model()
    rows = read_telemetry(session)
    print(f"{len(rows)} frames in session")

    prev_gray = None
    flow_rows = []

    for i, r in enumerate(rows):
        fp = os.path.join(session, r["filename"])
        if not os.path.exists(fp):
            continue
        pil = Image.open(fp).convert("RGB")
        img = np.array(pil)

        # --- reference depth ---
        depth = depth_for(proc, model, pil)
        base = os.path.splitext(r["filename"])[0]
        np.save(os.path.join(out_dir, "depth", base + ".npy"), depth)
        Image.fromarray(colourise(depth)).save(
            os.path.join(out_dir, "depth", base + "_vis.png"))

        # --- optical flow on the lower-central road region ---
        gray = cv2.cvtColor(img, cv2.COLOR_RGB2GRAY)
        h, w = gray.shape
        roi = (slice(int(h * 0.55), int(h * 0.95)), slice(int(w * 0.30), int(w * 0.70)))
        speed_kmh = float(r["speedKmh"] or 0)
        dt_ms = float(r["dtMs"] or 0)
        if prev_gray is not None and speed_kmh > 10:
            flow = cv2.calcOpticalFlowFarneback(
                prev_gray[roi], gray[roi], None, 0.5, 3, 15, 3, 5, 1.2, 0)
            mag = np.sqrt(flow[..., 0] ** 2 + flow[..., 1] ** 2)
            med_flow = float(np.median(mag))           # px/frame in ROI
            # implied metric scale probe: real forward translation this frame
            #   dm = speed(m/s) * dt(s);   for road points depth ~ dm / flow_px
            # so (dm / med_flow) is a depth-per-pixel-flow scale we expect to be
            # ~constant if the geometry is stable. We log it against speed.
            if med_flow > 0.05:
                dm = (speed_kmh / 3.6) * (dt_ms / 1000.0 if dt_ms > 0 else 0)
                scale_probe = dm / med_flow
                med_ref_depth = float(np.median(depth[roi]))
                flow_rows.append((base, speed_kmh, med_flow, dm, scale_probe, med_ref_depth))
        prev_gray = gray
        if i % 25 == 0:
            print(f"  {i}/{len(rows)}")

    # --- write flow/scale probe ---
    fs_path = os.path.join(out_dir, "flow_scale.csv")
    with open(fs_path, "w", newline="") as f:
        wtr = csv.writer(f)
        wtr.writerow(["frame", "speedKmh", "medianFlowPx", "fwdTranslM",
                      "scaleProbe", "medianRefDepthM"])
        wtr.writerows(flow_rows)

    # --- verdict: how tight is the model-depth vs flow-derived-depth relation? ---
    summary = summarise(flow_rows)
    with open(os.path.join(out_dir, "summary.txt"), "w") as f:
        f.write(summary)
    print("\n" + summary)
    print(f"reference depth + flow_scale.csv written to {out_dir}")


def summarise(flow_rows):
    import numpy as np
    if len(flow_rows) < 10:
        return ("Not enough moving-speed frames (need >10 with speed>10 km/h). "
                "Capture a longer clip with sustained driving.")
    arr = np.array([[r[1], r[2], r[4], r[5]] for r in flow_rows], dtype=float)
    speed, flow, scale_probe, ref_depth = arr.T
    # If the model depth is truly metric AND geometry stable, ref_depth and the
    # flow-derived depth should correlate strongly; the ratio should be ~const.
    ratio = ref_depth / np.maximum(scale_probe, 1e-6)
    cv = float(np.std(ratio) / max(1e-6, np.mean(ratio)))     # coeff of variation
    corr = float(np.corrcoef(ref_depth, scale_probe)[0, 1]) if len(arr) > 2 else float("nan")
    lines = [
        "Phase-0 scale-relationship probe",
        f"  moving frames analysed : {len(flow_rows)}",
        f"  ref-depth vs flow-depth correlation : {corr:.3f}  (want > ~0.6)",
        f"  scale ratio coeff-of-variation      : {cv:.3f}  (want < ~0.4)",
        "",
    ]
    if corr > 0.6 and cv < 0.4:
        lines.append("VERDICT: promising — the speed-driven scale looks learnable. "
                     "Proceed to Phase 2 calibration.")
    elif corr > 0.4:
        lines.append("VERDICT: weak-but-present relationship. Likely needs a road-"
                     "mask (exclude other cars) and pitch handling before it is "
                     "usable. Worth investigating, not yet trusting.")
    else:
        lines.append("VERDICT: no clean relationship in this clip. Check that speed "
                     "was valid (OBD/GPS healthy), the mount was stable, and the "
                     "ROI actually contains road, before drawing conclusions.")
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--session", required=True, help="depth_capture/session_* dir from the phone")
    ap.add_argument("--out", default="./ref_out")
    a = ap.parse_args()
    run(a.session, a.out)


if __name__ == "__main__":
    main()
