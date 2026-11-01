package com.example.mconkmap.mock

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock

/**
 * Registers Android's GPS test provider. The app must be selected under
 * Developer options > Select mock location app before this can inject.
 *
 * Note: a GPS test provider replaces the real GPS_PROVIDER while registered.
 * The app cannot simultaneously read genuine GPS fixes from that same provider.
 */
class MockLocationController(private val locationManager: LocationManager) {
    private var registered = false

    @Synchronized
    fun start(): Boolean {
        if (registered) return true
        return try {
            try { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
            locationManager.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false, false, false, false,
                true, true, true,
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            registered = true
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun inject(
        latitude: Double,
        longitude: Double,
        speedKmh: Double,
        bearingDegrees: Double,
        accuracyMeters: Float
    ): Boolean {
        if (!registered && !start()) return false
        return try {
            val location = Location(LocationManager.GPS_PROVIDER).apply {
                this.latitude = latitude
                this.longitude = longitude
                speed = (speedKmh.coerceAtLeast(0.0) / 3.6).toFloat()
                bearing = ((bearingDegrees % 360.0 + 360.0) % 360.0).toFloat()
                accuracy = accuracyMeters.coerceAtLeast(1f)
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        try { locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false) } catch (_: Exception) {}
        try { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        registered = false
    }
}
