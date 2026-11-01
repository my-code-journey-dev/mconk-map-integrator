package com.example.mconkmap.engine

import kotlin.math.*

data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double,
    val bearingDegrees: Double,
    val accuracyMeters: Float,
    val elapsedRealtimeNanos: Long,
    val hasBearing: Boolean = true
)

data class EngineInput(
    val nowElapsedRealtimeNanos: Long,
    val gpsFix: GpsFix? = null,
    val obdSpeedKmh: Double? = null,
    val obdLastUpdateNanos: Long = 0L,
    val obdConnected: Boolean = false,
    val yawRateRadPerSecond: Double = 0.0
)


/**
 * Platform-independent vehicle dead-reckoning engine.
 * All timestamps use elapsedRealtimeNanos to avoid wall-clock changes.
 */
class DeadReckoningEngine(
    private val gpsMaxAccuracyMeters: Float = 20f,
    private val gpsMaxAgeNanos: Long = 2_000_000_000L,
    private val recoveryDurationNanos: Long = 1_500_000_000L,
    private val ratioAlpha: Double = 0.05
) {
    private var mode = TrackingMode.WAITING_FOR_GPS
    private var latitude = Double.NaN
    private var longitude = Double.NaN
    private var heading = 0.0
    private var speedKmh = 0.0
    private var lastUpdateNanos = 0L
    private var lastGoodGpsNanos = 0L
    private var lastRatioGpsNanos = 0L
    private var lastObdSpeedKmh = 0.0
    private var lastObdNanos = 0L
    private var speedRatio = 1.0
    private var drDistance = 0.0
    private var drStartNanos = 0L
    private var recoveryStartNanos = 0L
    private var recoveryFromLat = 0.0
    private var recoveryFromLon = 0.0
    private var recoveryToLat = 0.0
    private var recoveryToLon = 0.0
    private var recoveryToHeading = 0.0
    private var recoveryToSpeed = 0.0

    @Synchronized
    fun update(input: EngineInput): NavigationState {
        val now = input.nowElapsedRealtimeNanos
        val dt = if (lastUpdateNanos == 0L) 0.0 else
            ((now - lastUpdateNanos).coerceAtLeast(0L) / 1e9).coerceAtMost(0.5)
        lastUpdateNanos = now

        val obd = input.obdSpeedKmh
        if (obd != null && obd.isFinite() && obd >= 0.0) {
            lastObdSpeedKmh = obd
            lastObdNanos = if (input.obdLastUpdateNanos > 0L) input.obdLastUpdateNanos else now
        }

        val gps = input.gpsFix
        val gpsFresh = gps != null &&
            gps.accuracyMeters <= gpsMaxAccuracyMeters &&
            gps.accuracyMeters >= 0f &&
            now >= gps.elapsedRealtimeNanos &&
            now - gps.elapsedRealtimeNanos <= gpsMaxAgeNanos

        if (gpsFresh && gps != null) {
            processGoodGps(gps, input, now)
        } else {
            processDeadReckoning(input, now, dt)
        }

        val elapsed = if (drStartNanos == 0L) 0.0 else
            ((now - drStartNanos).coerceAtLeast(0L) / 1e9)
        val accuracy = when (mode) {
            TrackingMode.GPS -> gps?.accuracyMeters ?: gpsMaxAccuracyMeters
            TrackingMode.RECOVERING -> max(gpsMaxAccuracyMeters, 8f)
            TrackingMode.DEAD_RECKONING -> (8.0 + elapsed * 1.8 + drDistance * 0.025).coerceAtMost(200f.toDouble()).toFloat()
            TrackingMode.WAITING_FOR_GPS -> 100f
        }
        return NavigationState(
            latitude, longitude, speedKmh, normalize(heading), accuracy,
            mode, speedRatio, drDistance, elapsed, input.obdConnected &&
                (now - lastObdNanos).coerceAtMost(Long.MAX_VALUE) <= 2_000_000_000L
        )
    }

    private fun processGoodGps(gps: GpsFix, input: EngineInput, now: Long) {
        val hadDeadReckoning = mode == TrackingMode.DEAD_RECKONING
        if (hadDeadReckoning) {
            recoveryFromLat = latitude
            recoveryFromLon = longitude
            recoveryToLat = gps.latitude
            recoveryToLon = gps.longitude
            recoveryToHeading = if (gps.hasBearing && (input.obdSpeedKmh ?: gps.speedKmh) >= 10.0) gps.bearingDegrees else heading
            recoveryToSpeed = gps.speedKmh
            recoveryStartNanos = now
            mode = TrackingMode.RECOVERING
        }

        if (mode == TrackingMode.RECOVERING) {
            val t = ((now - recoveryStartNanos).toDouble() / recoveryDurationNanos).coerceIn(0.0, 1.0)
            latitude = interpolateLatitude(recoveryFromLat, recoveryToLat, t)
            longitude = interpolateLongitude(recoveryFromLat, recoveryFromLon, recoveryToLat, recoveryToLon, t)
            heading = interpolateAngle(heading, recoveryToHeading, t)
            speedKmh += (recoveryToSpeed - speedKmh) * t
            if (t >= 1.0) {
                latitude = gps.latitude
                longitude = gps.longitude
                if (gps.hasBearing && (input.obdSpeedKmh ?: gps.speedKmh) >= 10.0) heading = gps.bearingDegrees
                speedKmh = gps.speedKmh
                mode = TrackingMode.GPS
                drDistance = 0.0
                drStartNanos = 0L
            }
        } else {
            latitude = gps.latitude
            longitude = gps.longitude
            speedKmh = gps.speedKmh
            if (gps.speedKmh >= 10.0) heading = gps.bearingDegrees
            mode = TrackingMode.GPS
        }

        if (gps.elapsedRealtimeNanos != lastRatioGpsNanos && input.obdConnected && input.obdSpeedKmh != null &&
            input.obdSpeedKmh >= 10.0 && gps.speedKmh >= 5.0 &&
            gps.accuracyMeters <= gpsMaxAccuracyMeters) {
            val measuredRatio = (gps.speedKmh / input.obdSpeedKmh).coerceIn(0.7, 1.3)
            speedRatio = (speedRatio * (1.0 - ratioAlpha) + measuredRatio * ratioAlpha)
                .coerceIn(0.7, 1.3)
            lastRatioGpsNanos = gps.elapsedRealtimeNanos
        }
        lastGoodGpsNanos = gps.elapsedRealtimeNanos
    }

    private fun processDeadReckoning(input: EngineInput, now: Long, dt: Double) {
        if (latitude.isNaN() || longitude.isNaN()) {
            mode = TrackingMode.WAITING_FOR_GPS
            return
        }
        if (mode == TrackingMode.GPS || mode == TrackingMode.WAITING_FOR_GPS) {
            mode = TrackingMode.DEAD_RECKONING
            drStartNanos = now
            drDistance = 0.0
        }
        if (mode == TrackingMode.RECOVERING) {
            mode = TrackingMode.DEAD_RECKONING
            drStartNanos = now
        }

        heading = normalize(heading + Math.toDegrees(input.yawRateRadPerSecond * dt))
        val obdAge = if (lastObdNanos == 0L) Long.MAX_VALUE else now - lastObdNanos
        // Retain the last measured vehicle speed when the adapter drops out; UI reports stale/disconnected.
        val usableSpeed = if (obdAge <= 5_000_000_000L) lastObdSpeedKmh else 0.0
        speedKmh = usableSpeed * speedRatio
        val distance = speedKmh / 3.6 * dt
        moveLocal(distance, heading)
        drDistance += distance
    }

    private fun moveLocal(distanceMeters: Double, bearingDegrees: Double) {
        val earthRadius = 6_371_000.0
        val north = distanceMeters * cos(Math.toRadians(bearingDegrees))
        val east = distanceMeters * sin(Math.toRadians(bearingDegrees))
        latitude += Math.toDegrees(north / earthRadius)
        val cosLat = cos(Math.toRadians(latitude)).coerceAtLeast(1e-6)
        longitude += Math.toDegrees(east / (earthRadius * cosLat))
    }

    private fun interpolateLatitude(a: Double, b: Double, t: Double) = a + (b - a) * t

    private fun interpolateLongitude(aLat: Double, aLon: Double, bLat: Double, bLon: Double, t: Double): Double {
        val dLon = ((bLon - aLon + 540.0) % 360.0) - 180.0
        return aLon + dLon * t
    }

    private fun interpolateAngle(a: Double, b: Double, t: Double): Double {
        val delta = ((b - a + 540.0) % 360.0) - 180.0
        return normalize(a + delta * t)
    }

    private fun normalize(value: Double): Double = (value % 360.0 + 360.0) % 360.0
}
