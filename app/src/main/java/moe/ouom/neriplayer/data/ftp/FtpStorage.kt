@file:Suppress("DEPRECATION")

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

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import moe.ouom.neriplayer.core.logging.NPLogger

/**
 * FTP 服务器配置的加密存储(账号密码不落明文)。
 */
class FtpStorage(private val context: Context) {
    private val encryptedPrefs: SharedPreferences = openEncryptedPrefsWithRecovery()

    companion object {
        private const val PREFS_NAME = "ftp_secure_prefs"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_BASE_PATH = "base_path"
    }

    fun loadConfig(): FtpServerConfig {
        return FtpServerConfig(
            host = encryptedPrefs.getString(KEY_HOST, null).orEmpty().trim(),
            port = encryptedPrefs.getInt(KEY_PORT, 21),
            username = encryptedPrefs.getString(KEY_USERNAME, null).orEmpty(),
            password = encryptedPrefs.getString(KEY_PASSWORD, null).orEmpty(),
            basePath = encryptedPrefs.getString(KEY_BASE_PATH, "/").orEmpty()
        )
    }

    fun saveConfig(config: FtpServerConfig) {
        encryptedPrefs.edit {
            putString(KEY_HOST, config.host.trim())
            putInt(KEY_PORT, config.port.coerceIn(1, 65535))
            putString(KEY_USERNAME, config.username.trim())
            putString(KEY_PASSWORD, config.password)
            putString(KEY_BASE_PATH, config.basePath.trim().ifBlank { "/" })
        }
        NPLogger.d("NERI-FtpStorage", "Saved FTP config for host=${config.host}")
    }

    fun clear() {
        encryptedPrefs.edit { clear() }
    }

    private fun openEncryptedPrefsWithRecovery(): SharedPreferences {
        return runCatching {
            createEncryptedPrefs()
        }.getOrElse { error ->
            NPLogger.w(
                "NERI-FtpStorage",
                "Failed to open FTP secure prefs, clearing storage and recreating.",
                error
            )
            runCatching { context.deleteSharedPreferences(PREFS_NAME) }
            createEncryptedPrefs()
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
