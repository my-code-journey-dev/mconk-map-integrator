package com.example.mconkmap.sensor

import kotlin.math.sqrt

/**
 * Projects the gyro vector onto the gravity axis, so phone mounting tilt is tolerated.
 * Accelerometer low-pass estimates gravity; stopped periods learn gyro bias.
 */
class VehicleHeadingTracker(
    private val gravityAlpha: Float = 0.08f,
    private val biasAlpha: Float = 0.01f
) {
    private val gravity = FloatArray(3)
    private val gyroBias = FloatArray(3)
    private var gravityReady = false
    private var yawRate = 0.0

    @Synchronized
    fun onAccelerometer(x: Float, y: Float, z: Float) {
        if (!gravityReady) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z
            gravityReady = true
        } else {
            gravity[0] += gravityAlpha * (x - gravity[0])
            gravity[1] += gravityAlpha * (y - gravity[1])
            gravity[2] += gravityAlpha * (z - gravity[2])
        }
    }

    @Synchronized
    fun onGyroscope(x: Float, y: Float, z: Float, vehicleStopped: Boolean): Double {
        if (!gravityReady) return yawRate
        val norm = sqrt((gravity[0]*gravity[0] + gravity[1]*gravity[1] + gravity[2]*gravity[2]).toDouble()).toFloat()
        if (norm < 1f) return yawRate
        val gx = gravity[0] / norm
        val gy = gravity[1] / norm
        val gz = gravity[2] / norm
        val raw = x * gx + y * gy + z * gz
        if (vehicleStopped) {
            gyroBias[0] += biasAlpha * (x - gyroBias[0])
            gyroBias[1] += biasAlpha * (y - gyroBias[1])
            gyroBias[2] += biasAlpha * (z - gyroBias[2])
        }
        yawRate = ((x - gyroBias[0]) * gx + (y - gyroBias[1]) * gy + (z - gyroBias[2]) * gz).toDouble()
        return yawRate
    }

    @Synchronized
    fun currentYawRateRadPerSecond(): Double = yawRate
}
