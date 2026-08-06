package moe.ouom.neriplayer.activity.auth

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.activity.auth/QqQrLoginActivity
 * Created: 2026/8/6
 *
 * QQ 音乐扫码登录(WebView 内嵌 ptlogin2 官方扫码页)。
 *
 * 说明: 腾讯登录接口 (ssl.ptlogin2.qq.com/ptqrlogin) 对纯 HTTP 客户端做 TLS 指纹
 * 风控, curl / OkHttp 一律 403; 必须使用真实浏览器环境。此页面用 WebView 直接加载
 * ptlogin2 的扫码页, 扫码确认后自动登录, 轮询 CookieManager 检测登录态并提取 Cookie。
 */

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.auth.web.ForegroundWebLoginGuard
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.platform.lockPortraitIfPhone
import android.webkit.WebStorage

class QqQrLoginActivity : ComponentActivity() {

    companion object {
        const val RESULT_COOKIE = "qq_cookie_result"
        private const val LOG_TAG = "NERI-QqQrLogin"
        private const val POLL_INTERVAL_MS = 1_500L
        private const val LOGIN_READY_STRING = "登录成功"
        private const val QQ_LOGIN_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36"
    }

    private lateinit var webView: WebView
    private var foregroundWebLoginToken: AutoCloseable? = null
    private var hasReturned = false
    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (hasReturned || !this@QqQrLoginActivity::webView.isInitialized) return
            val cookies = readCookieForDomains(LOGIN_COOKIE_DOMAINS)
            val hasUin = !cookies["uin"].isNullOrBlank()
            val hasTicket = !cookies["qm_keyst"].isNullOrBlank() ||
                !cookies["p_skey"].isNullOrBlank() ||
                !cookies["skey"].isNullOrBlank()
            if (hasUin && hasTicket) {
                NPLogger.d(LOG_TAG, "QQ login detected via cookies, keys=${cookies.keys}")
                finishWithCookies(cookies)
                return
            }
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val LOGIN_COOKIE_DOMAINS = listOf(
        ".qq.com",
        "qq.com",
        "y.qq.com",
        "u.y.qq.com",
        "ssl.ptlogin2.qq.com"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockPortraitIfPhone()
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        foregroundWebLoginToken = ForegroundWebLoginGuard.enter("qqmusic")

        buildLayout()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (this@QqQrLoginActivity::webView.isInitialized && webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
        )
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        reloadLoginPage("create")
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        if (this::webView.isInitialized) {
            webView.onPause()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (this::webView.isInitialized) {
            webView.resumeTimers()
            webView.onResume()
        }
    }

    override fun onDestroy() {
        pollHandler.removeCallbacks(pollRunnable)
        if (this::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        foregroundWebLoginToken?.close()
        foregroundWebLoginToken = null
        super.onDestroy()
    }

    private fun buildLayout() {
        val root = CoordinatorLayout(this)
        val appBar = AppBarLayout(this).apply {
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
        }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.qq_qr_login)
            setNavigationIcon(R.drawable.ic_arrow_back_24)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.menu_netease_web_login)
            setOnMenuItemClickListener { onToolbarMenu(it) }
        }
        appBar.addView(toolbar)

        webView = WebView(this).apply {
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                behavior = AppBarLayout.ScrollingViewBehavior()
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            // 伪装桌面浏览器 UA: 避免腾讯把 WebView 识别为移动端壳并引导跳转系统浏览器
            settings.userAgentString = QQ_LOGIN_DESKTOP_UA
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                // 登录弹窗/跳转通过 window.open 打开时, 拦截到当前 WebView 内, 避免跳系统浏览器
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    NPLogger.d(LOG_TAG, "onCreateWindow intercepted, loading in current WebView")
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    transport.webView = view ?: return false
                    resultMsg.sendToTarget()
                    return true
                }
            }
            webViewClient = InnerClient()
        }
        webView.resumeTimers()
        forceFreshWebContext()

        root.addView(webView)
        root.addView(appBar)
        appBar.bringToFront()
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            appBar.updatePadding(top = status.top)
            webView.updatePadding(bottom = nav.bottom)
            insets
        }
    }

    private fun onToolbarMenu(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                webView.reload()
                true
            }
            R.id.action_read_cookie -> {
                readAndReturnCookies()
                true
            }
            else -> false
        }
    }

    private fun readAndReturnCookies() {
        try {
            CookieManager.getInstance().flush()
            val map = readCookieForDomains(LOGIN_COOKIE_DOMAINS)
            val hasUin = !map["uin"].isNullOrBlank()
            val hasTicket = !map["qm_keyst"].isNullOrBlank() || !map["p_skey"].isNullOrBlank()
            if (!hasUin || !hasTicket) {
                NPLogger.w(LOG_TAG, "QQ login cookie incomplete, keys=${map.keys}")
                webView.evaluateJavascript("window.alert('Cookie 未完整, 请确认已登录后重试')", null)
                return
            }
            finishWithCookies(map)
        } catch (error: Throwable) {
            NPLogger.e(LOG_TAG, "read cookies failed", error)
        }
    }

    private fun finishWithCookies(cookies: Map<String, String>) {
        if (hasReturned) return
        hasReturned = true
        pollHandler.removeCallbacks(pollRunnable)
        val json = org.json.JSONObject().apply {
            cookies.forEach { (key, value) -> put(key, value) }
        }.toString()
        setResult(RESULT_OK, Intent().putExtra(RESULT_COOKIE, json))
        NPLogger.d(LOG_TAG, "QQ login OK, cookie keys=${cookies.keys}")
        finish()
    }

    private fun forceFreshWebContext() {
        NPLogger.d(LOG_TAG, "Clearing QQ WebView state")
        val cm = CookieManager.getInstance()
        val urls = listOf(
            "https://qq.com",
            "https://y.qq.com",
            "https://ssl.ptlogin2.qq.com"
        )
        val keys = listOf(
            "uin",
            "skey",
            "p_skey",
            "pt4_token",
            "qm_keyst",
            "qm_kt",
            "qm_ckey"
        )
        urls.forEach { url ->
            keys.forEach { k ->
                expireCookie(cm, url, k, domain = null)
                expireCookie(cm, url, k, domain = ".qq.com")
                expireCookie(cm, url, k, domain = "qq.com")
            }
        }
        cm.flush()
        listOf(
            "https://qq.com",
            "https://y.qq.com",
            "https://ssl.ptlogin2.qq.com"
        ).forEach(WebStorage.getInstance()::deleteOrigin)
        if (this::webView.isInitialized) {
            webView.clearCache(true)
            webView.clearHistory()
        }
    }

    private fun expireCookie(
        cookieManager: CookieManager,
        url: String,
        key: String,
        domain: String?
    ) {
        val domainPart = domain?.let { "; Domain=$it" }.orEmpty()
        cookieManager.setCookie(
            url,
            "$key=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0$domainPart; Path=/; Secure"
        )
    }

    private fun reloadLoginPage(reason: String) {
        if (hasReturned || !this::webView.isInitialized) {
            return
        }
        // 加载 QQ 音乐个人中心页(与 listen1 get_login_url 一致), 用户点"登录"后任选方式
        // (扫码/QQ/验证码), 登录完成会自动跳回并写入 cookie, 由轮询/页面检测自动抓取。
        val loginUrl = "https://y.qq.com/portal/profile.html"
        NPLogger.d(LOG_TAG, "Loading QQ login reason=$reason url=$loginUrl")
        if (!webView.url.isNullOrBlank()) {
            webView.stopLoading()
        }
        webView.loadUrl(loginUrl)
    }

    private fun readCookieForDomains(domains: List<String>): Map<String, String> {
        val cm = CookieManager.getInstance()
        val result = linkedMapOf<String, String>()
        domains.forEach { d ->
            val raw = cm.getCookie("https://$d").orEmpty()
            if (raw.isBlank()) return@forEach
            raw.split(';')
                .map { it.trim() }
                .forEach { pair ->
                    val eq = pair.indexOf('=')
                    if (eq > 0) {
                        val k = pair.substring(0, eq)
                        val v = pair.substring(eq + 1)
                        result[k] = v
                    }
                }
        }
        return result
    }

    private inner class InnerClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: android.webkit.WebResourceRequest?
        ): Boolean {
            val url = request?.url?.toString() ?: return false
            // 拉起 QQ 客户端(QQ 一键登录): mqq / mqqapi / wtloginmqq 协议交给系统
            if (url.startsWith("mqq") || url.startsWith("wtloginmqq")) {
                NPLogger.d(LOG_TAG, "Launch QQ app via scheme: ${url.take(80)}")
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }.onFailure { error ->
                    NPLogger.w(LOG_TAG, "Launch QQ app failed: ${error.message}")
                }
                return true
            }
            return super.shouldOverrideUrlLoading(view, request)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            // 登录完成后立即尝试读取(比轮询更快); 也兜底手动"读取 Cookie"
            if (url?.startsWith("https://y.qq.com") == true) {
                CookieManager.getInstance().flush()
                val cookies = readCookieForDomains(LOGIN_COOKIE_DOMAINS)
                val hasUin = !cookies["uin"].isNullOrBlank()
                val hasTicket = !cookies["qm_keyst"].isNullOrBlank() ||
                    !cookies["p_skey"].isNullOrBlank() ||
                    !cookies["skey"].isNullOrBlank()
                if (hasUin && hasTicket) {
                    NPLogger.d(LOG_TAG, "QQ login detected on y.qq.com page")
                    finishWithCookies(cookies)
                }
            }
        }
    }
}
