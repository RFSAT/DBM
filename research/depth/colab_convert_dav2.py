# ============================================================================
#  Depth Anything V2 Small (metric / outdoor)  ->  LiteRT .tflite
#  Google Colab notebook  —  paste each CELL into its own Colab cell and run
#  top to bottom.  https://colab.research.google.com  (New notebook)
# ----------------------------------------------------------------------------
#  Why Colab: the litert-torch converter ships wheels ONLY for Linux + CPython
#  3.10/3.11. A Windows / Python-3.13 machine matches NEITHER, so it can never
#  pip-install natively. Colab is Linux + Python 3.11 => both conditions met.
#  Runtime > Change runtime type > CPU is fine (GPU only speeds the trace).
# ============================================================================


# ========================= CELL 1 — check environment =======================
import sys, platform
print("python :", sys.version.split()[0])          # expect 3.11.x
print("system :", platform.system(), platform.machine())   # expect Linux x86_64
assert sys.version_info[:2] in [(3, 10), (3, 11)], \
    "litert-torch needs Python 3.10/3.11. In Colab: Runtime > Change runtime type."
print("environment OK")


# ========================= CELL 2 — install =================================
# Clean Colab env, so no version conflict. Quiet install.
!pip install -q litert-torch ai-edge-litert transformers pillow numpy
print("installed")


# ========================= CELL 3 — load + wrap model =======================
import torch, numpy as np
from transformers import AutoModelForDepthEstimation

SIZE = 518          # DAV2 native. Try 384 or 256 later for a faster/smaller model.
MODEL_ID = "depth-anything/Depth-Anything-V2-Metric-Outdoor-Small-hf"

hf = AutoModelForDepthEstimation.from_pretrained(MODEL_ID).eval()

class Wrapper(torch.nn.Module):
    """Plain tensor-in / tensor-out, fixed shape (TFLite needs static shapes)."""
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, pixel_values):
        return self.m(pixel_values=pixel_values).predicted_depth

model = Wrapper(hf)
sample = (torch.rand(1, 3, SIZE, SIZE),)
with torch.no_grad():
    ref = model(*sample)
print("pytorch forward OK -> depth", tuple(ref.shape))


# ========================= CELL 4 — convert =================================
import litert_torch
edge = litert_torch.convert(model, sample)

edge_np = np.array(edge(*sample)).reshape(-1)
ref_np  = ref.detach().numpy().reshape(-1)
mean_abs = float(np.abs(edge_np - ref_np).mean())
print(f"mean|edge - pytorch| = {mean_abs:.5f}")
print("GOOD (< ~0.05): conversion is faithful." if mean_abs < 0.05
      else "LARGE diff: an op likely lowered wrong — tell Claude this number.")

edge.export("dav2_small_metric.tflite")
print("wrote dav2_small_metric.tflite")


# ============ CELL 5 — OPTIONAL: test on a real captured frame ==============
# Upload one frame_XXXXXX.jpg from a depth_capture session to check the depth
# map looks sane (near = one end of the scale, far = the other).
# from google.colab import files
# up = files.upload()                       # pick a frame_*.jpg
# from PIL import Image
# name = next(iter(up))
# img = Image.open(name).convert("RGB").resize((SIZE, SIZE))
# x = torch.from_numpy(np.array(img)).permute(2,0,1)[None].float()/255.0
# d = np.array(edge(x))[0]
# print("depth range (m):", float(d.min()), "..", float(d.max()))
# import matplotlib.pyplot as plt
# plt.subplot(1,2,1); plt.imshow(img); plt.axis('off'); plt.title('frame')
# plt.subplot(1,2,2); plt.imshow(d, cmap='turbo'); plt.axis('off'); plt.title('depth')
# plt.show()


# ========================= CELL 6 — download ================================
from google.colab import files
files.download("dav2_small_metric.tflite")
# Drop this file into DBM's assets on your Windows machine and build as normal.
