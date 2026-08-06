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
 * File: moe.ouom.neriplayer.activity.auth/QqWebLoginActivity
 * Created: 2026/8/6
 *
 * QQ 音乐网页登录(WebView): 打开 y.qq.com 完成登录后, 点击菜单"读取 Cookie 并返回",
 * 从 qq.com 域提取登录 Cookie (uin / skey / p_skey / pt4_token / qm_keyst 等) 返回给设置页。
 */

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.View
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
import com.google.android.material.snackbar.Snackbar
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.auth.web.ForegroundWebLoginGuard
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.platform.lockPortraitIfPhone
import android.webkit.WebStorage

class QqWebLoginActivity : ComponentActivity() {

    companion object {
        const val RESULT_COOKIE = "qq_cookie_result"
        private const val LOG_TAG = "NERI-QqWebLogin"
        private const val LOGIN_URL = "https://y.qq.com/portal/profile.html"
    }

    private lateinit var webView: WebView
    private var foregroundWebLoginToken: AutoCloseable? = null
    private var hasReturned = false

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
                    if (this@QqWebLoginActivity::webView.isInitialized && webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
        )
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
            title = getString(R.string.qq_web_login)
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
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
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
            val map = readCookieForDomains(
                listOf(
                    ".qq.com",
                    "qq.com",
                    "y.qq.com",
                    "u.y.qq.com",
                    "ssl.ptlogin2.qq.com"
                )
            )
            val hasUin = !map["uin"].isNullOrBlank()
            val hasTicket = !map["qm_keyst"].isNullOrBlank() || !map["p_skey"].isNullOrBlank()
            if (!hasUin || !hasTicket) {
                showNeriViewSnackbar(
                    webView,
                    getString(R.string.snackbar_cookie_empty),
                    Snackbar.LENGTH_SHORT
                )
                NPLogger.w(
                    LOG_TAG,
                    "QQ web login cookie incomplete, keys=${map.keys}"
                )
                return
            }

            hasReturned = true
            val json = org.json.JSONObject().apply {
                map.forEach { (key, value) -> put(key, value) }
            }.toString()
            setResult(RESULT_OK, Intent().putExtra(RESULT_COOKIE, json))
            finish()
        } catch (error: Throwable) {
            showNeriViewSnackbar(
                webView,
                getString(R.string.snackbar_read_failed, error.message ?: error.javaClass.simpleName),
                Snackbar.LENGTH_LONG
            )
        }
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
        NPLogger.d(LOG_TAG, "Loading QQ login reason=$reason url=$LOGIN_URL")
        if (!webView.url.isNullOrBlank()) {
            webView.stopLoading()
        }
        webView.loadUrl(LOGIN_URL)
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

    private fun showNeriViewSnackbar(view: View, text: String, duration: Int) {
        Snackbar.make(view, text, duration).show()
    }

    private inner class InnerClient : WebViewClient()
}
