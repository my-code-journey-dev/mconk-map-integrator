package com.example.mconkmap

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.text.InputType
import android.widget.Button
import android.widget.EditText
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
    private lateinit var speedCorrectionText: TextView
    private var speedOffsetKmh = 0
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastSpeedMps = 0.0
    private var lastBearing = 0.0
    private var lastFixElapsedMs = 0L
    private var sensorHeading = Double.NaN
    private var calibratedHeadingOffset = 0.0
    private var headingCalibrated = false
    private var hasGpsFix = false
    private var isTracking = false
    private var mockSpeedTestActive = false
    private var mockLat = Double.NaN
    private var mockLon = Double.NaN
    private var mockLastTickMs = 0L
    private lateinit var mockTestButton: Button
    private val mockSpeedRunnable = object : Runnable {
        override fun run() {
            if (!mockSpeedTestActive) return
            try {
                val now = SystemClock.elapsedRealtime()
                val dt = ((now - mockLastTickMs).coerceAtLeast(0L) / 1000.0).coerceAtMost(2.0)
                mockLastTickMs = now
                val speedMps = ((lastSpeedMps * 3.6 + speedOffsetKmh).coerceAtLeast(0.0) / 3.6).coerceAtMost(55.0)
                val moved = destinationPoint(mockLat, mockLon, speedMps * dt, lastBearing)
                mockLat = moved.first
                mockLon = moved.second
                val mock = Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = mockLat
                    longitude = mockLon
                    accuracy = 5f
                    speed = speedMps.toFloat()
                    bearing = lastBearing.toFloat()
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, mock)
                status.text = "상태: 시스템 모의 GPS 송출 중 · 보정 속도 %.1f km/h".format(speedMps * 3.6)
                gpsText.text = "실험용 모의 GPS\n좌표: %.6f, %.6f\n속도: %.1f km/h · 방향: %.0f°".format(mockLat, mockLon, speedMps * 3.6, lastBearing)
            } catch (e: Exception) {
                status.text = "상태: 모의 GPS 실패 (" + e.javaClass.simpleName + ") · 개발자 옵션 설정을 확인하세요."
                stopMockSpeedTest()
                return
            }
            uiHandler.postDelayed(this, 1000L)
        }
    }
    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateDisplay()
            uiHandler.postDelayed(this, 1000L)
        }
    }
    private val bg = Color.rgb(17, 24, 39)
    private val panel = Color.rgb(31, 41, 55)
    private val fg = Color.rgb(243, 244, 246)
    private val muted = Color.rgb(156, 163, 175)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        speedOffsetKmh = getSharedPreferences("dr_settings", MODE_PRIVATE).getInt("speed_offset_kmh", 0).coerceIn(-20, 20)
        buildUi()
        updateDisplay()
        uiHandler.post(refreshRunnable)
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

        speedCorrectionText = makeText("속도 보정: 0 km/h", 17f, fg).apply {
            setPadding(12, 12, 12, 12)
            setBackgroundColor(panel)
        }
        root.addView(speedCorrectionText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 5, 0, 5) })

        val correctionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val correctionInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            textSize = 18f
            hint = "예: 4"
            setText(kotlin.math.abs(speedOffsetKmh).toString())
            contentDescription = "뺄 속도 보정값 km/h"
        }
        correctionRow.addView(correctionInput, LinearLayout.LayoutParams(0, 52, 1f))
        val applyCorrection = Button(this).apply {
            text = "적용"
            isAllCaps = false
            setOnClickListener {
                val amount = correctionInput.text.toString().toIntOrNull()
                if (amount == null || amount !in 0..20) {
                    status.text = "상태: 0~20 사이의 보정값을 입력해 주세요."
                } else {
                    speedOffsetKmh = -amount
                    getSharedPreferences("dr_settings", MODE_PRIVATE).edit()
                        .putInt("speed_offset_kmh", speedOffsetKmh).apply()
                    status.text = "상태: 속도 보정값 적용됨 (−" + amount + " km/h)"
                    updateDisplay()
                }
            }
        }
        correctionRow.addView(applyCorrection, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, 52
        ))
        val resetCorrection = Button(this).apply {
            text = "초기화"
            isAllCaps = false
            setOnClickListener {
                speedOffsetKmh = 0
                correctionInput.setText("0")
                getSharedPreferences("dr_settings", MODE_PRIVATE).edit()
                    .putInt("speed_offset_kmh", 0).apply()
                status.text = "상태: 속도 보정값 초기화"
                updateDisplay()
            }
        }
        correctionRow.addView(resetCorrection, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, 52
        ))
        root.addView(correctionRow)

        val mockNote = makeText(
            "실험 기능: 선택한 모의 위치 앱으로 시스템 GPS를 덮어씁니다. 지도 위치가 이동 경로를 따라가지 못할 수 있고 앱이 모의 위치를 차단할 수 있습니다. 반드시 정차 상태에서 켜고, 테스트 후 중지하세요.",
            13f, muted
        ).apply { setPadding(0, 8, 0, 8) }
        root.addView(mockNote)

        status = makeText("상태: 시작 전", 17f, fg)
        gpsText = makeText("GPS: 대기 중", 16f, fg)
        estimateText = makeText("추정 위치: 대기 중", 20f, fg)
        confidenceText = makeText("신뢰도: 아직 GPS를 수신하지 못했습니다.", 15f, muted)
        headingText = makeText("휴대폰 방향 센서: 대기 중", 15f, muted)
        mockTestButton = Button(this).apply {
            text = "시스템 속도 보정 실험 시작"
            isAllCaps = false
            setOnClickListener {
                if (mockSpeedTestActive) stopMockSpeedTest() else startMockSpeedTest()
            }
        }
        root.addView(mockTestButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 54))

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
                    headingCalibrated = true
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


    private fun startMockSpeedTest() {
        if (!hasGpsFix || lastLat.isNaN() || lastLon.isNaN()) {
            status.text = "상태: 먼저 '추측항법 시작'을 눌러 실제 GPS 위치를 받아 주세요."
            return
        }
        try {
            locationManager.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false, false, false, false,
                true, true, true,
                android.location.Criteria.POWER_MEDIUM,
                android.location.Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            mockLat = lastLat
            mockLon = lastLon
            mockLastTickMs = SystemClock.elapsedRealtime()
            mockSpeedTestActive = true
            mockTestButton.text = "시스템 속도 보정 실험 중지"
            status.text = "상태: 모의 GPS 준비됨 · 정차 상태에서만 시험"
            uiHandler.post(mockSpeedRunnable)
        } catch (e: Exception) {
            mockSpeedTestActive = false
            mockTestButton.text = "시스템 속도 보정 실험 시작"
            status.text = "상태: 모의 GPS 시작 실패 (" + e.javaClass.simpleName + "). 개발자 옵션에서 이 앱을 모의 위치 앱으로 선택했는지 확인하세요."
            try { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        }
    }

    private fun stopMockSpeedTest() {
        mockSpeedTestActive = false
        uiHandler.removeCallbacks(mockSpeedRunnable)
        try { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        if (::mockTestButton.isInitialized) mockTestButton.text = "시스템 속도 보정 실험 시작"
        if (::status.isInitialized) status.text = "상태: 모의 GPS 중지 · 실제 GPS가 복구됐는지 확인하세요."
    }

    private fun stopTracking() {
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
        sensorManager.unregisterListener(this)
        isTracking = false
    }

    override fun onLocationChanged(location: Location) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && location.isMock) return
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
            headingText.text = "휴대폰 방향 센서: " + sensorHeading.toInt() + "°" +
                if (headingCalibrated) " (보정 후 " + normalizeAngle(sensorHeading + calibratedHeadingOffset).toInt() + "°)" else " (아직 미보정)"
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
        val bearing = if (headingCalibrated && !sensorHeading.isNaN()) normalizeAngle(sensorHeading + calibratedHeadingOffset) else lastBearing
        val rawSpeedMps = lastSpeedMps.coerceIn(0.0, 55.0)
        val speed = ((rawSpeedMps * 3.6 + speedOffsetKmh).coerceAtLeast(0.0) / 3.6).coerceIn(0.0, 55.0)
        val distance = speed * ageSeconds.coerceAtMost(MAX_EXTRAPOLATION_SECONDS)
        val estimated = destinationPoint(lastLat, lastLon, distance, bearing)
        val rawSpeedKmh = rawSpeedMps * 3.6
        val correctedSpeedKmh = (rawSpeedKmh + speedOffsetKmh).coerceIn(0.0, 198.0)
        speedCorrectionText.text = "뺄 값: " + kotlin.math.abs(speedOffsetKmh) +
            " km/h   ·   보정 속도: " + correctedSpeedKmh.toInt() + " km/h"
        gpsText.text = "마지막 GPS: %.6f, %.6f\n수신 후 경과: %.1f초 · GPS 속도: %.1f km/h · 보정 표시: %.1f km/h · 진행 방향: %.0f°".format(
            lastLat, lastLon, ageSeconds, rawSpeedKmh, correctedSpeedKmh, bearing
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
        uiHandler.removeCallbacks(refreshRunnable)
        stopMockSpeedTest()
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
