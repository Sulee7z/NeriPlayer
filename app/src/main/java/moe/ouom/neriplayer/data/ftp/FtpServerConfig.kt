package moe.ouom.neriplayer.data.ftp

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

/**
 * FTP 服务器连接配置。
 */
data class FtpServerConfig(
    val host: String = "",
    val port: Int = 21,
    val username: String = "",
    val password: String = "",
    val basePath: String = "/"
) {
    fun isConfigured(): Boolean = host.isNotBlank()

    val normalizedBasePath: String
        get() {
            val trimmed = basePath.trim().replace('\\', '/')
            return if (trimmed.isBlank()) "/" else if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        }

    val displayHost: String
        get() = if (port > 0 && port != 21) "$host:$port" else host
}
