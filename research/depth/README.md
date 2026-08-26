# DBM metric-depth research — Phase 0 & 1 tooling

Two halves: an **in-app capture** (already built into DBM) and these **PC-side
scripts**. Together they execute Phase 0 (data + reference) and de-risk Phase 1
(does the model convert at all).

## The capture side (in the app, no script)

DBM v1.20.21+ has **Settings → Research capture (depth Phase 0)**. Start
monitoring, start capture, drive. It writes to the phone at:

    Android/data/com.DBM/files/depth_capture/session_<ts>/
        frame_000001.jpg ...        road-camera frames (~5 fps, capped at 3000)
        telemetry.csv               frame,filename,tMs,dtMs,speedKmh,speedSrc,lat,lon,headingDeg

Frames and speed share DBM's clock, so they are exactly synchronised — no
external sync needed. Pull the session dir off the phone over USB.

## The PC side (these scripts — need torch, run on your workstation)

    pip install torch torchvision transformers pillow numpy opencv-python

**Converter install (the fiddly bit):** the PyTorch->LiteRT converter was renamed
`ai-edge-torch` -> `litert-torch` (Jan 2026). Install ONLY the new name, in a
clean virtual environment, or pip hits a `litert-converter` version conflict:

    python3.11 -m venv depthenv && source depthenv/bin/activate
    pip install --upgrade pip
    pip install litert-torch torchvision ai-edge-litert

- Linux only; Python 3.10 or 3.11 (3.12 currently fails at import).
- On Windows/macOS use WSL2 or a Linux container.
- Don't install both ai-edge-torch and litert-torch.
- If stable won't resolve: `pip install --pre litert-torch-nightly torchvision ai-edge-litert`.

### 1. Prove the conversion path (Phase 1's biggest unknown)

    # Linux + Python 3.10/3.11 + a CLEAN venv (see notes below)
    pip install litert-torch torchvision ai-edge-litert
    python convert_depth_anything.py --out dav2_small_metric.tflite
    # optional: --quantize (int8), --size 384 (speed)

Converts Depth Anything V2 Small (metric/outdoor) to a **LiteRT-native** .tflite
via ai-edge-torch — the path that actually works (naive TF conversion of DAV2
fails on ViT ops). Output drops into DBM's existing LiteRT runtime. It prints the
mean abs difference between the PyTorch and converted outputs, so you know
immediately whether an op silently mismatched.

### 2. Build reference maps + probe the scale relationship (Phase 0 core)

    python run_depth_reference.py --session /path/to/session_XXXX --out ./ref_out

Runs full-size DAV2 (PyTorch) over your captured drive to make "gold" depth maps,
computes optical flow on the road region, and — the important part — tests the
Phase-2 premise directly: for road points, `depth ≈ speed·dt / flow`, so the
model's depth and the flow-derived depth should track each other if the geometry
is stable. It prints a verdict:

  * correlation > ~0.6 and low scale variation → speed-driven metric scale is
    learnable → proceed to Phase 2.
  * weak → needs a road-mask / pitch handling first.
  * none → check speed validity, mount stability, ROI before concluding.

This is the go/no-go evidence for the whole calibration idea, produced from one
real drive and zero human labelling — measurement, not assertion.

## Order

1. Drive + capture one clip (5–10 min, varied speed, ideally with OBD speed).
2. `convert_depth_anything.py` — retire the "does it convert" risk (no clip needed).
3. `run_depth_reference.py` — reference maps + the scale-relationship verdict.
4. Then Phase 1 on-device benchmark (separate: the standalone FPS/thermal screen).

Neither PC script can run in the DBM build sandbox (they need torch); they are
syntax-checked and their pure-logic parts are unit-tested, but run them on your
machine and report back what the conversion diff and the scale verdict say.

## IMPORTANT: the converter is Linux-only

`litert-torch` and its deps (`litert-converter`, `ai-edge-tensorflow`) publish
wheels **only for Linux + CPython 3.10/3.11**. On Windows or macOS pip reports
"no matching distributions available for your environment" and walks back through
every version — that is not a version conflict, it is the packages not existing
for your OS. No pinning fixes it. Run the conversion in one of these Linux envs
(the rest of the pipeline is cross-platform; only this step is pinned):

1. **WSL2 (recommended on Windows 11):**
   ```
   wsl --install -d Ubuntu
   # then inside Ubuntu:
   python3.11 -m venv depthenv && source depthenv/bin/activate
   pip install --upgrade pip
   pip install litert-torch torchvision ai-edge-litert transformers pillow numpy opencv-python
   python convert_depth_anything.py --out dav2_small_metric.tflite
   ```

2. **Docker (reproducible, host-agnostic):** use `Dockerfile.convert` here:
   ```
   docker build -f Dockerfile.convert -t dbm-depth-convert .
   docker run --rm -v "${PWD}:/work" dbm-depth-convert \
       python convert_depth_anything.py --out /work/dav2_small_metric.tflite
   ```

3. **Google Colab (zero local setup, fastest):** paste `colab_convert_dav2.py`
   cells into a Colab notebook and download the resulting .tflite. Colab is
   Linux+Py3.11, so the wheels resolve.

The `.tflite` is portable — produce it in any of these, then use it from the
Windows-based DBM build/CI as normal.
