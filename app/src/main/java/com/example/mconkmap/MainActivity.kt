package com.example.mconkmap

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest
                ): Boolean {
                    return handleSpecialUrl(view, request.url.toString())
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    return handleSpecialUrl(view, url)
                }
            }
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

    private fun handleSpecialUrl(view: WebView, url: String): Boolean {
        // 일반 웹 주소(https/http)는 WebView에서 그대로 엽니다.
        if (url.startsWith("https://") || url.startsWith("http://")) {
            return false
        }

        // 네이버 지도 등의 intent:// 링크는 Android 앱 실행으로 전달합니다.
        if (url.startsWith("intent://")) {
            var fallbackUrl: String? = null
            try {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                fallbackUrl = intent.getStringExtra("browser_fallback_url")
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.component = null
                startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // 지도 앱이 설치되어 있지 않으면 아래의 웹 주소로 이동합니다.
            } catch (_: Exception) {
                // 링크 형식이 잘못되었어도 웹 대체 주소가 있으면 사용합니다.
            }

            if (!fallbackUrl.isNullOrBlank() &&
                (fallbackUrl.startsWith("https://") || fallbackUrl.startsWith("http://"))
            ) {
                view.loadUrl(fallbackUrl)
                return true
            }
            return true
        }

        // nmap://, kakaomap:// 등 기타 앱 전용 링크도 앱 실행을 시도합니다.
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (_: Exception) {
            true
        }
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
