package moe.ouom.neriplayer.platform.netease.api.client

import moe.ouom.neriplayer.platform.netease.api.crypto.NeteaseCrypto
import okhttp3.FormBody
import okhttp3.Request
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * Dedicated desktop log transport. Do not inherit an Android login's device cookies.
 * Protocol reference: api-enhanced/module/scrobble.js and util/request.js (2026-11-19).
 * Importantly, clientlog is not the ordinary music.163.com WEAPI endpoint.
 */
internal fun buildNeteaseFeedbackRequest(
    cookies: Map<String, String>,
    logs: String,
    nowMs: Long = System.currentTimeMillis(),
    nonce: Int = SecureRandom().nextInt(10_000)
): Request {
    require(!cookies["MUSIC_U"].isNullOrBlank()) { "NetEase feedback requires a login" }
    val header = linkedMapOf(
        "os" to "osx",
        "appver" to "3.1.10.5100",
        "osver" to "15.5",
        "channel" to "netease",
        "versioncode" to "140",
        "mobilename" to "",
        "deviceId" to "", // No Android serial, IMEI, or generated hardware identity.
        "buildver" to (nowMs / 1000).toString(),
        "resolution" to "1920x1080",
        "__csrf" to cookies["__csrf"].orEmpty(),
        "MUSIC_U" to cookies.getValue("MUSIC_U"),
        "requestId" to "${nowMs}_${nonce.toString().padStart(4, '0')}"
    )
    cookies["NMTID"]?.takeIf(String::isNotBlank)?.let { header["NMTID"] = it }
    val encrypted = NeteaseCrypto.eApiEncrypt("/api/feedback/weblog", linkedMapOf(
        "logs" to logs, "e_r" to false, "header" to header
    ))
    val body = FormBody.Builder().apply { encrypted.forEach { (k, v) -> add(k, v) } }.build()
    fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    return Request.Builder()
        .url("https://clientlog.music.163.com/eapi/feedback/weblog")
        .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .header("Cookie", header.entries.joinToString("; ") { (k, v) -> "${encode(k)}=${encode(v)}" })
        .header("Accept", "application/json")
        .post(body)
        .build()
}
