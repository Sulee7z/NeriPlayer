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

    private val ENCODING_CANDIDATES = listOf("UTF-8", "GBK", "Shift_JIS", "GB18030")

    fun list(config: FtpServerConfig, path: String): List<FtpEntry> {
        val utf8Entries = withClient(config) { client ->
            client.list(path)
        }
        // 目录名解码出乱码符(\uFFFD)时, 逐个尝试其他编码重列
        if (utf8Entries.none { it.name.contains('\uFFFD') }) return utf8Entries
        for (encoding in ENCODING_CANDIDATES.drop(1)) {
            val retry = withClient(config, controlEncoding = encoding) { client ->
                client.list(path)
            }
            if (retry.none { it.name.contains('\uFFFD') }) {
                return retry
            }
        }
        return utf8Entries
    }

    fun download(
        config: FtpServerConfig,
        remotePath: String,
        expectedSize: Long,
        output: OutputStream,
        onProgress: (bytes: Long, total: Long) -> Unit
    ) {
        // 文件名含非 ASCII(中文/日文等)时, 服务器可能是 GBK/Shift-JIS 编码:
        // 依次尝试各编码的 RETR, 均失败(且未写入数据)时, 用对应编码重列父目录,
        // 按文件大小匹配出真实文件名再下载
        val encodings = if (remotePath.any { it.code > 127 }) {
            ENCODING_CANDIDATES
        } else {
            listOf("UTF-8")
        }
        var lastError: IOException? = null
        for (encoding in encodings) {
            val written = java.util.concurrent.atomic.AtomicLong(0L)
            val tracked = TrackingOutputStream(output, written)
            try {
                withClient(config, controlEncoding = encoding) { client ->
                    client.download(remotePath, expectedSize, tracked, onProgress)
                }
                return
            } catch (e: IOException) {
                lastError = e
                // 已写入数据说明 RETR 成功过(传输中断), 换编码没有意义
                if (written.get() > 0L) break
            }
        }

        // 名字可能是乱码(列表编码与服务器不一致):
        // 1) 用服务器校验命令(MD5/XMD5/HASH)探测目标文件, 成功即确定正确编码并下载
        // 2) 校验命令不可用时, 按大小匹配真实文件名兜底
        if (lastError != null) {
            val parent = parentOf(remotePath)
            for (encoding in ENCODING_CANDIDATES.drop(1)) {
                val digest = withClient(config, controlEncoding = encoding) { client ->
                    client.probeDigest(remotePath)
                }
                if (digest != null) {
                    withClient(config, controlEncoding = encoding) { client ->
                        client.download(remotePath, expectedSize, output, onProgress)
                    }
                    return
                }
            }
            for (encoding in ENCODING_CANDIDATES.drop(1)) {
                val sizeMatched = withClient(config, controlEncoding = encoding) { client ->
                    client.list(parent).filter {
                        !it.isDirectory && it.size == expectedSize
                    }
                }
                if (sizeMatched.size != 1) {
                    continue
                }
                val candidate = sizeMatched[0]
                if (candidate.path == remotePath) continue
                // 用大小匹配到的名字, 依次尝试全部编码下载
                for (retrEncoding in ENCODING_CANDIDATES) {
                    val written = java.util.concurrent.atomic.AtomicLong(0L)
                    val tracked = TrackingOutputStream(output, written)
                    try {
                        withClient(config, controlEncoding = retrEncoding) { client ->
                            client.download(candidate.path, expectedSize, tracked, onProgress)
                        }
                        return
                    } catch (e: IOException) {
                        lastError = e
                        if (written.get() > 0L) break
                    }
                }
            }
        }

        throw lastError ?: IOException("FTP 下载失败")
    }

    private fun <T> withClient(
        config: FtpServerConfig,
        controlEncoding: String = "UTF-8",
        block: (Session) -> T
    ): T {
        val client = FTPClient()
        val username = config.username.ifBlank { "anonymous" }
        try {
            // 重要: setControlEncoding 必须在 connect 之前调用!
            // commons-net 在 connect 时按当时的编码创建控制流, 之后设置不生效
            // (控制流会停留在默认 US-ASCII, 中文名发送时被替换成 ?)
            client.setControlEncoding(controlEncoding)
            client.connectTimeout = CONNECT_TIMEOUT_MS
            client.defaultTimeout = READ_TIMEOUT_MS
            client.connect(config.host, config.port.coerceIn(1, 65535))
            if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                throw IOException("FTP 连接失败: ${client.replyString?.trim()}")
            }
            client.soTimeout = READ_TIMEOUT_MS
            if (!client.login(username, config.password)) {
                throw IOException("FTP 登录失败(账号或密码错误)")
            }
            // RFC 2640: 部分服务器(vsftpd 等)必须显式 OPTS UTF8 ON 才启用 UTF-8 文件名,
            // 否则 RETR 会把非 ASCII 字符全部替换成 ?, 导致 550;
            // 该命令必须放在登录之后(部分服务器登录前拒绝一切命令, 返回 530)
            if (controlEncoding.equals("UTF-8", ignoreCase = true)) {
                client.sendCommand("OPTS UTF8 ON")
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

    /** 统计写入字节数的输出流包装, 用于判断 RETR 是否已经开始传数据。 */
    private class TrackingOutputStream(
        private val delegate: OutputStream,
        private val written: java.util.concurrent.atomic.AtomicLong
    ) : OutputStream() {
        override fun write(b: Int) {
            delegate.write(b)
            written.incrementAndGet()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            written.addAndGet(len.toLong())
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
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
            // 部分服务器(IIS/NAS 等)对前导 / 的绝对路径支持不佳, 会返回 550:
            // 依次尝试 绝对路径 -> 去掉前导 / -> CWD 到所在目录后按文件名 RETR
            val ok = retrieveWithFallbacks(remote, counting)
            if (!ok) {
                throw IOException("FTP 下载失败: ${client.replyString?.trim()}")
            }
            onProgress(counting.bytesWritten, expectedSize.coerceAtLeast(counting.bytesWritten))
        }

        private fun retrieveWithFallbacks(remotePath: String, output: OutputStream): Boolean {
            val relative = remotePath.removePrefix("/")
            if (relative.isNotEmpty()) {
                if (client.retrieveFile(relative, output)) return true
            }
            if (client.retrieveFile(remotePath, output)) return true
            val parent = parentOf(remotePath)
            val name = remotePath.substringAfterLast('/')
            if (parent.isNotEmpty() && client.changeWorkingDirectory(parent)) {
                if (client.retrieveFile(name, output)) return true
            }
            return false
        }

        /**
         * 用服务器校验命令(MD5/XMD5/HASH)查询远端文件摘要。
         * 返回 32 位十六进制摘要, 服务器不支持时返回 null。
         */
        fun probeDigest(remotePath: String): String? {
            for (command in listOf("MD5", "XMD5", "HASH")) {
                val code = client.sendCommand(command, remotePath)
                val text = client.replyString?.trim().orEmpty()
                if (FTPReply.isPositiveCompletion(code)) {
                    extractHexDigest(text)?.let { return it }
                }
            }
            return null
        }

        /** 广度优先扫描目录, 收集音乐/视频文件(单连接, 减少重连开销)。 */        fun scanMedia(startPath: String, maxDepth: Int, maxEntries: Int): List<FtpEntry> {
            val result = ArrayList<FtpEntry>()
            val queue = ArrayDeque<Pair<String, Int>>()
            queue.addLast(startPath to 0)
            while (queue.isNotEmpty() && result.size < maxEntries) {
                val (dir, depth) = queue.removeFirst()
                if (depth > maxDepth) continue
                val entries = list(dir)
                for (entry in entries) {
                    if (result.size >= maxEntries) break
                    if (entry.isDirectory) {
                        if (depth + 1 <= maxDepth) {
                            queue.addLast(entry.path to depth + 1)
                        }
                    } else if (isMediaFile(entry.name)) {
                        result.add(entry)
                    }
                }
            }
            return result
        }
    }

    private fun parentOf(path: String): String {
        val normalized = normalizePath(path).trimEnd('/')
        if (normalized.isEmpty() || normalized == "/") return "/"
        val lastSlash = normalized.lastIndexOf('/')
        if (lastSlash <= 0) return "/"
        return normalized.substring(0, lastSlash)
    }

    private fun isMediaFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in MEDIA_EXTENSIONS
    }

    /** 从服务器回复文本中提取 32 位十六进制摘要(如 "213 MD5 0F34...1C" / "250-0F34...1C")。 */
    private fun extractHexDigest(reply: String): String? {
        val token = reply.split(Regex("[\\s:'\\-\\[\\],()<>]+"))
            .firstOrNull { it.length == 32 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
        return token
    }

    private val MEDIA_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "ape", "alac", "wv", "amr",
        "mp4", "mkv", "webm", "avi", "mov", "ts", "flv", "m4v", "3gp", "mpg", "mpeg", "wmv"
    )

    /**
     * 递归扫描 [startPath] 下最多 [maxDepth] 层目录, 返回全部音乐/视频文件。
     */
    fun scanMediaFiles(
        config: FtpServerConfig,
        startPath: String,
        maxDepth: Int = 4,
        maxEntries: Int = 500
    ): List<FtpEntry> {
        return withClient(config) { session ->
            session.scanMedia(startPath, maxDepth, maxEntries)
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
