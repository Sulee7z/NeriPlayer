@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.data.auth.qq

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
 * File: moe.ouom.neriplayer.data.auth.qq/QqCookieRepository
 * Created: 2026/8/6
 */

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthHealth
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import moe.ouom.neriplayer.core.logging.NPLogger
import org.json.JSONObject

private const val QQ_AUTH_PREFS = "qq_auth_secure_prefs"
private const val KEY_QQ_AUTH_BUNDLE = "qq_auth_bundle"

/**
 * QQ 音乐登录的关键 cookie。
 *
 * 腾讯音乐接口 (musicu.fcg 等) 通过 uin + qm_keyst / qm_ckey 等 cookie 识别登录态;
 * 其中 qm_keyst 是换取播放地址 (vkey) 所需的核心票据。
 */
private val QQ_LOGIN_COOKIE_KEYS = listOf(
    "uin",
    "qm_keyst",
    "qm_kt",
    "qm_ckey",
    "p_uin",
    "p_skey",
    "pt4_token",
    "skey"
)

data class QqAuthBundle(
    val cookies: Map<String, String> = emptyMap(),
    val savedAt: Long = 0L
) {
    fun hasLoginCookies(): Boolean {
        return !cookies["uin"].isNullOrBlank() && (
            !cookies["qm_keyst"].isNullOrBlank() ||
                !cookies["p_skey"].isNullOrBlank() ||
                !cookies["skey"].isNullOrBlank()
            )
    }

    fun normalized(savedAt: Long = this.savedAt): QqAuthBundle {
        return copy(
            cookies = LinkedHashMap(cookies.filterKeys { it.isNotBlank() }),
            savedAt = savedAt
        )
    }

    fun toJson(): String {
        return JSONObject().apply {
            put(
                "cookies",
                JSONObject().apply {
                    cookies.forEach { (key, value) -> put(key, value) }
                }
            )
            put("savedAt", savedAt)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): QqAuthBundle {
            return runCatching {
                val root = JSONObject(json)
                val cookiesJson = root.optJSONObject("cookies") ?: JSONObject()
                val cookies = linkedMapOf<String, String>()
                val keys = cookiesJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    cookies[key] = cookiesJson.optString(key, "")
                }
                val savedAt = root.optLong("savedAt", 0L)
                QqAuthBundle(
                    cookies = cookies,
                    savedAt = savedAt
                ).normalized(savedAt = savedAt)
            }.getOrDefault(QqAuthBundle())
        }
    }
}

internal fun evaluateQqAuthHealth(
    bundle: QqAuthBundle,
    now: Long = System.currentTimeMillis()
): SavedCookieAuthHealth {
    val normalized = bundle.normalized(savedAt = bundle.savedAt)
    val loginCookieKeys = QQ_LOGIN_COOKIE_KEYS.filter { key ->
        !normalized.cookies[key].isNullOrBlank()
    }
    if (!normalized.hasLoginCookies()) {
        return SavedCookieAuthHealth(
            state = SavedCookieAuthState.Missing,
            savedAt = normalized.savedAt,
            checkedAt = now,
            loginCookieKeys = loginCookieKeys
        )
    }

    val savedAt = normalized.savedAt
    val ageMs = if (savedAt > 0L) {
        (now - savedAt).coerceAtLeast(0L)
    } else {
        Long.MAX_VALUE
    }
    return SavedCookieAuthHealth(
        state = SavedCookieAuthState.Valid,
        savedAt = savedAt,
        checkedAt = now,
        ageMs = ageMs,
        loginCookieKeys = loginCookieKeys
    )
}

class QqCookieRepository(private val context: Context) {
    private var encryptedPrefs: SharedPreferences
    private val _authFlow: MutableStateFlow<QqAuthBundle>
    private val _cookieFlow: MutableStateFlow<Map<String, String>>
    private val _authHealthFlow: MutableStateFlow<SavedCookieAuthHealth>

    val cookieFlow: StateFlow<Map<String, String>>
        get() = _cookieFlow.asStateFlow()

    val authHealthFlow: StateFlow<SavedCookieAuthHealth>
        get() = _authHealthFlow.asStateFlow()

    init {
        encryptedPrefs = openEncryptedPrefsWithRecovery()
        val initialBundle = loadAuthBundle()
        _authFlow = MutableStateFlow(initialBundle)
        _cookieFlow = MutableStateFlow(initialBundle.cookies)
        _authHealthFlow = MutableStateFlow(
            evaluateQqAuthHealth(initialBundle)
        )
    }

    fun getCookiesOnce(): Map<String, String> = _cookieFlow.value

    fun getAuthHealthOnce(): SavedCookieAuthHealth = _authHealthFlow.value

    fun getAuthHealth(
        now: Long = System.currentTimeMillis()
    ): SavedCookieAuthHealth = evaluateQqAuthHealth(_authFlow.value, now)

    fun saveCookies(
        cookies: Map<String, String>,
        savedAt: Long = System.currentTimeMillis()
    ) {
        val normalized = QqAuthBundle(
            cookies = cookies,
            savedAt = savedAt
        ).normalized(savedAt = savedAt)
        encryptedPrefs.edit {
            putString(KEY_QQ_AUTH_BUNDLE, normalized.toJson())
        }
        _authFlow.value = normalized
        _cookieFlow.value = normalized.cookies
        _authHealthFlow.value = evaluateQqAuthHealth(normalized)
        NPLogger.d("NERI-QqCookieRepo", "Saved QQ cookies: keys=${cookies.keys.joinToString()}")
    }

    fun clear() {
        encryptedPrefs.edit {
            remove(KEY_QQ_AUTH_BUNDLE)
        }
        val cleared = QqAuthBundle()
        _authFlow.value = cleared
        _cookieFlow.value = cleared.cookies
        _authHealthFlow.value = evaluateQqAuthHealth(cleared)
        NPLogger.d("NERI-QqCookieRepo", "Cleared QQ cookies")
    }

    fun refreshHealth(now: Long = System.currentTimeMillis()) {
        _authHealthFlow.value = evaluateQqAuthHealth(
            bundle = _authFlow.value,
            now = now
        )
    }

    private fun loadAuthBundle(): QqAuthBundle {
        val raw = encryptedPrefs.getString(KEY_QQ_AUTH_BUNDLE, null).orEmpty()
        if (raw.isNotBlank()) {
            return QqAuthBundle.fromJson(raw)
        }
        return QqAuthBundle()
    }

    private fun openEncryptedPrefsWithRecovery(): SharedPreferences {
        return runCatching {
            createEncryptedPrefs()
        }.getOrElse { error ->
            NPLogger.w(
                "NERI-QqCookieRepo",
                "Failed to open QQ secure prefs, clearing storage and recreating.",
                error
            )
            clearEncryptedStorage()
            createEncryptedPrefs()
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            QQ_AUTH_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun clearEncryptedStorage() {
        runCatching {
            context.deleteSharedPreferences(QQ_AUTH_PREFS)
        }.onFailure { error ->
            NPLogger.w(
                "NERI-QqCookieRepo",
                "Failed to delete corrupted QQ secure prefs file.",
                error
            )
        }
    }
}
