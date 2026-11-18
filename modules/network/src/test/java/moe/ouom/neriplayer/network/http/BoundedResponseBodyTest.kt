package moe.ouom.neriplayer.network.http

import okhttp3.ResponseBody
import okhttp3.MediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BoundedResponseBodyTest {
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
