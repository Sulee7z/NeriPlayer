package moe.ouom.neriplayer.ui.viewmodel.auth

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

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthHealth
import org.json.JSONObject

data class KugouKuwoAuthUiState(
    val health: SavedCookieAuthHealth = SavedCookieAuthHealth(),
    val hasSavedCookies: Boolean = false
)

sealed interface KugouKuwoAuthEvent {
    data class ShowSnack(val message: String) : KugouKuwoAuthEvent
    data object LoginSuccess : KugouKuwoAuthEvent
}

/**
 * 酷狗/酷我共用的 cookie 登录 ViewModel。
 */
open class KugouKuwoAuthViewModel(
    app: Application,
    private val cookieKey: CookieKey
) : AndroidViewModel(app) {

    enum class CookieKey {
        KUGOU,
        KUWO
    }

    private val repo: moe.ouom.neriplayer.data.auth.common.PlatformCookieRepository =
        when (cookieKey) {
            CookieKey.KUGOU -> AppContainer.kugouCookieRepo
            CookieKey.KUWO -> AppContainer.kuwoCookieRepo
        }

    private val _uiState = MutableStateFlow(
        KugouKuwoAuthUiState(
            health = repo.getAuthHealthOnce(),
            hasSavedCookies = repo.getCookiesOnce().isNotEmpty()
        )
    )
    val uiState: StateFlow<KugouKuwoAuthUiState> = _uiState.asStateFlow()

    private val _events = Channel<KugouKuwoAuthEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            repo.authHealthFlow.collect { health ->
                _uiState.update { current -> current.copy(health = health) }
            }
        }
        viewModelScope.launch {
            repo.cookieFlow.collect { cookies ->
                _uiState.update { current ->
                    current.copy(hasSavedCookies = cookies.isNotEmpty())
                }
            }
        }
    }

    fun refreshAuthHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.refreshHealth()
            _uiState.update { current ->
                current.copy(health = repo.getAuthHealthOnce())
            }
        }
    }

    fun clearCookies() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.clear()
            _events.send(
                KugouKuwoAuthEvent.ShowSnack(
                    getApplication<Application>().getString(R.string.auth_cookie_cleared)
                )
            )
        }
    }

    fun importCookiesFromRaw(raw: String) {
        importCookiesFromMap(parseRawCookies(raw))
    }

    fun importCookiesFromMap(map: Map<String, String>) {
        viewModelScope.launch(Dispatchers.IO) {
            if (map.isEmpty()) {
                _events.send(
                    KugouKuwoAuthEvent.ShowSnack(
                        getApplication<Application>().getString(R.string.auth_cookie_empty)
                    )
                )
                return@launch
            }
            repo.saveCookies(map)
            _events.send(KugouKuwoAuthEvent.LoginSuccess)
        }
    }

    fun parseJsonToMap(json: String): Map<String, String> {
        return runCatching {
            val obj = JSONObject(json)
            val out = linkedMapOf<String, String>()
            val it = obj.keys()
            while (it.hasNext()) {
                val key = it.next()
                out[key] = obj.optString(key, "")
            }
            out
        }.getOrElse { emptyMap() }
    }

    private fun parseRawCookies(raw: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        raw.split(';').forEach { part ->
            val eq = part.indexOf('=')
            if (eq > 0) {
                val key = part.substring(0, eq).trim()
                val value = part.substring(eq + 1).trim()
                if (key.isNotBlank()) out[key] = value
            }
        }
        return out
    }
}

class KugouAuthViewModel(app: Application) :
    KugouKuwoAuthViewModel(app, KugouKuwoAuthViewModel.CookieKey.KUGOU)

class KuwoAuthViewModel(app: Application) :
    KugouKuwoAuthViewModel(app, KugouKuwoAuthViewModel.CookieKey.KUWO)
