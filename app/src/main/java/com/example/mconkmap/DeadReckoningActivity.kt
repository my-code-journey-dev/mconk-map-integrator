package com.example.mconkmap

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.cos
import kotlin.math.sin

/**
 * Prototype only: short-gap constant-speed extrapolation from the last valid GPS fix.
 * It does not replace the navigation app's location and is not a certified navigation aid.
 */
class DeadReckoningActivity : Activity(), LocationListener, SensorEventListener {
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var status: TextView
    private lateinit var gpsText: TextView
    private lateinit var estimateText: TextView
    private lateinit var confidenceText: TextView
    private lateinit var headingText: TextView
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastSpeedMps = 0.0
    private var lastBearing = 0.0
    private var lastFixElapsedMs = 0L
    private var sensorHeading = Double.NaN
    private var calibratedHeadingOffset = 0.0
    private var hasGpsFix = false
    private var isTracking = false
    private val bg = Color.rgb(17, 24, 39)
    private val panel = Color.rgb(31, 41, 55)
    private val fg = Color.rgb(243, 244, 246)
    private val muted = Color.rgb(156, 163, 175)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        buildUi()
        updateDisplay()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            setBackgroundColor(bg)
        }
        val title = TextView(this).apply {
            text = "터널 추측항법 테스트"
            textSize = 24f
            setTextColor(fg)
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title)
        val note = TextView(this).apply {
            text = "마지막 GPS 위치에서 속도·방향을 이용해 짧은 구간의 예상 좌표를 계산합니다. 지도 앱/HUD 위치를 직접 변경하지는 않습니다."
            textSize = 14f
            setTextColor(muted)
            setPadding(0, 8, 0, 14)
        }
        root.addView(note)

        status = makeText("상태: 시작 전", 17f, fg)
        gpsText = makeText("GPS: 대기 중", 16f, fg)
        estimateText = makeText("추정 위치: 대기 중", 20f, fg)
        confidenceText = makeText("신뢰도: 아직 GPS를 수신하지 못했습니다.", 15f, muted)
        headingText = makeText("휴대폰 방향 센서: 대기 중", 15f, muted)
        listOf(status, gpsText, estimateText, confidenceText, headingText).forEach {
            it.setPadding(12, 12, 12, 12)
            it.setBackgroundColor(panel)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 5, 0, 5)
            root.addView(it, lp)
        }

        val calibrateButton = Button(this).apply {
            text = "현재 휴대폰 방향을 진행 방향으로 보정"
            isAllCaps = false
            setOnClickListener {
                if (!sensorHeading.isNaN()) {
                    calibratedHeadingOffset = normalizeAngle(lastBearing - sensorHeading)
                    status.text = "상태: 방향 보정값 저장됨 (GPS 진행방향 기준)"
                    updateDisplay()
                } else {
                    status.text = "상태: 방향 센서값을 아직 받지 못했습니다."
                }
            }
        }
        root.addView(calibrateButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 54))

        val start = Button(this).apply {
            text = "추측항법 시작"
            isAllCaps = false
            setOnClickListener { startTracking() }
        }
        root.addView(start, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 54))
        val stop = Button(this).apply {
            text = "중지"
            isAllCaps = false
            setOnClickListener { stopTracking(); status.text = "상태: 중지됨" }
        }
        root.addView(stop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48))
        val back = Button(this).apply {
            text = "뒤로"
            isAllCaps = false
            setOnClickListener { finish() }
        }
        root.addView(back, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48))
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun makeText(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun startTracking() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
            return
        }
        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var requested = false
            providers.forEach { provider ->
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 1000L, 0f, this)
                    requested = true
                }
            }
            val rotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (rotation != null) sensorManager.registerListener(this, rotation, SensorManager.SENSOR_DELAY_UI)
            isTracking = requested
            status.text = if (requested) "상태: 위치 수신 중 · 차량 주행 전 테스트 금지" else "상태: 위치 제공자를 사용할 수 없습니다."
            updateDisplay()
        } catch (_: SecurityException) {
            status.text = "상태: 위치 권한이 필요합니다."
        } catch (e: Exception) {
            status.text = "상태: 시작 실패 (" + e.javaClass.simpleName + ")"
        }
    }

    private fun stopTracking() {
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
        sensorManager.unregisterListener(this)
        isTracking = false
    }

    override fun onLocationChanged(location: Location) {
        if (location.hasAccuracy() && location.accuracy > 80f) {
            status.text = "상태: GPS 정확도가 낮습니다 (" + location.accuracy.toInt() + "m)"
            updateDisplay()
            return
        }
        lastLat = location.latitude
        lastLon = location.longitude
        if (location.hasSpeed() && location.speed >= 0.0f) lastSpeedMps = location.speed.toDouble()
        if (location.hasBearing() && location.speed > 1.5f) lastBearing = location.bearing.toDouble()
        lastFixElapsedMs = SystemClock.elapsedRealtime()
        hasGpsFix = true
        status.text = "상태: GPS 정상 · 정확도 " + (if (location.hasAccuracy()) location.accuracy.toInt().toString() else "미상") + "m"
        updateDisplay()
    }

    @Deprecated("Deprecated in Android API")
    override fun onStatusChanged(provider: String?, statusCode: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) { status.text = "상태: 위치 제공자 켜짐 (" + provider + ")" }
    override fun onProviderDisabled(provider: String) { status.text = "상태: 위치 제공자 꺼짐 (" + provider + ")" }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            sensorHeading = normalizeAngle(Math.toDegrees(orientation[0].toDouble()))
            headingText.text = "휴대폰 방향 센서: " + sensorHeading.toInt() + "° (보정 후 " +
                normalizeAngle(sensorHeading + calibratedHeadingOffset).toInt() + "°)"
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateDisplay() {
        if (!hasGpsFix) {
            gpsText.text = "GPS: 아직 유효한 위치가 없습니다."
            estimateText.text = "추정 위치: GPS 수신 대기"
            confidenceText.text = "신뢰도: GPS 위치를 한 번 이상 받아야 계산할 수 있습니다."
            return
        }
        val ageSeconds = ((SystemClock.elapsedRealtime() - lastFixElapsedMs).coerceAtLeast(0L)) / 1000.0
        val bearing = if (!sensorHeading.isNaN()) normalizeAngle(sensorHeading + calibratedHeadingOffset) else lastBearing
        val speed = lastSpeedMps.coerceIn(0.0, 55.0)
        val distance = speed * ageSeconds.coerceAtMost(MAX_EXTRAPOLATION_SECONDS)
        val estimated = destinationPoint(lastLat, lastLon, distance, bearing)
        gpsText.text = "마지막 GPS: %.6f, %.6f\n수신 후 경과: %.1f초 · 마지막 속도: %.1f km/h · 진행 방향: %.0f°".format(
            lastLat, lastLon, ageSeconds, speed * 3.6, bearing
        )
        estimateText.text = "예상 위치\n%.6f, %.6f\n마지막 GPS에서 %.0fm 이동 추정".format(estimated.first, estimated.second, distance)
        confidenceText.text = when {
            ageSeconds <= 2.0 -> "신뢰도: GPS 정상 수신 중"
            ageSeconds <= 10.0 -> "신뢰도: 낮아지는 중 · 일정 속도/방향 가정"
            ageSeconds <= MAX_EXTRAPOLATION_SECONDS -> "신뢰도: 낮음 · 터널에서 오차가 빠르게 커질 수 있음"
            else -> "신뢰도: 매우 낮음 · " + MAX_EXTRAPOLATION_SECONDS.toInt() + "초 이후 위치 추정을 제한했습니다"
        }
        if (ageSeconds > 2.0 && isTracking) status.text = "상태: GPS 갱신 지연 · 추정 모드"
    }

    private fun destinationPoint(lat: Double, lon: Double, distanceMeters: Double, bearingDegrees: Double): Pair<Double, Double> {
        val earth = 6371000.0
        val angular = distanceMeters / earth
        val bearing = Math.toRadians(bearingDegrees)
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val lat2 = Math.asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
        val lon2 = lon1 + Math.atan2(
            sin(bearing) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sin(lat2)
        )
        return Pair(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    private fun normalizeAngle(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

    override fun onResume() {
        super.onResume()
        if (isTracking) {
            try { sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } } catch (_: Exception) {}
        }
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startTracking()
        } else if (requestCode == REQUEST_LOCATION) {
            status.text = "상태: 위치 권한이 거부되었습니다."
        }
    }

    companion object {
        private const val REQUEST_LOCATION = 601
        private const val MAX_EXTRAPOLATION_SECONDS = 45.0
    }
}
