package com.example.mconkmap.logging

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

class DriveLogger(context: Context) {
    private val file = File(context.getExternalFilesDir("logs") ?: context.filesDir, "drive-log.csv")
    private var writer: BufferedWriter? = null
    private var lastFlushMs = 0L

    @Synchronized fun start(): File {
        file.parentFile?.mkdirs()
        writer = BufferedWriter(FileWriter(file, false)).also {
            it.write("elapsedNanos,wallTimeMs,gpsLat,gpsLon,gpsSpeedKmh,gpsBearing,gpsAccuracy,obdSpeedKmh,obdConnected,gyroX,gyroY,gyroZ,gravityX,gravityY,gravityZ\n")
            it.flush()
        }
        return file
    }

    @Synchronized fun append(
        elapsedNanos: Long, wallTimeMs: Long,
        gpsLat: Double?, gpsLon: Double?, gpsSpeedKmh: Double?, gpsBearing: Double?, gpsAccuracy: Float?,
        obdSpeedKmh: Double?, obdConnected: Boolean,
        gyro: FloatArray, gravity: FloatArray
    ) {
        val out = writer ?: return
        val values = listOf(
            elapsedNanos.toString(), wallTimeMs.toString(),
            gpsLat.csv(), gpsLon.csv(), gpsSpeedKmh.csv(), gpsBearing.csv(), gpsAccuracy.csv(),
            obdSpeedKmh.csv(), obdConnected.toString(),
            gyro.getOrNull(0).csv(), gyro.getOrNull(1).csv(), gyro.getOrNull(2).csv(),
            gravity.getOrNull(0).csv(), gravity.getOrNull(1).csv(), gravity.getOrNull(2).csv()
        )
        out.write(values.joinToString(","))
        out.newLine()
        if (System.currentTimeMillis() - lastFlushMs >= 1000L) {
            out.flush()
            lastFlushMs = System.currentTimeMillis()
        }
    }

    @Synchronized fun stop() {
        try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        writer = null
    }

    fun file(): File = file

    private fun Any?.csv(): String = this?.toString() ?: ""
}
