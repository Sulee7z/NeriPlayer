package moe.ouom.neriplayer.core.customsource

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
 * File: moe.ouom.neriplayer.core.customsource/LxScriptEngine
 * Created: 2026/7/26
 *
 * 基于系统 WebView 的洛雪音乐(LX Music)自定义源脚本运行引擎。
 *
 * 在隐藏 WebView 中构造一个 globalThis.lx 上下文,提供 LX 脚本所需的
 * EVENT_NAMES / on / send / request / utils / env / version 接口,
 * HTTP 请求通过 Java 桥接由 OkHttp 完成(绕开 WebView 的跨域限制)。
 */

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.network.CustomSourceProxySelector
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "NERI-LxEngine"

/**
 * 单个脚本对应一个引擎实例。引擎持有一个 WebView,生命周期跟随脚本的启用状态。
 */
class LxScriptEngine(
    private val appContext: Context,
    private val script: String
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .proxySelector(CustomSourceProxySelector)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val requestTimeoutHttp = OkHttpClient.Builder()
        .proxySelector(CustomSourceProxySelector)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile private var webView: WebView? = null
    @Volatile private var initialized = false
    @Volatile private var initResult: InitResult? = null
    private val initLatch = CompletableDeferred<InitResult>()

    private val requestSeq = AtomicLong(0)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val httpCalls = ConcurrentHashMap<String, Call>()
    private val httpLog = java.util.Collections.synchronizedList(ArrayList<String>())
    /** JS setTimeout 桥: id -> Runnable, 供 clearTimeout/destroy 精确清理 */
    private val timeoutTasks = ConcurrentHashMap<Int, Runnable>()

    /** 返回最近的 HTTP 请求结果(脚本通过 lx.request 发起的),用于诊断网络是否可达。 */
    fun recentHttpLog(): List<String> = synchronized(httpLog) { httpLog.toList() }

    private fun logHttp(line: String) {
        synchronized(httpLog) {
            httpLog.add(line)
            while (httpLog.size > 12) httpLog.removeAt(0)
        }
    }

    data class InitResult(
        val ok: Boolean,
        val sources: Map<String, List<String>>,
        val error: String? = null
    )

    /** 解析结果:url 为 null 表示失败,detail 给出可读的诊断信息(供 UI/日志展示)。 */
    data class ResolveResult(
        val url: String?,
        val detail: String,
        /** true=瞬时性失败(超时/网络抖动), 值得重试; false=脚本/服务端的确定性失败(404/无版权等), 重试无意义 */
        val transient: Boolean = true
    )

    /** 最近一次脚本内部报告的错误(console.error / window.onerror / 未捕获 Promise)。 */
    @Volatile var lastScriptError: String? = null
        private set

    /** 已完成的初始化结果(null 表示尚未 start 或仍在初始化)。 */
    fun initInfo(): InitResult? = initResult

    /** 在主线程创建 WebView 并注入脚本;挂起直到脚本触发 inited 或超时。 */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    suspend fun start(timeoutMs: Long = 22_000): InitResult {
        initResult?.let { return it }
        mainHandler.post {
            try {
                val wv = WebView(appContext)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.allowFileAccess = false
                wv.settings.allowContentAccess = false
                wv.addJavascriptInterface(Bridge(), "NeriBridge")
                webView = wv
                val html = buildBootstrapHtml(script)
                wv.loadDataWithBaseURL(
                    "https://neriplayer.local/",
                    html,
                    "text/html",
                    "utf-8",
                    null
                )
            } catch (e: Throwable) {
                NPLogger.e(TAG, "WebView 创建失败", e)
                if (!initLatch.isCompleted) {
                    initLatch.complete(InitResult(false, emptyMap(), e.message))
                }
            }
        }
        val result = withTimeoutOrNull(timeoutMs) { initLatch.await() }
            ?: InitResult(false, emptyMap(), "脚本初始化超时")
        initialized = true
        initResult = result
        return result
    }

    /**
     * 请求某首歌的播放地址。
     * @param source LX 平台 key,例如 "wy"
     * @param quality LX 音质,例如 "320k" / "flac"
     * @param musicInfo LX musicInfo(至少含 songmid/id/name/singer)
     * @return 可播放 URL,失败返回 null
     */
    suspend fun getMusicUrl(
        source: String,
        quality: String,
        musicInfo: JSONObject,
        timeoutMs: Long = 20_000
    ): String? = resolve(source, quality, musicInfo, timeoutMs).url

    /** 与 getMusicUrl 相同,但返回带诊断信息的结果。 */
    suspend fun resolve(
        source: String,
        quality: String,
        musicInfo: JSONObject,
        timeoutMs: Long = 20_000
    ): ResolveResult {
        if (!initialized) start()
        initResult?.let {
            if (!it.ok) return ResolveResult(null, "引擎未初始化: ${it.error ?: "未知"}")
        }
        lastScriptError = null

        val callId = "req_${requestSeq.incrementAndGet()}"
        val deferred = CompletableDeferred<String>()
        pendingRequests[callId] = deferred

        val payload = JSONObject().apply {
            put("callId", callId)
            put("source", source)
            put("action", "musicUrl")
            put("info", JSONObject().apply {
                put("type", quality)
                put("musicInfo", musicInfo)
            })
        }
        val js = "window.__neri_invoke(${JSONObject.quote(payload.toString())});"
        runJs(js)

        val raw = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pendingRequests.remove(callId)
        if (raw == null) {
            NPLogger.w(TAG, "musicUrl 超时: source=$source quality=$quality")
            val extra = lastScriptError?.let { " | 脚本错误: $it" } ?: ""
            return ResolveResult(null, "解析超时(${timeoutMs}ms),脚本可能未返回或网络阻塞$extra", transient = true)
        }
        return try {
            val obj = JSONObject(raw)
            val url = extractMusicUrlFromScriptResponse(obj)
            if (url != null) {
                ResolveResult(url, "成功: ${url.take(80)}")
            } else {
                val err = obj.optString("error") ?: obj.optString("message")
                val cleanErr = err.takeIf { it.isNotBlank() } ?: "未知错误"
                NPLogger.w(TAG, "musicUrl 脚本返回失败($source/$quality): $cleanErr")
                val extra = lastScriptError?.let { " | $it" } ?: ""
                ResolveResult(null, "脚本返回失败($source/$quality): $cleanErr$extra", transient = false)
            }
        } catch (e: Exception) {
            NPLogger.w(TAG, "musicUrl 结果解析失败($source/$quality): $raw", e)
            ResolveResult(null, "结果解析异常($source/$quality): ${e.message}", transient = false)
        }
    }

    /**
     * 兼容多种 LX 脚本返回格式:
     * {ok:true, url:"..."} / {url:"..."} / {data:{url:"..."}} / {body:{url:"..."}}
     */
    private fun extractMusicUrlFromScriptResponse(obj: JSONObject): String? {
        if (obj.optBoolean("ok", true)) {
            obj.optString("url").takeIf { it.isNotBlank() }?.let { return it }
        }
        obj.optString("url").takeIf { it.isNotBlank() }?.let { return it }
        obj.optJSONObject("data")?.optString("url")?.takeIf { it.isNotBlank() }?.let { return it }
        obj.optJSONObject("body")?.optString("url")?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    fun destroy() {
        mainHandler.post {
            timeoutTasks.values.forEach { task -> mainHandler.removeCallbacks(task) }
            timeoutTasks.clear()
            httpCalls.values.forEach { runCatching { it.cancel() } }
            httpCalls.clear()
            pendingRequests.values.forEach { it.cancel() }
            pendingRequests.clear()
            webView?.let { wv ->
                runCatching {
                    wv.removeJavascriptInterface("NeriBridge")
                    wv.loadUrl("about:blank")
                    wv.destroy()
                }
            }
            webView = null
        }
    }

    private fun runJs(js: String) {
        mainHandler.post {
            webView?.evaluateJavascript(js, null)
        }
    }

    /** Java <-> JS 桥。方法运行在 WebView 的 JS 线程,不可阻塞。 */
    private inner class Bridge {

        @JavascriptInterface
        fun onInited(sourcesJson: String) {
            NPLogger.d(TAG, "脚本 inited: $sourcesJson")
            val map = parseSources(sourcesJson)
            if (!initLatch.isCompleted) {
                initLatch.complete(InitResult(true, map))
            }
        }

        @JavascriptInterface
        fun onInitError(message: String) {
            NPLogger.w(TAG, "脚本 inited 失败: $message")
            if (!initLatch.isCompleted) {
                initLatch.complete(InitResult(false, emptyMap(), message))
            }
        }

        @JavascriptInterface
        fun onRequestResult(callId: String, resultJson: String) {
            pendingRequests[callId]?.complete(resultJson)
        }

        @JavascriptInterface
        fun log(message: String) {
            NPLogger.d(TAG, "[script] $message")
        }

        @JavascriptInterface
        fun onScriptError(message: String) {
            lastScriptError = message
            NPLogger.w(TAG, "[script-error] $message")
        }

        /** JS setTimeout 桥(对齐 LX Mobile: 脚本大量使用 setTimeout 轮询/延迟) */
        @JavascriptInterface
        fun setTimeout(id: Int, delayMs: Long) {
            val task = Runnable {
                timeoutTasks.remove(id)
                runJs("window.__neri_timeout($id);")
            }
            timeoutTasks[id] = task
            mainHandler.postDelayed(task, delayMs.coerceAtLeast(0L))
        }

        @JavascriptInterface
        fun clearTimeout(id: Int) {
            timeoutTasks.remove(id)?.let { task -> mainHandler.removeCallbacks(task) }
        }

        /** 由脚本发起 HTTP 请求;完成后回调 JS __neri_httpCallback。 */
        @JavascriptInterface
        fun httpRequest(requestId: String, url: String, optionsJson: String) {
            try {
                val options = JSONObject(optionsJson)
                val method = options.optString("method", "GET").uppercase()
                val builder = Request.Builder().url(url)

                builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36")
                builder.header("Accept", "application/json, text/plain, */*")

                options.optJSONObject("headers")?.let { headers ->
                    val keys = headers.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = headers.optString(k)
                        if (v.isNotBlank()) builder.header(k, v)
                    }
                }

                if (method != "GET" && method != "HEAD") {
                    val bodyStr = options.optString("body", "")
                    val contentType = options.optJSONObject("headers")
                        ?.optString("Content-Type")
                        ?.takeIf { it.isNotBlank() }
                        ?: if (options.has("form")) "application/x-www-form-urlencoded" else "application/x-www-form-urlencoded"
                    val body = bodyStr.toRequestBody(contentType.toMediaTypeOrNull())
                    builder.method(method, body)
                } else {
                    builder.method(method, null)
                }

                val requestTimeout = if (options.has("timeout")) {
                    options.optLong("timeout").takeIf { it > 0 }
                } else {
                    null
                }
                val client = if (requestTimeout != null) {
                    requestTimeoutHttp.newBuilder()
                        .connectTimeout(requestTimeout, TimeUnit.MILLISECONDS)
                        .readTimeout(requestTimeout, TimeUnit.MILLISECONDS)
                        .callTimeout(requestTimeout, TimeUnit.MILLISECONDS)
                        .build()
                } else {
                    http
                }

                val shortUrl = url.take(80)
                val call = client.newCall(builder.build())
                httpCalls[requestId] = call
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        httpCalls.remove(requestId)
                        logHttp("$method $shortUrl -> 失败: ${e.message ?: "network error"}")
                        deliverHttp(requestId, error = e.message ?: "网络请求失败", resp = null)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        httpCalls.remove(requestId)
                        response.use { r ->
                            val bodyStr = r.body.string() ?: ""
                            logHttp("$method $shortUrl -> ${r.code} (${bodyStr.length}B)")
                            val headersObj = JSONObject()
                            r.headers.forEach { pair ->
                                headersObj.put(pair.first, pair.second)
                            }
                            val respObj = JSONObject().apply {
                                put("statusCode", r.code)
                                put("statusMessage", r.message.ifBlank { "OK" })
                                put("headers", headersObj)
                                put("body", bodyStr)
                                put("ok", r.isSuccessful)
                            }
                            deliverHttp(requestId, error = null, resp = respObj)
                        }
                    }
                })
            } catch (e: Exception) {
                logHttp("请求构造失败: ${e.message}")
                deliverHttp(requestId, error = e.message ?: "请求构造失败", resp = null)
            }
        }

        @JavascriptInterface
        fun httpAbort(requestId: String) {
            httpCalls.remove(requestId)?.let { runCatching { it.cancel() } }
        }

        @JavascriptInterface
        fun aesEncrypt(dataB64: String, keyB64: String, ivB64: String, mode: String): String {
            return try {
                val keyBytes = Base64.decode(keyB64, Base64.DEFAULT)
                val dataBytes = Base64.decode(dataB64, Base64.DEFAULT)
                val cipher = Cipher.getInstance(mode)
                val keySpec = SecretKeySpec(keyBytes, "AES")
                if (ivB64.isNotEmpty()) {
                    val ivBytes = Base64.decode(ivB64, Base64.DEFAULT)
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(ivBytes))
                } else {
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec)
                }
                val encrypted = cipher.doFinal(dataBytes)
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            } catch (e: Exception) {
                NPLogger.e(TAG, "AES encrypt failed", e)
                ""
            }
        }

        @JavascriptInterface
        fun rsaEncrypt(dataB64: String, keyB64: String): String {
            return try {
                val keyBytes = Base64.decode(keyB64, Base64.DEFAULT)
                val dataBytes = Base64.decode(dataB64, Base64.DEFAULT)
                val keyFactory = KeyFactory.getInstance("RSA")
                val keySpec = X509EncodedKeySpec(keyBytes)
                val publicKey = keyFactory.generatePublic(keySpec)
                val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, publicKey)
                val encrypted = cipher.doFinal(dataBytes)
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            } catch (e: Exception) {
                NPLogger.e(TAG, "RSA encrypt failed", e)
                ""
            }
        }
    }

    private fun deliverHttp(requestId: String, error: String?, resp: JSONObject?) {
        val payload = JSONObject().apply {
            put("requestId", requestId)
            if (error != null) put("error", error) else put("error", JSONObject.NULL)
            put("response", resp ?: JSONObject.NULL)
        }
        runJs("window.__neri_httpCallback(${JSONObject.quote(payload.toString())});")
    }

    private fun parseSources(json: String): Map<String, List<String>> {
        return try {
            val obj = JSONObject(json)
            val out = mutableMapOf<String, List<String>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val src = obj.optJSONObject(key) ?: continue
                val quals = src.optJSONArray("qualitys") ?: src.optJSONArray("qualities") ?: JSONArray()
                val list = ArrayList<String>(quals.length())
                for (i in 0 until quals.length()) list.add(quals.optString(i))
                out[key] = list
            }
            out
        } catch (e: Exception) {
            NPLogger.w(TAG, "解析 sources 失败", e)
            emptyMap()
        }
    }
}
