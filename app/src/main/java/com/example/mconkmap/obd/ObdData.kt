package com.example.mconkmap.obd

data class ObdData(
    val speedKmh: Double?,
    val connected: Boolean,
    val timestampNanos: Long,
    val message: String
)
