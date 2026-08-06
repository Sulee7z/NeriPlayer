package moe.ouom.neriplayer.data.auth.common

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

import kotlinx.coroutines.flow.StateFlow

/**
 * 通用 Cookie 登录仓库接口(酷狗/酷我等以 Cookie 登录的平台共用)。
 */
interface PlatformCookieRepository {
    val cookieFlow: StateFlow<Map<String, String>>
    val authHealthFlow: StateFlow<SavedCookieAuthHealth>

    fun getCookiesOnce(): Map<String, String>
    fun getAuthHealthOnce(): SavedCookieAuthHealth
    fun saveCookies(cookies: Map<String, String>)
    fun clear()
    fun refreshHealth()
}
