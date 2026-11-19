package moe.ouom.neriplayer.network.http

import okhttp3.ResponseBody
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BoundedResponseBodyTest {
    @Test fun declaredGb2312IsDecodedWithoutMojibake() {
        val text = "甜度爆表 | 旋律说唱狙击少女心"
        val body = text.toByteArray(charset("GB2312"))
            .toResponseBody("application/x-javascript;charset=gb2312".toMediaType())
        assertEquals(text, body.readTextBounded(1024))
    }
    @Test fun textWithoutCharsetDefaultsToUtf8() {
        assertEquals("网易云歌曲", "网易云歌曲".toByteArray()
            .toResponseBody("application/json".toMediaType()).readTextBounded(1024))
    }
    @Test fun bomTakesPriorityOverDeclaredCharset() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "中文歌单".toByteArray()
        assertEquals("中文歌单", bytes.toResponseBody("application/json;charset=gb2312".toMediaType())
            .readTextBounded(1024))
    }
    @Test(expected = IOException::class) fun charsetDecodingDoesNotBypassTheByteBudget() {
        "中文".toByteArray(charset("GB2312"))
            .toResponseBody("text/plain;charset=gb2312".toMediaType()).readTextBounded(3)
    }
    @Test fun validUtf8ResponseIsDecoded() {
        assertEquals("歌曲", "歌曲".toResponseBody().readUtf8Bounded(6))
    }
    @Test(expected = IOException::class) fun declaredBodyLimitIsEnforced() {
        "歌曲".toResponseBody().readUtf8Bounded(5)
    }
    @Test(expected = IOException::class) fun chunkedBodyLimitIsEnforced() {
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = Buffer().writeUtf8("123456")
        }
        body.readUtf8Bounded(5)
    }
}
