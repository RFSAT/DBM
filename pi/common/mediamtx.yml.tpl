# DMS node — mediamtx using the built-in Raspberry Pi camera source
# (hardware H.264 encode, no rpicam-vid pipe needed).
logLevel: info
rtspAddress: :8554
rtspTransports: [tcp]          # TCP = robust over WiFi; phone requests TCP anyway
rtmp: no
hls: no
webrtc: no
srt: no
api: yes                        # status_server.py queries http://127.0.0.1:9997
apiAddress: 127.0.0.1:9997

paths:
  "@ROLE@":
    source: rpiCamera
    rpiCameraWidth: 640
    rpiCameraHeight: 480
    rpiCameraFPS: 15
    rpiCameraBitrate: 1800000
    rpiCameraIDRPeriod: 15      # 1 keyframe/s -> fast stream join/recovery
    rpiCameraProfile: baseline
    rpiCameraLevel: "4.0"
    # For the driver camera at night consider an IR camera module and:
    # rpiCameraExposure: sport
