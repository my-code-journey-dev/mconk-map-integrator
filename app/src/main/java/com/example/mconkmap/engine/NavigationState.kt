package com.example.mconkmap.engine

enum class TrackingMode { WAITING_FOR_GPS, GPS, DEAD_RECKONING, RECOVERING }

data class NavigationState(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double,
    val bearingDegrees: Double,
    val accuracyMeters: Float,
    val mode: TrackingMode,
    val calibratedSpeedRatio: Double,
    val deadReckoningDistanceMeters: Double,
    val deadReckoningElapsedSeconds: Double,
    val obdConnected: Boolean
)
