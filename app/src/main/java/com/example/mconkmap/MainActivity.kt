package com.example.mconkmap

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private val dark = Color.rgb(17, 24, 39)
    private val green = Color.rgb(22, 163, 74)
    private val light = Color.rgb(243, 244, 246)

    private data class MapService(val label: String, val url: String)
    private val services = listOf(
        MapService("네이버 지도", "https://map.naver.com/p/search/서울역"),
        MapService("카카오맵", "https://map.kakao.com/?q=서울역"),
        MapService("티맵", "https://www.tmap.co.kr/")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = dark
        window.navigationBarColor = dark
        buildUi()
        loadService(0)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(light)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 10, 16, 10)
            setBackgroundColor(dark)
        }
        val title = TextView(this).apply {
            text = "지도 통합"
            textSize = 21f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusText = TextView(this).apply {
            text = "프로토타입 · HUD 연동 미포함"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(statusText)
        root.addView(header)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.WHITE)
        }
        services.forEachIndexed { index, service ->
            val button = Button(this).apply {
                text = service.label
                isAllCaps = false
                textSize = 14f
                setOnClickListener { loadService(index) }
            }
            tabs.addView(button, LinearLayout.LayoutParams(0, 52, 1f).apply {
                setMargins(4, 0, 4, 0)
            })
        }
        root.addView(tabs)

        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
        }
        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val footer = TextView(this).apply {
            text = "테스트용 초기 버전 · 웹 지도 표시 여부는 서비스 정책에 따라 달라질 수 있음"
            textSize = 11f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(footer)
        setContentView(root)
    }

    private fun loadService(index: Int) {
        val service = services[index]
        statusText.text = "${service.label} · HUD 미연동"
        webView.loadUrl(service.url)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
