package moe.ouom.neriplayer.core.api.qq

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
 * File: moe.ouom.neriplayer.core.api.qq/QqQrLoginClient
 * Created: 2026/8/6
 *
 * QQ 音乐扫码登录客户端 (腾讯统一登录 ptlogin2 链路):
 * 1. GET xui.ptlogin2.qq.com/cgi-bin/xlogin   -> 拿 pt_login_sig cookie
 * 2. GET ssl.ptlogin2.qq.com/ptqrshow          -> 拿 qrsig cookie + 二维码 GIF
 * 3. 轮询 ssl.ptlogin2.qq.com/ptqrlogin        -> 成功后收集 Set-Cookie (uin/skey/p_skey 等)
 */

import moe.ouom.neriplayer.util.network.DynamicProxySelector
import moe.ouom.neriplayer.core.logging.NPLogger
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val QQ_QR_LOG_TAG = "NERI-QqQrClient"

private const val QQ_XLOGIN_URL =
    "https://xui.ptlogin2.qq.com/cgi-bin/xlogin"
private const val QQ_PTQR_SHOW_URL =
    "https://ssl.ptlogin2.qq.com/ptqrshow"
private const val QQ_PTQR_LOGIN_URL =
    "https://ssl.ptlogin2.qq.com/ptqrlogin"
private const val QQ_REDIRECT_URL =
    "https://y.qq.com/portal/player.html"

/** QQ 音乐在腾讯统一登录体系中的应用 ID (y.qq.com 桌面端) */
private const val QQ_APP_ID = 716027609
private const val QQ_DAID = 383
private const val QQ_PT_3RD_AID = 100497308

private const val QQ_QR_REFERER = "https://xui.ptlogin2.qq.com/"
private const val QQ_QR_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"

private const val QQ_QR_NETWORK_RETRY_COUNT = 3
private const val QQ_QR_NETWORK_RETRY_DELAY_MS = 200L
private const val QQ_QR_SESSION_ATTEMPT_COUNT = 3

data class QqQrLoginSession(
    val qrsig: String,
    val qrImageBytes: ByteArray,
    val qrContent: String
)

data class QqQrLoginCheckResult(
    val code: Int,
    val message: String,
    val cookies: Map<String, String> = emptyMap(),
    val uin: String = ""
) {
    val isConfirmed: Boolean
        get() = code == 0

    /** 二维码已失效, 需要重新生成 */
    val isExpired: Boolean
        get() = code == 65

    /** 已扫码, 等待手机端确认 */
    val isScanned: Boolean
        get() = code == 67
}

class QqQrLoginClient {
    private val cookieStore: ConcurrentHashMap<String, String> = ConcurrentHashMap()
    private val http = OkHttpClient.Builder()
        .proxySelector(DynamicProxySelector)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun reset() {
        cookieStore.clear()
        NPLogger.d(QQ_QR_LOG_TAG, "Reset QR login cookie store")
    }

    @Throws(IOException::class)
    fun createSession(): QqQrLoginSession {
        NPLogger.d(QQ_QR_LOG_TAG, "Create QR session start")
        // 1. 先访问 xlogin 页面拿到 pt_login_sig cookie (失败不致命, 继续走 ptqrshow)
        runCatching {
            executeGetText(
                QQ_XLOGIN_URL.toHttpUrl().newBuilder()
                    .addQueryParameter("appid", QQ_APP_ID.toString())
                    .addQueryParameter("s_url", QQ_REDIRECT_URL)
                    .addQueryParameter("pt_no_auth", "1")
                    .build()
                    .toString()
            )
        }.onFailure { error ->
            NPLogger.w(QQ_QR_LOG_TAG, "xlogin preflight failed (non-fatal): ${error.message}")
        }

        // 2. 请求二维码 GIF + qrsig
        var qrImageBytes: ByteArray? = null
        var lastError: IOException? = null
        var attemptIndex = 0
        while (qrImageBytes == null && attemptIndex < QQ_QR_SESSION_ATTEMPT_COUNT) {
            runCatching {
                val timestamp = System.currentTimeMillis()
                val showUrl = QQ_PTQR_SHOW_URL.toHttpUrl().newBuilder()
                    .addQueryParameter("appid", QQ_APP_ID.toString())
                    .addQueryParameter("e", "2")
                    .addQueryParameter("l", "M")
                    .addQueryParameter("s", "3")
                    .addQueryParameter("d", "72")
                    .addQueryParameter("v", "4")
                    .addQueryParameter("t", timestamp.toString())
                    .addQueryParameter("daid", QQ_DAID.toString())
                    .addQueryParameter("pt_3rd_aid", QQ_PT_3RD_AID.toString())
                    .build()
                qrImageBytes = executeGetBytes(showUrl.toString())
            }.onFailure { error ->
                lastError = error as? IOException ?: IOException(error)
                NPLogger.w(
                    QQ_QR_LOG_TAG,
                    "ptqrshow attempt=${attemptIndex + 1} failed: ${error.message}"
                )
                Thread.sleep(QQ_QR_NETWORK_RETRY_DELAY_MS)
            }
            attemptIndex += 1
        }

        val bytes = qrImageBytes
            ?: throw lastError ?: IOException("QQ QR 会话创建失败: 二维码生成失败")
        val qrsig = cookieStore["qrsig"]
            ?: throw IOException("QQ QR 会话创建失败: 未获取到 qrsig")
        val qrContent = buildQrContent(qrsig)
        NPLogger.d(
            QQ_QR_LOG_TAG,
            "Create QR session success qrsig=${qrsig.redactedKey()} bytes=${bytes.size}"
        )
        return QqQrLoginSession(
            qrsig = qrsig,
            qrImageBytes = bytes,
            qrContent = qrContent
        )
    }

    @Throws(IOException::class)
    fun checkLogin(session: QqQrLoginSession): QqQrLoginCheckResult {
        val ptqrtoken = hash33(session.qrsig)
        val loginSig = cookieStore["pt_login_sig"].orEmpty()
        val timestamp = System.currentTimeMillis()
        val url = QQ_PTQR_LOGIN_URL.toHttpUrl().newBuilder()
            .addQueryParameter("u1", QQ_REDIRECT_URL)
            .addQueryParameter("ptqrtoken", ptqrtoken.toString())
            .addQueryParameter("ptredirect", "0")
            .addQueryParameter("h", "1")
            .addQueryParameter("t", "1")
            .addQueryParameter("g", "1")
            .addQueryParameter("from_ui", "1")
            .addQueryParameter("ptlang", "2052")
            .addQueryParameter("action", "0-0-$timestamp")
            .addQueryParameter("js_ver", "22082315")
            .addQueryParameter("js_type", "1")
            .addQueryParameter("login_sig", loginSig)
            .addQueryParameter("pt_uistyle", "40")
            .addQueryParameter("aid", QQ_APP_ID.toString())
            .addQueryParameter("daid", QQ_DAID.toString())
            .addQueryParameter("pt_3rd_aid", QQ_PT_3RD_AID.toString())
            .build()
        NPLogger.d(
            QQ_QR_LOG_TAG,
            "Poll QR status qrsig=${session.qrsig.redactedKey()} ptqrtoken=$ptqrtoken"
        )
        val text = executeGetText(url.toString())
        val (code, jumpUrl, message, uin) = parsePtuiCallback(text)
        if (code == 0) {
            // 跟随登录跳转链接, 拿齐 skey / p_skey / pt4_token 等登录票据
            if (!jumpUrl.isNullOrBlank() && jumpUrl.startsWith("http")) {
                runCatching {
                    executeGetText(jumpUrl)
                }.onFailure { error ->
                    NPLogger.w(QQ_QR_LOG_TAG, "follow login redirect failed: ${error.message}")
                }
            }
            // 尝试补全 QQ 音乐的 qm_keyst / qm_kt cookie
            runCatching { fetchMusicKeyIfPossible(uin) }
                .onFailure { error ->
                    NPLogger.w(QQ_QR_LOG_TAG, "fetch qm_keyst failed: ${error.message}")
                }
        }
        val cookies = if (code == 0) currentCookies() else emptyMap()
        NPLogger.d(
            QQ_QR_LOG_TAG,
            "Poll QR response code=$code message=$message uin=$uin cookieKeys=${cookies.keys}"
        )
        return QqQrLoginCheckResult(
            code = code,
            message = message,
            cookies = cookies,
            uin = uin
        )
    }

    fun currentCookies(): Map<String, String> {
        return LinkedHashMap(cookieStore)
    }

    /**
     * 通过 musictoken 接口补全 QQ 音乐的 qm_keyst cookie。
     * 失败时静默返回(登录态仍可依赖 uin + p_skey)。
     */
    @Throws(IOException::class)
    fun fetchMusicKeyIfPossible(uin: String = ""): Map<String, String> {
        val resolvedUin = uin.ifBlank { cookieStore["uin"].orEmpty() }
            .trim()
            .trimStart('o')
        if (resolvedUin.isBlank()) return currentCookies()
        try {
            val data = org.json.JSONObject().put(
                "comm", org.json.JSONObject().put("uin", resolvedUin.toLongOrNull() ?: 0L)
            ).put(
                "req_0", org.json.JSONObject()
                    .put("module", "music.pf_song_detail_svr")
                    .put("method", "get_song_detail_yqq")
                    .put("param", org.json.JSONObject().put("song_mid", "0000000000"))
            ).toString()
            val url = "https://u.y.qq.com/cgi-bin/musictoken.fcg".toHttpUrl().newBuilder()
                .addQueryParameter("data", data)
                .build()
            executeGetText(url.toString())
            NPLogger.d(QQ_QR_LOG_TAG, "musictoken response processed, keys=${cookieStore.keys}")
        } catch (e: Exception) {
            NPLogger.w(QQ_QR_LOG_TAG, "fetch qm_keyst failed: ${e.message}")
        }
        return currentCookies()
    }

    private fun buildQrContent(qrsig: String): String {
        // 二维码内容为 ptlogin2 的扫码确认 URL
        return "https://ssl.ptlogin2.qq.com/ptqrshow?appid=$QQ_APP_ID&e=2&l=M&s=3&d=72&v=4" +
            "&daid=$QQ_DAID&pt_3rd_aid=$QQ_PT_3RD_AID&t=${System.currentTimeMillis()}&u=1"
    }

    /** 解析 ptuiCB('code','type','url','encry','msg','uin') 形式的 JS 回调 */
    private fun parsePtuiCallback(text: String): PtuiCallback {
        val match = Regex("ptuiCB\\('([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'\\)")
            .find(text)
        if (match == null) {
            NPLogger.w(QQ_QR_LOG_TAG, "unexpected ptqrlogin response: ${text.take(160)}")
            return PtuiCallback(code = -1, jumpUrl = null, message = text.take(120), uin = "")
        }
        val code = match.groupValues[1].toIntOrNull() ?: -1
        val jumpUrl = match.groupValues[3].takeIf { it.startsWith("http") }
        val message = match.groupValues[5]
        val uin = match.groupValues[6]
        return PtuiCallback(code = code, jumpUrl = jumpUrl, message = message, uin = uin)
    }

    private data class PtuiCallback(
        val code: Int,
        val jumpUrl: String?,
        val message: String,
        val uin: String
    )

    private fun hash33(value: String): Int {
        var hash = 0
        for (element in value) {
            hash = ((hash shl 5) + hash + element.code) and 0x7fffffff
        }
        return hash and 0x7fffffff
    }

    private fun executeGetText(url: String): String {
        val bytes = executeGetBytes(url)
        return String(bytes, Charsets.UTF_8)
    }

    private fun executeGetBytes(url: String): ByteArray {
        var lastError: IOException? = null
        repeat(QQ_QR_NETWORK_RETRY_COUNT) { attemptIndex ->
            try {
                return executeGetOnce(url)
            } catch (error: IOException) {
                lastError = error
                if (attemptIndex == QQ_QR_NETWORK_RETRY_COUNT - 1) {
                    throw error
                }
                Thread.sleep(QQ_QR_NETWORK_RETRY_DELAY_MS)
            }
        }
        throw lastError ?: IOException("QQ QR request failed")
    }

    private fun executeGetOnce(url: String): ByteArray {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
            .header("Referer", QQ_QR_REFERER)
            .header("User-Agent", QQ_QR_UA)
            .get()
        currentCookieHeader().takeIf { it.isNotBlank() }?.let { cookieHeader ->
            requestBuilder.header("Cookie", cookieHeader)
        }

        http.newCall(requestBuilder.build()).execute().use { response ->
            storeSetCookieHeaders(response.headers("Set-Cookie"))
            val bytes = response.body.bytes()
            if (bytes.isEmpty()) {
                throw IOException("Empty QQ QR response")
            }
            return bytes
        }
    }

    private fun currentCookieHeader(): String {
        return cookieStore.entries.joinToString("; ") { (key, value) -> "$key=$value" }
    }

    private fun storeSetCookieHeaders(headers: List<String>) {
        if (headers.isEmpty()) return
        headers.forEach { header ->
            val update = parseSetCookieHeader(header) ?: return@forEach
            if (update.removed) {
                cookieStore.remove(update.name)
            } else {
                cookieStore[update.name] = update.value
            }
        }
    }

    private fun parseSetCookieHeader(header: String): CookieUpdate? {
        val parts = header.split(';').map { it.trim() }
        val nameValue = parts.firstOrNull().orEmpty()
        val separatorIndex = nameValue.indexOf('=')
        if (separatorIndex <= 0) return null
        val name = nameValue.substring(0, separatorIndex).trim()
        val value = nameValue.substring(separatorIndex + 1).trim()
        if (name.isBlank()) return null
        val removed = value.isBlank() || parts.any { part ->
            part.equals("Max-Age=0", ignoreCase = true) ||
                part.startsWith("Expires=Thu, 01 Jan 1970", ignoreCase = true)
        }
        return CookieUpdate(name = name, value = value, removed = removed)
    }

    private fun String.compactForLog(maxLength: Int = 360): String {
        val compact = replace('\r', ' ').replace('\n', ' ').trim()
        return if (compact.length <= maxLength) compact else "${compact.take(maxLength)}..."
    }

    private fun String.redactedKey(): String {
        if (length <= 8) return "***"
        return "${take(4)}...${takeLast(4)}"
    }

    private data class CookieUpdate(
        val name: String,
        val value: String,
        val removed: Boolean
    )
}
