package com.rfsat.dms

/**
 * Camera roles. Phase 1 uses the phone's own cameras (DRIVER = front/selfie
 * camera watching the cabin, FRONT = rear camera watching the road).
 * Phase 2 adds Raspberry Pi RTSP nodes — URLs retained for that mode.
 */
enum class CameraRole(val rtspUrl: String, val statusUrl: String, val label: String) {
    DRIVER("rtsp://192.168.50.1:8554/driver",  "http://192.168.50.1:8080/status",  "Driver / Interior"),
    FRONT ("rtsp://192.168.50.11:8554/front",  "http://192.168.50.11:8080/status", "Road Ahead"),
    REAR  ("rtsp://192.168.50.12:8554/rear",   "http://192.168.50.12:8080/status", "Rear");
}

enum class RiskType(val description: String, val scorePenalty: Int) {
    // Driver-state risks
    MICROSLEEP("Eyes closed — possible microsleep", 15),
    EYES_OFF_ROAD("Driver not looking ahead", 8),
    NO_MIRROR_CHECK("No mirror check for extended period", 3),
    PHONE_USE("Mobile phone use detected", 12),
    HANDS_OFF_WHEEL("Hands off steering wheel", 10),
    NO_SEATBELT("Seatbelt not fastened", 10),
    // Road / collision risks
    FRONT_COLLISION_RISK("Fast-approaching object ahead", 10),
    UNSAFE_FOLLOWING_DISTANCE("Following closer than stopping distance", 10),
    REAR_COLLISION_RISK("Fast-approaching vehicle behind / tailgating", 6),
    VULNERABLE_ROAD_USER("Pedestrian / cyclist / scooter in risk zone", 8),
    // Road-regulation compliance
    SPEEDING("Exceeding posted speed limit", 12),
    SOLID_LINE_CROSSING("Crossing a solid lane line", 10),
    DOUBLE_LINE_CROSSING("Crossing a double solid line", 15),
    HARD_SHOULDER_DRIVING("Driving on the hard shoulder", 12),
    ILLEGAL_TURN("Possible illegal turn manoeuvre", 12),
    LANE_DRIFT("Drifting out of lane without indicating", 5),
    // System
    NODE_OFFLINE("Camera offline", 0),
}

enum class Severity { INFO, WARNING, CRITICAL }

/** Detection box in normalized [0,1] frame coords for overlay drawing. */
data class Detection(
    val labelText: String,
    val score: Float,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val risky: Boolean = false,
)

/** A detected lane line for overlay: bottom/top x positions, normalized. */
data class LaneLine(
    val xBottom: Float, val xTop: Float,
    val kind: Kind,
) { enum class Kind { DASHED, SOLID, DOUBLE_SOLID } }

data class AnalysisResult(
    val detections: List<Detection> = emptyList(),
    val laneLines: List<LaneLine> = emptyList(),
    val events: List<RiskEventCandidate> = emptyList(),
    /** Speed limit (km/h) read from a sign in this frame, if any. */
    val speedLimitSeen: Int? = null,
)

data class RiskEventCandidate(
    val type: RiskType,
    val severity: Severity,
    val confidence: Float,
    val detail: String = "",
)

enum class SpeedSource { GPS, VISUAL, NONE }

/** Live driver-compliance scoring state, 0..100. */
data class ComplianceState(
    val score: Int = 100,
    val activeSpeedLimitKmh: Int? = null,
    val currentSpeedKmh: Int = 0,
    val speedSource: SpeedSource = SpeedSource.NONE,
    val tripEvents: Int = 0,
)
