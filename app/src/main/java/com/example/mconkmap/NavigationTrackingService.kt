package com.example.mconkmap

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.mconkmap.engine.DeadReckoningEngine
import com.example.mconkmap.engine.EngineInput
import com.example.mconkmap.engine.GpsFix
import com.example.mconkmap.engine.TrackingMode
import com.example.mconkmap.logging.DriveLogger
import com.example.mconkmap.mock.MockLocationController
import com.example.mconkmap.obd.Elm327Manager
import com.example.mconkmap.obd.ObdData
import com.example.mconkmap.sensor.VehicleHeadingTracker

/**
 * Foreground orchestration service.
 *
 * GPS_PROVIDER mock replaces the real GPS provider on Android. To detect GPS recovery,
 * this service periodically releases the test provider, listens for a real GPS fix,
 * then re-registers the test provider during the 1.5s engine recovery interpolation.
 * This brief probe interval can momentarily expose the real provider to other apps.
 */
class NavigationTrackingService : Service(), LocationListener, SensorEventListener {
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var mockController: MockLocationController
    private lateinit var logger: DriveLogger
    private val engine = DeadReckoningEngine()
    private val headingTracker = VehicleHeadingTracker()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("navigation_tracking", MODE_PRIVATE) }

    @Volatile private var latestGps: GpsFix? = null
    @Volatile private var latestObd: ObdData? = null
    @Volatile private var obdManager: Elm327Manager? = null
    @Volatile private var mockActive = false
    @Volatile private var probing = false
    @Volatile private var probeStartedNanos = 0L
    @Volatile private var lastProbeNanos = 0L
    @Volatile private var lastStateText = "시작 준비"
    private val gyro = FloatArray(3)
    private val gravity = FloatArray(3)

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtimeNanos()
            val obd = latestObd
            val state = engine.update(EngineInput(
                nowElapsedRealtimeNanos = now,
                gpsFix = latestGps,
                obdSpeedKmh = obd?.speedKmh,
                obdLastUpdateNanos = obd?.timestampNanos ?: 0L,
                obdConnected = obd?.connected == true,
                yawRateRadPerSecond = headingTracker.currentYawRateRadPerSecond()
            ))

            if (state.mode == TrackingMode.DEAD_RECKONING) {
                if (mockActive && !probing && now - lastProbeNanos >= GPS_PROBE_INTERVAL_NANOS) {
                    beginGpsProbe(now)
                }
                if (probing && now - probeStartedNanos >= GPS_PROBE_TIMEOUT_NANOS) {
                    probing = false
                    if (mockController.start()) mockActive = true
                    lastProbeNanos = now
                    lastStateText = "GPS 복귀 신호 없음 · 추측항법 계속"
                } else if (!mockActive && !probing) {
                    mockActive = mockController.start()
                    lastProbeNanos = now
                    if (!mockActive) lastStateText = "모의 위치 등록 실패 · 개발자 옵션 설정 확인"
                }
                if (mockActive) inject(state)
            } else if (state.mode == TrackingMode.RECOVERING) {
                probing = false
                if (!mockActive) mockActive = mockController.start()
                if (mockActive) inject(state)
                lastProbeNanos = now
            } else if (state.mode == TrackingMode.GPS) {
                if (mockActive) {
                    mockController.stop()
                    mockActive = false
                }
                probing = false
                lastStateText = "GPS 정상 · 실제 위치 사용"
            } else {
                lastStateText = "GPS 위치 대기 중"
            }

            val obdStatus = when {
                obd == null -> "OBD 미연결"
                obd.connected && now - obd.timestampNanos <= 2_000_000_000L -> "OBD 연결"
                else -> "OBD 연결 끊김 · 마지막 차속 유지"
            }
            val modeText = when (state.mode) {
                TrackingMode.GPS -> "GPS"
                TrackingMode.DEAD_RECKONING -> "추측항법"
                TrackingMode.RECOVERING -> "GPS 복귀 보간"
                TrackingMode.WAITING_FOR_GPS -> "GPS 대기"
            }
            val display = "$lastStateText\n모드: $modeText · $obdStatus\n차속: %.1f km/h · 방향: %.1f°\n보정계수: %.3f · 추측 이동: %.1f m · 경과: %.1f초\n정확도 추정: %.1f m".format(
                state.speedKmh, state.bearingDegrees, state.calibratedSpeedRatio,
                state.deadReckoningDistanceMeters, state.deadReckoningElapsedSeconds, state.accuracyMeters
            )
            prefs.edit()
                .putString("status", display)
                .putString("mode", modeText)
                .putFloat("speed", state.speedKmh.toFloat())
                .putFloat("heading", state.bearingDegrees.toFloat())
                .putFloat("ratio", state.calibratedSpeedRatio.toFloat())
                .putFloat("distance", state.deadReckoningDistanceMeters.toFloat())
                .putFloat("accuracy", state.accuracyMeters)
                .putBoolean("mock_active", mockActive)
                .apply()
            updateNotification(display)
            logger.append(
                now, System.currentTimeMillis(),
                latestGps?.latitude, latestGps?.longitude, latestGps?.speedKmh,
                latestGps?.bearingDegrees, latestGps?.accuracyMeters,
                obd?.speedKmh, obd?.connected == true, gyro, gravity
            )
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        mockController = MockLocationController(locationManager)
        logger = DriveLogger(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("위치·차량 데이터 초기화 중"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putString("status", "정확한 위치 권한이 필요합니다. 앱 화면에서 권한을 허용하세요.").apply()
            stopSelf()
            return START_NOT_STICKY
        }
        startGps()
        startSensors()
        connectObd()
        val logFile = logger.start()
        prefs.edit().putString("log_path", logFile.absolutePath).apply()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_STICKY
    }

    private fun startGps() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, this)
            }
        } catch (_: SecurityException) {
            prefs.edit().putString("status", "위치 권한이 없어 GPS를 시작하지 못했습니다.").apply()
        }
    }

    private fun startSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    @Suppress("MissingPermission")
    private fun connectObd() {
        val address = prefs.getString("obd_mac", null) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        try {
            val device = adapter.getRemoteDevice(address)
            obdManager = Elm327Manager(adapter) { data ->
                latestObd = data
                prefs.edit().putString("obd_status", data.message).apply()
            }.also { it.connect(device, 150L) }
        } catch (e: Exception) {
            latestObd = ObdData(null, false, SystemClock.elapsedRealtimeNanos(), "OBD 연결 실패: ${e.javaClass.simpleName}")
        }
    }

    override fun onLocationChanged(location: Location) {
        val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider
        if (isMock) return
        if (!location.hasAccuracy() || location.accuracy > 20f) return
        val now = SystemClock.elapsedRealtimeNanos()
        val fix = GpsFix(
            location.latitude, location.longitude,
            if (location.hasSpeed()) location.speed * 3.6 else 0.0,
            if (location.hasBearing()) location.bearing.toDouble() else 0.0,
            location.accuracy, location.elapsedRealtimeNanos.takeIf { it > 0L } ?: now,
            location.hasBearing()
        )
        latestGps = fix
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                for (i in 0..2) gravity[i] = event.values[i]
                headingTracker.onAccelerometer(event.values[0], event.values[1], event.values[2])
            }
            Sensor.TYPE_GYROSCOPE -> {
                for (i in 0..2) gyro[i] = event.values[i]
                val stopped = latestObd?.connected == true && (latestObd?.speedKmh ?: 999.0) < 1.5
                headingTracker.onGyroscope(event.values[0], event.values[1], event.values[2], stopped)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun beginGpsProbe(now: Long) {
        mockController.stop()
        mockActive = false
        probing = true
        probeStartedNanos = now
        lastProbeNanos = now
        lastStateText = "실제 GPS 복귀 확인 중"
    }

    private fun inject(state: com.example.mconkmap.engine.NavigationState) {
        val ok = mockController.inject(state.latitude, state.longitude, state.speedKmh,
            state.bearingDegrees, state.accuracyMeters)
        if (!ok) lastStateText = "모의 위치 주입 실패 · 개발자 옵션에서 이 앱 선택 필요"
    }

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("차량 추측항법 실행 중")
            .setContentText(text.lineSequence().firstOrNull() ?: "실행 중")
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true).build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "차량 위치 추측항법", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onProviderDisabled(provider: String) {}
    override fun onProviderEnabled(provider: String) {}
    @Deprecated("Deprecated in Android API")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
        sensorManager.unregisterListener(this)
        obdManager?.disconnect()
        mockController.stop()
        logger.stop()
        prefs.edit().putString("status", "백그라운드 추적 중지").putBoolean("mock_active", false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.example.mconkmap.STOP_NAVIGATION_TRACKING"
        private const val CHANNEL_ID = "navigation_tracking"
        private const val NOTIFICATION_ID = 7410
        private const val TICK_INTERVAL_MS = 200L
        private const val GPS_PROBE_INTERVAL_NANOS = 8_000_000_000L
        private const val GPS_PROBE_TIMEOUT_NANOS = 2_500_000_000L
    }
}
