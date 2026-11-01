package com.example.mconkmap.logging

import com.example.mconkmap.engine.DeadReckoningEngine
import com.example.mconkmap.engine.EngineInput
import com.example.mconkmap.engine.GpsFix
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

/**
 * Replays DriveLogger CSV. GPS is deliberately hidden for outageSeconds from the first row.
 * Real GPS coordinates remain available only as ground truth for error calculation.
 */
object ReplayRunner {
    fun run(csv: String, outageSeconds: Double): ReplayResult {
        val rows = csv.lineSequence().drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            val c = line.split(",")
            if (c.size < 15) null else try {
                Row(
                    c[0].toLong(), c[2].toDoubleOrNull(), c[3].toDoubleOrNull(),
                    c[4].toDoubleOrNull(), c[5].toDoubleOrNull(), c[6].toFloatOrNull(),
                    c[7].toDoubleOrNull(), c[8].toBoolean(), c[9].toDoubleOrNull() ?: 0.0,
                    c[10].toDoubleOrNull() ?: 0.0, c[11].toDoubleOrNull() ?: 0.0
                )
            } catch (_: Exception) { null }
        }.toList()
        if (rows.isEmpty()) return ReplayResult(emptyList(), 0.0, 0.0)

        val engine = DeadReckoningEngine()
        val start = rows.first().elapsedNanos
        val points = mutableListOf<ReplayPoint>()
        val errors = mutableListOf<Double>()
        rows.forEach { row ->
            val outage = row.elapsedNanos - start >= 0L &&
                row.elapsedNanos - start <= (outageSeconds.coerceAtLeast(0.0) * 1e9).toLong()
            val gps = if (!outage && row.lat != null && row.lon != null && row.accuracy != null) {
                GpsFix(row.lat, row.lon, row.gpsSpeed ?: 0.0, row.bearing ?: 0.0,
                    row.accuracy, row.elapsedNanos)
            } else null
            val state = engine.update(EngineInput(
                row.elapsedNanos, gps, row.obdSpeed, row.elapsedNanos,
                row.obdConnected, yawRateRadPerSecond = row.yawRate
            ))
            val error = if (outage && row.lat != null && row.lon != null &&
                !state.latitude.isNaN() && !state.longitude.isNaN()) {
                distanceMeters(state.latitude, state.longitude, row.lat, row.lon)
            } else null
            if (error != null) errors.add(error)
            points.add(ReplayPoint(row.elapsedNanos, state.latitude, state.longitude,
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
        val elapsedNanos: Long, val lat: Double?, val lon: Double?,
        val gpsSpeed: Double?, val bearing: Double?, val accuracy: Float?,
        val obdSpeed: Double?, val obdConnected: Boolean, val yawRate: Double,
        val unused1: Double, val unused2: Double
    )
}
