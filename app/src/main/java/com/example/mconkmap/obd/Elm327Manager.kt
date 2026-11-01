package com.example.mconkmap.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ELM327 classic Bluetooth SPP reader.
 * Standard PID 010D returns vehicle speed in km/h (one-byte response).
 * Actual polling rate depends on adapter firmware and response latency.
 */
class Elm327Manager(
    private val adapter: BluetoothAdapter,
    private val onData: (ObdData) -> Unit
) {
    private val running = AtomicBoolean(false)
    @Volatile private var socket: BluetoothSocket? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, pollIntervalMs: Long = 150L) {
        disconnect()
        running.set(true)
        worker = Thread {
            var lastSpeed: Double? = null
            try {
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket = s
                s.connect()
                val input = s.inputStream
                val output = s.outputStream

                listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0").forEach { command ->
                    writeCommand(output, command)
                    readPrompt(input, 2500)
                }

                while (running.get() && !Thread.currentThread().isInterrupted) {
                    val started = SystemClock.elapsedRealtime()
                    writeCommand(output, "010D")
                    val response = readPrompt(input, 1200)
                    val speed = parseSpeed(response)
                    if (speed != null) {
                        lastSpeed = speed
                        onData(ObdData(speed, true, SystemClock.elapsedRealtimeNanos(), "OBD 연결 · 차속 수신"))
                    } else {
                        onData(ObdData(lastSpeed, true, SystemClock.elapsedRealtimeNanos(), "OBD 응답 확인 중"))
                    }
                    val wait = pollIntervalMs - (SystemClock.elapsedRealtime() - started)
                    if (wait > 0) Thread.sleep(wait)
                }
            } catch (e: Exception) {
                onData(ObdData(lastSpeed, false, SystemClock.elapsedRealtimeNanos(),
                    "OBD 연결 끊김: ${e.javaClass.simpleName}"))
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                running.set(false)
            }
        }.apply { name = "ELM327-SPP"; isDaemon = true; start() }
    }

    fun disconnect() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        worker?.interrupt()
        worker = null
        socket = null
    }

    private fun writeCommand(output: java.io.OutputStream, command: String) {
        output.write((command + "\r").toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    @Throws(IOException::class)
    private fun readPrompt(input: java.io.InputStream, timeoutMs: Long): String {
        val result = StringBuilder()
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (input.available() > 0) {
                val value = input.read()
                if (value < 0) break
                if (value.toChar() == '>') break
                result.append(value.toChar())
            } else {
                Thread.sleep(5)
            }
        }
        return result.toString()
    }

    private fun parseSpeed(response: String): Double? {
        val compact = response.uppercase().replace(Regex("[^0-9A-F]"), "")
        val match = Regex("410D([0-9A-F]{2})").find(compact) ?: return null
        return match.groupValues[1].toInt(16).toDouble()
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
