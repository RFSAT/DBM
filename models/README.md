# DBM model assets — activatable upgrades

DBM uses on-device models. The application works with the bundled baseline
models, and automatically uses an upgraded model when its asset is present in
`android/app/src/main/assets/`. This lets the algorithm-review recommendations
be adopted by dropping in a trained/converted model, with no code change.

| Asset filename (in assets/) | Replaces / adds | Status | Source to produce |
|---|---|---|---|
| `efficientdet_lite0.tflite` | Object detector (baseline) | bundled / CI-fetched | MediaPipe model zoo |
| `yolo26n.tflite` | Object detector (preferred) | **available — CI-fetched** | Official: github.com/ultralytics/yolo-flutter-app/releases/download/v0.3.5/yolo26n_int8.tflite (rename to yolo26n.tflite). Or fine-tune YOLO26-nano and export TFLite |
| `face_landmarker.task` | Face landmarks (driver state) | bundled / CI-fetched | MediaPipe model zoo |
| `eye_state.tflite` | CNN eye-state corroboration | integration point | Train on MRL Eye / NTHU-DDD |
| `gtsrb_sign.tflite` | Sign classifier (43-class GTSRB) | **bundled** | frogermcs/GTSRB-TensorFlow-Lite (gtsrb_model.lite); MobileNet, 224x224 in, [1,43] out |
| `traffic_light.tflite` | Learned traffic-light detector + colour | **bundled** | Syazvinski YOLOv8-nano (red/green/off/yellow), exported to float16 TFLite |
| `ufld_lane.tflite` | Row-anchor lane model | integration point | Convert Ultra-Fast-Lane-Detection (TuSimple/CULane) |

## Hardware acceleration

The object detector enables NNAPI (with CPU fall-back) and the face landmarker
enables the GPU delegate (with CPU fall-back). On devices with an NPU/GPU this
is the single largest performance gain and requires no model change.

## Activation logic

- Object detector: `RoadAnalyzer` loads `yolo26n.tflite` if present, else
  `efficientdet_lite0.tflite`.
- The remaining integration-point models are loaded by their analyser when the
  asset is present; until then the existing classical/baseline method runs, so
  the application is always functional.

## Obtaining yolo26n.tflite directly

The official Ultralytics int8 TFLite export is published as a release asset:

```
curl -fL -o android/app/src/main/assets/yolo26n.tflite \
  https://github.com/ultralytics/yolo-flutter-app/releases/download/v0.3.5/yolo26n_int8.tflite
```

GitHub redirects this to a signed CDN URL; run the command on a machine with
normal internet access (the project CI does this automatically). To produce a
custom/fine-tuned model instead:

```
pip install ultralytics
yolo export model=yolo26n.pt format=tflite int8=True imgsz=640
# produces yolo26n_int8.tflite -> rename to yolo26n.tflite
```

## Training/conversion notes

All models must be quantised (float16 or int8) and exported to TFLite for
on-device use. Keep input sizes modest (e.g. 320–640 px) for real-time phone
performance. See the Detection Algorithms technical note for the rationale and
recommended architectures.
