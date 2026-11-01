package com.example.mconkmap

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper

class DeadReckoningActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var obdLabel: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("navigation_tracking", MODE_PRIVATE) }
    private val dark = Color.rgb(17, 24, 39)
    private val panel = Color.rgb(31, 41, 55)
    private val white = Color.rgb(243, 244, 246)
    private val muted = Color.rgb(156, 163, 175)

    private val refresh = object : Runnable {
        override fun run() {
            if (::status.isInitialized) status.text = prefs.getString("status", "서비스 대기 중") ?: "서비스 대기 중"
            handler.postDelayed(this, 700L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        updateObdLabel()
        handler.post(refresh)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 24)
            setBackgroundColor(dark)
        }
        root.addView(text("OBD2 + GPS 터널 추측항법", 23f, white, true))
        root.addView(text("아반떼 CN7 · ELM327 블루투스 SPP · 자이로 위치 추정", 13f, muted))
        root.addView(text("먼저 ELM327을 휴대폰 블루투스 설정에서 페어링하세요.", 14f, white))

        val select = button("1. OBD2 블루투스 기기 선택")
        select.setOnClickListener { chooseObdDevice() }
        root.addView(select, buttonParams())
        obdLabel = text("선택된 OBD2: 없음", 14f, muted)
        root.addView(obdLabel)

        val mockSettings = button("2. 개발자 옵션에서 모의 위치 앱 선택")
        mockSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        root.addView(mockSettings, buttonParams())
        root.addView(text("개발자 옵션 → 모의 위치 앱 선택 → MCon K 속도 실험 앱을 선택하세요.", 13f, muted))

        val start = button("3. 백그라운드 추적 시작")
        start.setOnClickListener { checkPermissionsAndStart() }
        root.addView(start, buttonParams())
        val stop = button("추적 중지")
        stop.setOnClickListener {
            stopService(Intent(this, NavigationTrackingService::class.java))
            status.text = "중지 요청됨"
        }
        root.addView(stop, buttonParams())

        root.addView(text("실시간 상태", 18f, white, true))
        status = text("서비스 대기 중", 14f, white)
        status.setPadding(14, 14, 14, 14)
        status.setBackgroundColor(panel)
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val replay = button("CSV 리플레이 파일 선택")
        replay.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/*"
            }
            startActivityForResult(intent, REQUEST_CSV)
        }
        root.addView(replay, buttonParams())
        root.addView(text("리플레이는 기록된 GPS·차속·센서 데이터를 재생해 GPS 단절 구간의 위치 오차를 계산합니다.", 13f, muted))

        val back = button("메인 화면으로 돌아가기")
        back.setOnClickListener { finish() }
        root.addView(back, buttonParams())

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(2, 8, 2, 8)
        }

    private fun button(label: String) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 17f
        maxLines = 2
        gravity = Gravity.CENTER
        includeFontPadding = true
        setLineSpacing(0f, 1.0f)
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        minHeight = 72
        minimumHeight = 72
        setPadding(16, 14, 16, 14)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.rgb(37, 99, 235))
            cornerRadius = 18f
        }
        elevation = 4f
    }

    private fun buttonParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, 8, 0, 8) }

    private fun updateObdLabel() {
        val name = prefs.getString("obd_name", null)
        val address = prefs.getString("obd_mac", null)
        obdLabel.text = if (address == null) "선택된 OBD2: 없음" else "선택된 OBD2: ${name ?: "ELM327"} · ${address}"
    }

    @Suppress("MissingPermission")
    private fun chooseObdDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BT)
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            showMessage("블루투스를 켜고 ELM327을 먼저 페어링하세요.")
            return
        }
        val devices = try { adapter.bondedDevices.toList() } catch (_: Exception) { emptyList() }
        if (devices.isEmpty()) {
            showMessage("페어링된 블루투스 기기가 없습니다. 휴대폰 설정에서 ELM327을 먼저 등록하세요.")
            return
        }
        val labels = devices.map { "${it.name ?: "이름 없는 기기"} · ${it.address}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("OBD2 기기 선택").setItems(labels) { _, which ->
            val device = devices[which]
            prefs.edit().putString("obd_mac", device.address).putString("obd_name", device.name ?: "ELM327").apply()
            updateObdLabel()
        }.setNegativeButton("취소", null).show()
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQUEST_START)
            return
        }
        startTrackingService()
    }

    private fun startTrackingService() {
        val intent = Intent(this, NavigationTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        status.text = "백그라운드 추적 시작 요청됨"
    }

    private fun showMessage(message: String) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton("확인", null).show()
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CSV && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                val csv = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                val outage = 10.0
                val result = com.example.mconkmap.logging.ReplayRunner.run(csv, outage)
                showMessage("GPS 단절 시험: ${outage.toInt()}초\n분석 샘플: ${result.points.size}\n평균 오차: %.1fm\n최대 오차: %.1fm".format(
                    result.meanErrorMeters, result.maxErrorMeters
                ))
            } catch (e: Exception) {
                showMessage("CSV 분석 실패: ${e.javaClass.simpleName}")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_START) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startTrackingService()
            else showMessage("위치·블루투스·알림 권한이 필요합니다.")
        } else if (requestCode == REQUEST_BT && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            chooseObdDevice()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_START = 810
        private const val REQUEST_BT = 811
        private const val REQUEST_CSV = 812
    }
}
