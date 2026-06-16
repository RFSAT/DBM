# Model assets (download before building)

face_landmarker.task:
  https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task

efficientdet_lite0.tflite (with metadata, for TFLite Task Library):
  https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float16/latest/efficientdet_lite0.tflite

Place both files in this directory.

yolo26n.tflite (bundled): Ultralytics YOLO26-nano, COCO, float input 640x640, raw [1,84,8400] output decoded by YoloDetector.

gtsrb_sign.tflite (bundled): GTSRB 43-class traffic-sign classifier (MobileNet, input 224x224 float, output [1,43]). Recognition stage of the two-stage sign pipeline.

traffic_light.tflite (bundled): YOLOv8-nano traffic-light detector + colour classifier (Syazvinski), float16, input 640x640, output [1,8,8400], classes red/green/off/yellow. Decoded by TrafficLightDetector with vehicle brake-light rejection.

sign_eu.tflite (bundled): Mapillary-trained EU sign DETECTOR, 27 classes incl. no-left/right/U-turn. YOLO format [1,31,8400], input 640. Decoded by SignDetector; preferred over gtsrb_sign.tflite.
