package com.rfsat.dms.nav

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Geometric camera-AR projection: maps points on the road plane ahead of the
 * vehicle to pixel positions in the front-camera image, so a navigation arrow
 * can be drawn ON the road WITHOUT first detecting the road in the image.
 *
 * The idea: with the phone mounted looking forward, its height above the road
 * and its pitch are (roughly) known — the app already exposes mount-calibration
 * offsets via LaneAnalyzer (horizonOffset ~ pitch, etc.). Assuming locally flat
 * ground, a point a given distance ahead and a given lateral offset sits at a
 * well-defined pixel via a pinhole camera model. That places the arrow correctly
 * on a straight, flat road.
 *
 * ML road/lane segmentation (the next step) REFINES this — it corrects for hills,
 * banking and curves the flat-ground model can't know, and masks out areas that
 * aren't road (so the arrow is never drawn over a car ahead). But the geometry
 * here is the honest foundation and works on straight, level roads today.
 *
 * Camera frame convention: x right, y up, z forward (into the scene). Image
 * coords are normalized 0..1 with (0,0) top-left.
 */
class ArProjection(
    /** Vertical field of view of the camera in degrees (typical phone ~50-65). */
    var vFovDeg: Double = 58.0,
    /** Image aspect ratio (width / height), used to derive horizontal FOV. */
    var aspect: Double = 16.0 / 9.0,
    /** Camera height above the road, metres (dash-mounted phone ~1.2 m). */
    var cameraHeightM: Double = 1.2,
    /** Downward pitch of the camera below horizontal, radians. 0 = level.
     *  Wire this from LaneAnalyzer.horizonOffset (a level camera puts the horizon
     *  at image mid-height; a downward tilt raises the road in the frame). */
    var pitchRad: Double = 0.06
) {
    /**
     * Project a road-plane point that is [ahead] metres in front of the camera
     * and [lateral] metres to the right (negative = left), at road level, to a
     * normalized image coordinate. Returns null if the point is behind the
     * camera or above the horizon (not on the visible road plane).
     */
    fun project(ahead: Double, lateral: Double): Pair<Float, Float>? {
        if (ahead <= 0.1) return null
        // Point in camera-centred world coords (before pitch):
        //   forward = ahead, right = lateral, down = cameraHeight
        // Apply camera pitch (rotation about the x/right axis) so a downward
        // pitch brings the road up into the frame.
        val cp = cos(pitchRad); val sp = sin(pitchRad)
        // world (right, up, forward) with up = -cameraHeight (point is below cam)
        val yUp = -cameraHeightM
        val zF = ahead
        // rotate about right-axis by pitch (down-tilt): 
        val yCam = yUp * cp - zF * sp
        val zCam = yUp * sp + zF * cp
        if (zCam <= 0.1) return null                 // behind image plane
        // pinhole projection
        val fY = 1.0 / tan(Math.toRadians(vFovDeg / 2.0))
        val fX = fY / aspect
        val ndcX = (lateral / zCam) * fX             // -1..1 across width
        val ndcY = (yCam / zCam) * fY                // -1..1 up
        // to normalized image coords (y flips: +up -> smaller row)
        val ix = (ndcX + 1.0) / 2.0
        val iy = (1.0 - (ndcY + 1.0) / 2.0)
        if (ix < -0.5 || ix > 1.5) return null       // far off-screen laterally
        return ix.toFloat() to iy.toFloat()
    }

    /**
     * Build a projected polyline for the road ahead: a series of points along the
     * route direction, so the renderer can draw a "carpet"/arrow that follows the
     * road. [headingOffsetsDeg] lets a turn bend the projected path (positive =
     * bends right). Distances in metres ahead.
     */
    fun projectPath(
        distancesAhead: List<Double>,
        bendDegPerM: Double = 0.0
    ): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        for (d in distancesAhead) {
            // approximate lateral drift for a gentle bend: lateral ~ 0.5*k*d^2
            val k = Math.toRadians(bendDegPerM)
            val lateral = 0.5 * k * d * d
            project(d, lateral)?.let { out.add(it) }
        }
        return out
    }
}
