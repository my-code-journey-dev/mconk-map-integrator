package com.example.mconkmap

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import kotlin.math.cos
import kotlin.math.sin

/**
 * Foreground location service for tunnel dead reckoning.
 * Uses the last trusted GPS fix, speed and course for short GPS outages.
 * Two accurate fixes are required before accepting a GPS reacquisition.
 */
class TunnelTrackingService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private var anchor: Location? = null
    private var anchorElapsedMs = 0L
    private var lastSpeedMps = 0.0
    private var lastBearing = 0.0
    private var goodFixCount = 0
    private var recovering = false
    private var lastStatus = "GPS 대기 중"
    private val prefs by lazy { getSharedPreferences("tunnel_tracking", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            val base = anchor
            if (base != null) {
                val age = (SystemClock.elapsedRealtime() - anchorElapsedMs) / 1000.0
                if (age > 2.5 && !recovering) {
                    recovering = true
                    goodFixCount = 0
                    publish("GPS 수신 끊김 · 추측항법으로 이동 중")
                } else if (recovering) {
                    publish("GPS 끊김 · 추측 위치 계산 중")
                }
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        createChannel()
        startForeground(NOTIFICATION_ID, notification("GPS 위치 확인 중"))
        handler.postDelayed(watchdog, 1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startLocationUpdates()
        return START_STICKY
    }

    private fun startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            publish("위치 권한 필요")
            stopSelf()
            return
        }
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
                publish("GPS 정상 수신 대기 · 백그라운드 실행 중")
            } else {
                publish("GPS 꺼짐 · 휴대폰 위치 설정 확인")
            }
        } catch (e: SecurityException) {
            publish("위치 권한 오류")
            stopSelf()
        } catch (e: Exception) {
            publish("GPS 시작 실패: ${e.javaClass.simpleName}")
            stopSelf()
        }
    }

    override fun onLocationChanged(location: Location) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && location.isMock) return
        val now = SystemClock.elapsedRealtime()
        val accuracy = if (location.hasAccuracy()) location.accuracy else 999f
        if (accuracy > 35f) {
            publish("GPS 정확도 낮음 (${accuracy.toInt()}m) · 추측항법 유지")
            return
        }

        val oldAnchor = anchor
        if (oldAnchor == null) {
            acceptGps(location, now, "GPS 기준 위치 설정")
            return
        }

        // During signal recovery, require two consecutive accurate fixes.
        if (recovering) {
            goodFixCount++
            if (goodFixCount < 2) {
                publish("GPS 재수신 확인 중 (${goodFixCount}/2) · 추측항법 유지")
                return
            }
        }

        val predicted = predict(now)
        val gapMeters = FloatArray(1)
        Location.distanceBetween(predicted.latitude, predicted.longitude,
            location.latitude, location.longitude, gapMeters)
        val difference = gapMeters[0]

        if (difference <= REJOIN_TOLERANCE_METERS) {
            acceptGps(location, now, "GPS 복귀 · 추측항법과 일치 (${difference.toInt()}m)")
        } else {
            // A stable GPS fix is authoritative: reset the dead-reckoning origin here.
            acceptGps(location, now, "GPS 복귀 · 오차 ${difference.toInt()}m → GPS 위치로 기준 재설정")
        }
    }

    private fun acceptGps(location: Location, now: Long, message: String) {
        anchor = Location(location)
        anchorElapsedMs = now
        if (location.hasSpeed() && location.speed >= 0f) lastSpeedMps = location.speed.toDouble()
        if (location.hasBearing() && (location.speed > 1.0f || lastBearing == 0.0)) lastBearing = location.bearing.toDouble()
        goodFixCount = 0
        recovering = false
        publish(message)
    }

    private fun predict(now: Long): Location {
        val base = anchor ?: return Location("gps")
        val ageSeconds = ((now - anchorElapsedMs).coerceAtLeast(0L) / 1000.0).coerceAtMost(MAX_DR_SECONDS)
        val distance = lastSpeedMps * ageSeconds
        val result = Location(base)
        val earth = 6371000.0
        val angular = distance / earth
        val bearing = Math.toRadians(lastBearing)
        val lat1 = Math.toRadians(base.latitude)
        val lon1 = Math.toRadians(base.longitude)
        val lat2 = Math.asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
        val lon2 = lon1 + Math.atan2(sin(bearing) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sin(lat2))
        result.latitude = Math.toDegrees(lat2)
        result.longitude = Math.toDegrees(lon2)
        return result
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            recovering = true
            goodFixCount = 0
            publish("GPS 끊김 · 터널 추측항법 대기")
        }
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            recovering = true
            goodFixCount = 0
            publish("GPS 재연결 중 · 위치 안정성 확인")
        }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private fun publish(message: String) {
        lastStatus = message
        val base = anchor
        val now = SystemClock.elapsedRealtime()
        val age = if (anchor != null) (now - anchorElapsedMs) / 1000 else -1
        val estimate = if (base != null) predict(now) else null
        val text = if (estimate != null) {
            "$message\n추정 좌표: %.6f, %.6f · 마지막 GPS 이후 %ds".format(estimate.latitude, estimate.longitude, age)
        } else message
        prefs.edit().putString("status", text).putLong("updated", System.currentTimeMillis()).apply()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("MCon K 터널 추측항법 실행 중")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "터널 추측항법", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun stopTracking() {
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
        prefs.edit().putString("status", "백그라운드 추적 중지").apply()
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        stopTracking()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.example.mconkmap.STOP_TUNNEL_TRACKING"
        private const val CHANNEL_ID = "tunnel_tracking"
        private const val NOTIFICATION_ID = 7401
        private const val REJOIN_TOLERANCE_METERS = 30f
        private const val MAX_DR_SECONDS = 45.0
    }
}
