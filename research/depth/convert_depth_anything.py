#!/usr/bin/env python3
"""
convert_depth_anything.py — convert Depth Anything V2 Small (metric, outdoor) to
a LiteRT / TFLite flatbuffer for on-device inference in DBM.

WHY THIS PATH (read before changing it):
  Naive PyTorch -> TF -> TFLite conversion of Depth Anything V2 FAILS: the ViT
  ops hit "not implemented"/runtime errors (see the DAV2 issue tracker). The
  reliable route is Google's ai-edge-torch (a.k.a. litert-torch), which lowers
  PyTorch directly to a .tflite via torch.export, avoiding the ONNX->TF
  transpose-insertion mess AND producing a LiteRT-native flatbuffer — the same
  runtime DBM already uses (com.google.ai.edge.litert), so it drops straight in.

  Fallback if ai-edge-torch op coverage is incomplete for some layer:
    onnx2tf (PyTorch -> ONNX -> TF -> TFLite) with the flatbuffer_direct backend.
  That path is noted at the bottom but not automated here.

This script runs on a PC (ideally with a GPU for the export trace). It CANNOT be
run in the DBM build sandbox — it needs torch + ai-edge-torch. Run it on your
workstation.

Install:
    pip install torch torchvision transformers pillow numpy
    pip install ai-edge-torch            # or: pip install litert-torch

Usage:
    # float32 (baseline, most compatible)
    python convert_depth_anything.py --out dav2_small_metric.tflite

    # int8 dynamic-range (smaller/faster; validate accuracy after)
    python convert_depth_anything.py --out dav2_small_metric_int8.tflite --quantize

    # different input size (speed/accuracy trade; must match the app's resize)
    python convert_depth_anything.py --size 384 --out dav2_small_384.tflite

After conversion, run verify_export.py to confirm input/output shapes and to
sanity-check the depth map against the PyTorch reference.
"""

import argparse
import sys


def load_pytorch_model(size: int):
    """Load Depth Anything V2 Small (metric, outdoor) as an eval() module that
    takes a float image tensor [1,3,H,W] in 0..1 and returns depth [1,H,W]."""
    import torch
    from transformers import AutoModelForDepthEstimation

    # The metric outdoor checkpoint (max_depth=80). If you prefer relative depth
    # (scale recovered entirely by our speed calibration), swap to the plain
    # "depth-anything/Depth-Anything-V2-Small-hf" checkpoint.
    model_id = "depth-anything/Depth-Anything-V2-Metric-Outdoor-Small-hf"
    print(f"loading {model_id} ...")
    model = AutoModelForDepthEstimation.from_pretrained(model_id)
    model.eval()

    class Wrapper(torch.nn.Module):
        """Fixed-shape wrapper: TFLite needs static shapes, and we want a plain
        tensor in / tensor out (the HF model returns a dataclass)."""
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, pixel_values):
            out = self.m(pixel_values=pixel_values)
            # predicted_depth: [B, H, W]
            return out.predicted_depth

    return Wrapper(model)


def convert(size: int, out_path: str, quantize: bool):
    import torch
    # The package was renamed ai-edge-torch -> litert-torch (Jan 2026). Prefer
    # the new name; fall back to the legacy import for older environments.
    try:
        import litert_torch as edge_converter
    except ImportError:
        try:
            import ai_edge_torch as edge_converter
        except ImportError:
            sys.exit(
                "Could not import litert-torch (the PyTorch->LiteRT converter).\n"
                "Install it in a CLEAN venv on Python 3.10 or 3.11 (Linux only):\n"
                "  python3.11 -m venv depthenv && source depthenv/bin/activate\n"
                "  pip install --upgrade pip\n"
                "  pip install litert-torch torchvision ai-edge-litert\n"
                "Notes:\n"
                "  * Do NOT install both ai-edge-torch AND litert-torch — the old\n"
                "    name is deprecated and mixing them causes the litert-converter\n"
                "    version conflict you saw.\n"
                "  * Python 3.12 currently fails at import (ai_edge_tensorflow / \n"
                "    'No module named tensorflow.python'); use 3.10 or 3.11.\n"
                "  * Linux only. On Windows/macOS run this step under WSL2 or a\n"
                "    Linux box / container.\n"
                "  * If the stable release won't resolve, try the nightly:\n"
                "      pip install --pre litert-torch-nightly torchvision ai-edge-litert")

    model = load_pytorch_model(size)
    sample = (torch.rand(1, 3, size, size),)

    # Quick PyTorch sanity forward so we fail early if the model/input is wrong.
    with torch.no_grad():
        ref = model(*sample)
    print(f"pytorch forward OK: input [1,3,{size},{size}] -> depth {tuple(ref.shape)}")

    quant_cfg = None
    if quantize:
        # PT2E dynamic-range quantisation. Import path follows whichever package
        # name resolved above.
        try:
            from litert_torch.quantize import pt2e_quantizer
            from litert_torch.quantize import quant_config as qc
        except ImportError:
            from ai_edge_torch.quantize import pt2e_quantizer
            from ai_edge_torch.quantize import quant_config as qc
        print("preparing PT2E dynamic-range quantisation ...")
        quantizer = pt2e_quantizer.PT2EQuantizer().set_global(
            pt2e_quantizer.get_symmetric_quantization_config())
        model = pt2e_quantizer.prepare_pt2e(model, quantizer)  # calibration-free (dynamic)
        quant_cfg = qc.QuantConfig(pt2e_quantizer=quantizer)

    print("converting via ai-edge-torch (torch.export -> TFLite/LiteRT) ...")
    edge = edge_converter.convert(model, sample, quant_config=quant_cfg) \
        if quant_cfg else edge_converter.convert(model, sample)

    # Numerical check: edge model vs pytorch, same input.
    import numpy as np
    edge_out = edge(*sample)
    edge_np = edge_out if isinstance(edge_out, np.ndarray) else np.array(edge_out)
    diff = float(np.abs(edge_np.reshape(-1) - ref.detach().numpy().reshape(-1)).mean())
    print(f"mean|edge-pytorch| = {diff:.5f} (small is good; large means op mismatch)")

    edge.export(out_path)
    print(f"wrote {out_path}")
    print("Next: verify_export.py --model", out_path, "--size", size)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--size", type=int, default=518,
                    help="square input size (DAV2 native is 518; try 384/256 for speed)")
    ap.add_argument("--out", default="dav2_small_metric.tflite")
    ap.add_argument("--quantize", action="store_true",
                    help="apply PT2E dynamic-range int8 quantisation")
    a = ap.parse_args()
    convert(a.size, a.out, a.quantize)


if __name__ == "__main__":
    main()
