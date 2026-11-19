package moe.ouom.neriplayer.platform.netease.api.client

import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class NeteaseFeedbackRequestTest {
    @Test fun desktopIdentityReplacesMobileDeviceInBothCookieAndEncryptedHeader() {
        val mobile = linkedMapOf("MUSIC_U" to "test-login", "__csrf" to "test-csrf",
            "NMTID" to "test-session", "os" to "android", "osver" to "14",
            "appver" to "9.9.9", "deviceId" to "android-serial", "sDeviceId" to "serial-2",
            "imei" to "imei-123", "channel" to "xiaomi")
        val request = buildNeteaseFeedbackRequest(mobile, "[]", 1_700_000_000_000, 7)
        assertEquals("https://clientlog.music.163.com/eapi/feedback/weblog", request.url.toString())
        assertEquals("POST", request.method)
        assertTrue(request.header("User-Agent")!!.contains("Macintosh"))
        val cookie = request.header("Cookie")!!
        assertTrue(cookie.contains("os=osx"))
        assertFalse(cookie.contains("android"))
        assertFalse(cookie.contains("serial"))
        assertFalse(cookie.contains("imei"))
        val body = request.body as FormBody
        assertEquals(1, body.size)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"))
        val hex = body.value(0)
        val plain = cipher.doFinal(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            .toString(Charsets.UTF_8).split("-36cd479b6b5-")
        assertEquals("/api/feedback/weblog", plain[0])
        assertEquals(moe.ouom.neriplayer.platform.netease.api.crypto.NeteaseCrypto
            .md5Hex("nobody${plain[0]}use${plain[1]}md5forencrypt"), plain[2])
        val payload = JSONObject(plain[1])
        assertFalse(payload.getBoolean("e_r"))
        assertEquals("[]", payload.getString("logs"))
        val header = payload.getJSONObject("header")
        assertEquals("osx", header.getString("os"))
        assertEquals("3.1.10.5100", header.getString("appver"))
        assertEquals("15.5", header.getString("osver"))
        assertEquals("", header.getString("deviceId"))
        assertEquals("test-login", header.getString("MUSIC_U"))
        assertEquals("test-csrf", header.getString("__csrf"))
        assertEquals("test-session", header.getString("NMTID"))
        assertEquals("1700000000000_0007", header.getString("requestId"))
        assertEquals("android-serial", mobile["deviceId"])
        assertEquals("android", mobile["os"])
    }

    @Test fun uploadTransportCannotOverwriteDesktopIdentityWithTheLoginCookieJar() {
        val session = NeteaseRequestSession(mapOf("MUSIC_U" to "login", "os" to "android"))
        assertSame(CookieJar.NO_COOKIES, session.feedbackHttpClient.cookieJar)
        assertFalse(session.feedbackHttpClient.followRedirects)
        assertFalse(session.feedbackHttpClient.followSslRedirects)
        assertEquals("android", session.requestCookiesForUrl("https://music.163.com/".toHttpUrl())["os"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingLoginCannotBeTurnedIntoAnAnonymousUpload() {
        buildNeteaseFeedbackRequest(mapOf("os" to "android"), "[]")
    }

    @Test fun logoutAndAccountSwitchRejectBeforeAnyFeedbackNetworkRequest() {
        val client = NeteaseClient { error("Comment token not used") }
        assertEquals(301, JSONObject(client.notifyPlayStart(42L)).getInt("code"))
        client.setPersistedCookies(mapOf("MUSIC_U" to "account-a"))
        val previous = client.commentCacheSessionKey()!!
        client.setPersistedCookies(mapOf("MUSIC_U" to "account-b"))
        assertEquals(301, JSONObject(client.notifyPlayStart(42L, expectedAccountKey = previous)).getInt("code"))
        assertEquals(301, JSONObject(client.scrobble(42L, 30, expectedAccountKey = previous)).getInt("code"))
    }
}
