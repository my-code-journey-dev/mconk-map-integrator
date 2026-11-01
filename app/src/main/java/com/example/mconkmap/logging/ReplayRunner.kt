package com.example.mconkmap.logging

import com.example.mconkmap.engine.DeadReckoningEngine
import com.example.mconkmap.engine.EngineInput
import com.example.mconkmap.engine.GpsFix
import com.example.mconkmap.sensor.VehicleHeadingTracker
import kotlin.math.*

data class ReplayPoint(
    val elapsedNanos: Long,
    val estimatedLatitude: Double,
    val estimatedLongitude: Double,
    val actualLatitude: Double?,
    val actualLongitude: Double?,
    val errorMeters: Double?
)

data class ReplayResult(
    val points: List<ReplayPoint>,
    val meanErrorMeters: Double,
    val maxErrorMeters: Double
)

/** Replay logger CSV while hiding GPS for a selected interval after the initial GPS lock. */
object ReplayRunner {
    fun run(csv: String, outageSeconds: Double): ReplayResult {
        val rows = csv.lineSequence().drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            val c = line.split(",")
            if (c.size < 15) null else try {
                Row(
                    elapsed = c[0].toLong(),
                    lat = c[2].toDoubleOrNull(), lon = c[3].toDoubleOrNull(),
                    gpsSpeed = c[4].toDoubleOrNull(), bearing = c[5].toDoubleOrNull(),
                    accuracy = c[6].toFloatOrNull(), obdSpeed = c[7].toDoubleOrNull(),
                    obdConnected = c[8].toBoolean(),
                    gyro = floatArrayOf(c[9].toFloatOrNull() ?: 0f, c[10].toFloatOrNull() ?: 0f, c[11].toFloatOrNull() ?: 0f),
                    gravity = floatArrayOf(c[12].toFloatOrNull() ?: 0f, c[13].toFloatOrNull() ?: 0f, c[14].toFloatOrNull() ?: 0f)
                )
            } catch (_: Exception) { null }
        }.toList()
        if (rows.isEmpty()) return ReplayResult(emptyList(), 0.0, 0.0)

        val engine = DeadReckoningEngine()
        val heading = VehicleHeadingTracker()
        val start = rows.first().elapsed
        val outageStart = 1_000_000_000L // retain the first second to establish a GPS origin
        val outageEnd = outageStart + (outageSeconds.coerceAtLeast(0.0) * 1e9).toLong()
        val points = mutableListOf<ReplayPoint>()
        val errors = mutableListOf<Double>()

        rows.forEach { row ->
            heading.onAccelerometer(row.gravity[0], row.gravity[1], row.gravity[2])
            val yawRate = heading.onGyroscope(
                row.gyro[0], row.gyro[1], row.gyro[2],
                row.obdConnected && (row.obdSpeed ?: 999.0) < 1.5
            )
            val elapsed = row.elapsed - start
            val outage = elapsed in outageStart..outageEnd
            val gps = if (!outage && row.lat != null && row.lon != null && row.accuracy != null) {
                GpsFix(row.lat, row.lon, row.gpsSpeed ?: 0.0, row.bearing ?: 0.0,
                    row.accuracy, row.elapsed)
            } else null
            val state = engine.update(EngineInput(
                row.elapsed, gps, row.obdSpeed, row.elapsed, row.obdConnected, yawRate
            ))
            val error = if (outage && row.lat != null && row.lon != null &&
                !state.latitude.isNaN() && !state.longitude.isNaN()) {
                distanceMeters(state.latitude, state.longitude, row.lat, row.lon)
            } else null
            if (error != null) errors.add(error)
            points.add(ReplayPoint(row.elapsed, state.latitude, state.longitude,
                row.lat, row.lon, error))
        }
        return ReplayResult(points, if (errors.isEmpty()) 0.0 else errors.average(),
            errors.maxOrNull() ?: 0.0)
    }

    private fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val x = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) *
            cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(x.coerceIn(0.0, 1.0)))
    }

    private data class Row(
        val elapsed: Long, val lat: Double?, val lon: Double?,
        val gpsSpeed: Double?, val bearing: Double?, val accuracy: Float?,
        val obdSpeed: Double?, val obdConnected: Boolean,
        val gyro: FloatArray, val gravity: FloatArray
    )
}
