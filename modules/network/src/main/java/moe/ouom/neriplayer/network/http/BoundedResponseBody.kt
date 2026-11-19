package moe.ouom.neriplayer.network.http

import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException

fun ResponseBody.readUtf8Bounded(maxBytes: Int): String {
    return readBytesBounded(maxBytes).toString(Charsets.UTF_8)
}

/** Retain OkHttp charset/BOM decoding while enforcing the same byte budget. */
fun ResponseBody.readTextBounded(maxBytes: Int): String {
    val type = contentType()
    return readBytesBounded(maxBytes).toResponseBody(type).use { it.string() }
}

private fun ResponseBody.readBytesBounded(maxBytes: Int): ByteArray {
    require(maxBytes > 0)
    if (contentLength() > maxBytes) throw IOException("Response exceeds $maxBytes bytes")
    val output = ByteArrayOutputStream()
    byteStream().use { stream ->
        val buffer = ByteArray(8192)
        while (true) {
            val size = stream.read(buffer)
            if (size < 0) break
            if (size > maxBytes - output.size()) throw IOException("Response exceeds $maxBytes bytes")
            output.write(buffer, 0, size)
        }
    }
    return output.toByteArray()
}
