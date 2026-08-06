@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.data.auth.kugou

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

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthHealth
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import org.json.JSONObject

private const val KUGOU_AUTH_PREFS = "kugou_auth_secure_prefs"
private const val KEY_KUGOU_AUTH_BUNDLE = "kugou_auth_bundle"

/**
 * 酷狗音乐登录 cookie。
 *
 * 在 www.kugou.com 登录后复制 Cookie 导入即可; openid/userid 等 cookie
 * 用于用户歌单接口, 没有登录态时媒体库展示热门歌单。
 */
class KugouCookieRepository(private val context: Context) {
    private var encryptedPrefs: SharedPreferences
    private val _cookieFlow: MutableStateFlow<Map<String, String>>
    private val _authHealthFlow: MutableStateFlow<SavedCookieAuthHealth>

    val cookieFlow: StateFlow<Map<String, String>>
        get() = _cookieFlow.asStateFlow()

    val authHealthFlow: StateFlow<SavedCookieAuthHealth>
        get() = _authHealthFlow.asStateFlow()

    init {
        encryptedPrefs = openEncryptedPrefsWithRecovery()
        val cookies = loadCookies()
        _cookieFlow = MutableStateFlow(cookies)
        _authHealthFlow = MutableStateFlow(evaluateHealth(cookies))
    }

    fun getCookiesOnce(): Map<String, String> = _cookieFlow.value

    fun getAuthHealthOnce(): SavedCookieAuthHealth = _authHealthFlow.value

    fun saveCookies(cookies: Map<String, String>) {
        val normalized = cookies.filterKeys { it.isNotBlank() }
        encryptedPrefs.edit {
            putString(KEY_KUGOU_AUTH_BUNDLE, encode(normalized))
        }
        _cookieFlow.value = normalized
        _authHealthFlow.value = evaluateHealth(normalized)
        NPLogger.d("NERI-KugouCookieRepo", "Saved Kugou cookies: keys=${normalized.keys.joinToString()}")
    }

    fun clear() {
        encryptedPrefs.edit { remove(KEY_KUGOU_AUTH_BUNDLE) }
        _cookieFlow.value = emptyMap()
        _authHealthFlow.value = evaluateHealth(emptyMap())
    }

    fun refreshHealth() {
        _authHealthFlow.value = evaluateHealth(_cookieFlow.value)
    }

    private fun evaluateHealth(cookies: Map<String, String>): SavedCookieAuthHealth {
        val state = if (cookies.isNotEmpty()) {
            SavedCookieAuthState.Valid
        } else {
            SavedCookieAuthState.Missing
        }
        return SavedCookieAuthHealth(
            state = state,
            savedAt = System.currentTimeMillis(),
            checkedAt = System.currentTimeMillis(),
            loginCookieKeys = cookies.keys.toList()
        )
    }

    private fun loadCookies(): Map<String, String> {
        val raw = encryptedPrefs.getString(KEY_KUGOU_AUTH_BUNDLE, null).orEmpty()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            val out = linkedMapOf<String, String>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                out[key] = root.optString(key, "")
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun encode(cookies: Map<String, String>): String {
        return JSONObject().apply {
            cookies.forEach { (key, value) -> put(key, value) }
        }.toString()
    }

    private fun openEncryptedPrefsWithRecovery(): SharedPreferences {
        return runCatching {
            createEncryptedPrefs()
        }.getOrElse { error ->
            NPLogger.w(
                "NERI-KugouCookieRepo",
                "Failed to open Kugou secure prefs, clearing storage and recreating.",
                error
            )
            runCatching { context.deleteSharedPreferences(KUGOU_AUTH_PREFS) }
            createEncryptedPrefs()
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            KUGOU_AUTH_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
