package moe.ouom.neriplayer.util.network

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
 * File: moe.ouom.neriplayer.util.network/CustomSourceProxySelector
 * Created: 2026/8/6
 *
 * 自定义音源(LX 脚本)专用代理选择器:
 * - forceBypass = true(默认): 始终直连, 不经过任何代理
 * - forceBypass = false: 跟随全局 DynamicProxySelector 的绕过设置
 */

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

object CustomSourceProxySelector : ProxySelector() {
    @Volatile
    var forceBypass: Boolean = true

    /** true = 自定义音源始终走系统代理(忽略全局绕过设置); false = 按 [forceBypass] 逻辑 */
    @Volatile
    var alwaysProxy: Boolean = false

    private fun systemDefault(): ProxySelector? {
        val current = getDefault()
        return if (current === this) null else current
    }

    override fun select(uri: URI?): List<Proxy> {
        if (uri == null) return listOf(Proxy.NO_PROXY)
        return when {
            alwaysProxy -> systemDefault()?.select(uri)
                .takeUnless { it.isNullOrEmpty() }
                ?: listOf(Proxy.NO_PROXY)
            forceBypass -> listOf(Proxy.NO_PROXY)
            else -> DynamicProxySelector.select(uri)
        }
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        DynamicProxySelector.connectFailed(uri, sa, ioe)
    }
}
