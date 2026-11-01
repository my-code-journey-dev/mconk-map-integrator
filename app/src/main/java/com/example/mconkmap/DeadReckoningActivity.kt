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
import androidx.core.content.FileProvider
import java.io.File
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.text.InputType
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
    private lateinit var outageSecondsInput: EditText
    private lateinit var manualSpeedInput: EditText
    private lateinit var manualSpeedOffsetInput: EditText
    private lateinit var manualSpeedEnabled: CheckBox
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

        root.addView(text("수동 차속 설정 (OBD 없이 사용)", 18f, white, true))
        manualSpeedEnabled = CheckBox(this).apply {
            text = "수동 차속 사용"
            textSize = 15f
            setTextColor(white)
            isChecked = prefs.getBoolean("manual_speed_enabled", false)
        }
        root.addView(manualSpeedEnabled)
        root.addView(text("차량 속도 입력 (km/h)", 14f, white))
        manualSpeedInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setTextColor(white)
            setHintTextColor(muted)
            hint = "예: 80"
            setText(if (prefs.contains("manual_speed_kmh")) prefs.getFloat("manual_speed_kmh", 0f).toString() else "")
        }
        root.addView(manualSpeedInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56))
        root.addView(text("추가 보정값 (+ km/h)", 14f, white))
        manualSpeedOffsetInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setTextColor(white)
            setHintTextColor(muted)
            hint = "예: 5  (입력 속도에 5 km/h 추가)"
            setText(prefs.getFloat("manual_speed_offset_kmh", 0f).toString())
        }
        root.addView(manualSpeedOffsetInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56))
        val saveManualSpeed = button("수동 차속 설정 저장")
        saveManualSpeed.setOnClickListener {
            val speed = manualSpeedInput.text.toString().trim().toDoubleOrNull()
            val offset = manualSpeedOffsetInput.text.toString().trim().toDoubleOrNull()
            if (manualSpeedEnabled.isChecked && (speed == null || !speed.isFinite() || speed !in 0.0..180.0)) {
                showMessage("차량 속도를 0~180 km/h 범위로 입력하세요.")
                return@setOnClickListener
            }
            if (offset == null || !offset.isFinite() || offset !in 0.0..50.0) {
                showMessage("추가 보정값은 0~50 km/h 범위로 입력하세요.")
                return@setOnClickListener
            }
            val editor = prefs.edit()
                .putBoolean("manual_speed_enabled", manualSpeedEnabled.isChecked)
                .putFloat("manual_speed_offset_kmh", offset.toFloat())
            if (speed != null && speed.isFinite() && speed in 0.0..180.0) {
                editor.putFloat("manual_speed_kmh", speed.toFloat())
            }
            editor.apply()
            showMessage(if (manualSpeedEnabled.isChecked)
                "저장 완료: 입력 속도 %.1f + 보정값 %.1f = 최대 %.1f km/h".format(speed, offset, (speed!! + offset).coerceAtMost(180.0))
                else "수동 차속 사용이 꺼졌습니다. GPS 속도 유지 기능을 사용합니다.")
        }
        root.addView(saveManualSpeed, buttonParams())
        root.addView(text("수동 차속은 GPS가 끊긴 구간에서 사용합니다. OBD가 연결되어 있으면 OBD 속도가 우선됩니다. 설정값은 저장 후 추적 서비스에 적용됩니다.", 13f, muted))

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

        root.addView(text("GPS를 끊을 시간(초)", 14f, white))
        outageSecondsInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("10")
            textSize = 18f
            setTextColor(white)
            setHintTextColor(muted)
        }
        root.addView(outageSecondsInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56))
        val export = button("주행 CSV 기록 공유 / 내보내기")
        export.setOnClickListener { shareLogFile() }
        root.addView(export, buttonParams())

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

    private fun shareLogFile() {
        val path = prefs.getString("log_path", null)
        val file = path?.let { File(it) }
        if (file == null || !file.exists()) {
            showMessage("CSV 기록 파일이 없습니다. 추적을 시작한 뒤 다시 시도하세요.")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "주행 CSV 내보내기"))
        } catch (e: Exception) {
            showMessage("CSV 공유 실패: ${e.javaClass.simpleName}")
        }
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
                val outage = outageSecondsInput.text.toString().toDoubleOrNull()?.coerceIn(1.0, 120.0) ?: 10.0
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
