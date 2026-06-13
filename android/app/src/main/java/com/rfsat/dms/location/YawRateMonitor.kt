package com.rfsat.dms.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Yaw-rate (heading-change) source from the phone gyroscope, used as an
 * INDEPENDENT corroborating signal for vision-based lane-crossing events.
 *
 * The phone's mounting orientation is unknown, so rather than assume an axis
 * we take the gyroscope axis with the largest sustained magnitude during
 * motion as the vehicle's yaw axis (auto-detected), low-pass filtered. This
 * is deliberately coarse — it only needs to answer "is the car actually
 * turning right now?" to distinguish a real lane change from paint/shadow
 * noise in the lane detector.
 */
class YawRateMonitor(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile var yawRateDps = 0f; private set
    private val axisEnergy = FloatArray(3)
    private var yawAxis = 2   // default Z

    fun start() { gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        // track which axis carries the most rotational energy = yaw axis
        for (i in 0..2) axisEnergy[i] = 0.95f * axisEnergy[i] + 0.05f * abs(e.values[i])
        yawAxis = axisEnergy.indices.maxByOrNull { axisEnergy[it] } ?: 2
        val raw = Math.toDegrees(e.values[yawAxis].toDouble()).toFloat()
        yawRateDps = 0.8f * yawRateDps + 0.2f * raw      // low-pass
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
}
