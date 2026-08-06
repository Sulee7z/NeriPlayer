package moe.ouom.neriplayer.core.ftp

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
 */

import moe.ouom.neriplayer.data.ftp.FtpServerConfig
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.IOException
import java.io.OutputStream

/** FTP 目录条目 */
data class FtpEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedTime: Long = 0L
)

object FtpClient {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val DATA_TIMEOUT_MS = 60_000

    /** 校验连接并返回根目录列表(失败抛 IOException) */
    fun testConnection(config: FtpServerConfig) {
        withClient(config) { client ->
            client.list(config.normalizedBasePath)
        }
    }

    fun list(config: FtpServerConfig, path: String): List<FtpEntry> {
        return withClient(config) { client ->
            client.list(path)
        }
    }

    fun download(
        config: FtpServerConfig,
        remotePath: String,
        expectedSize: Long,
        output: OutputStream,
        onProgress: (bytes: Long, total: Long) -> Unit
    ) {
        withClient(config) { client ->
            client.download(remotePath, expectedSize, output, onProgress)
        }
    }

    private fun <T> withClient(config: FtpServerConfig, block: (Session) -> T): T {
        val client = FTPClient()
        val username = config.username.ifBlank { "anonymous" }
        try {
            client.connectTimeout = CONNECT_TIMEOUT_MS
            client.defaultTimeout = READ_TIMEOUT_MS
            client.soTimeout = READ_TIMEOUT_MS
            client.connect(config.host, config.port.coerceIn(1, 65535))
            if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                throw IOException("FTP 连接失败: ${client.replyString?.trim()}")
            }
            client.setControlEncoding("UTF-8")
            if (!client.login(username, config.password)) {
                throw IOException("FTP 登录失败(账号或密码错误)")
            }
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            client.setDataTimeout(DATA_TIMEOUT_MS)
            return block(Session(client))
        } catch (e: IOException) {
            throw e
        } finally {
            runCatching { client.logout() }
            runCatching { client.disconnect() }
        }
    }

    private class Session(private val client: FTPClient) {
        fun list(path: String): List<FtpEntry> {
            val entries = ArrayList<FtpEntry>()
            val remote = normalizePath(path)
            val files = runCatching { client.mlistDir(remote) }.getOrNull()
                ?: runCatching { client.listFiles(remote) }.getOrNull()
                ?: emptyArray()
            for (file in files) {
                if (file == null) continue
                val name = file.name?.trim()
                if (name.isNullOrBlank() || name == "." || name == "..") continue
                entries.add(
                    FtpEntry(
                        name = name,
                        path = joinPath(remote, name),
                        isDirectory = file.isDirectory,
                        size = file.size.coerceAtLeast(0L),
                        modifiedTime = file.timestamp?.timeInMillis ?: 0L
                    )
                )
            }
            return entries.sortedWith(
                compareBy<FtpEntry> { !it.isDirectory }.thenBy { it.name.lowercase() }
            )
        }

        fun download(
            remotePath: String,
            expectedSize: Long,
            output: OutputStream,
            onProgress: (bytes: Long, total: Long) -> Unit
        ) {
            val remote = normalizePath(remotePath)
            val counting = CountingOutputStream(output, expectedSize, onProgress)
            val ok = client.retrieveFile(remote, counting)
            if (!ok) {
                throw IOException("FTP 下载失败: ${client.replyString?.trim()}")
            }
            onProgress(counting.bytesWritten, expectedSize.coerceAtLeast(counting.bytesWritten))
        }
    }

    fun normalizePath(path: String): String {
        val joined = path.trim().replace('\\', '/')
        if (joined.isBlank() || joined == "/") return "/"
        if (joined.startsWith("/")) return joined
        return "/$joined"
    }

    fun joinPath(parent: String, child: String): String {
        val base = normalizePath(parent).trimEnd('/')
        val name = child.trim().replace('\\', '/').trim('/')
        if (name.isEmpty()) return base.ifEmpty { "/" }
        if (name.contains("..")) throw IOException("非法路径")
        return if (base.isEmpty() || base == "/") "/$name" else "$base/$name"
    }

    private class CountingOutputStream(
        private val delegate: OutputStream,
        private val total: Long,
        private val onProgress: (bytes: Long, total: Long) -> Unit
    ) : OutputStream() {
        @Volatile
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            bytesWritten += 1
            onProgress(bytesWritten, total)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            bytesWritten += len
            onProgress(bytesWritten, total)
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }
    }
}
