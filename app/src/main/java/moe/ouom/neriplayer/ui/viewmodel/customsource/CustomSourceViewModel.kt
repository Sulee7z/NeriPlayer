package moe.ouom.neriplayer.ui.viewmodel.customsource

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
 * File: moe.ouom.neriplayer.ui.viewmodel.customsource/CustomSourceViewModel
 * Created: 2026/7/26
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import moe.ouom.neriplayer.platform.customsource.CustomAudioSource
import moe.ouom.neriplayer.platform.customsource.CustomSourceMetadataParser
import moe.ouom.neriplayer.core.di.AppContainer

data class CustomSourceUiState(
    val sources: List<CustomAudioSource> = emptyList(),
    val priorityMode: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null
)

class CustomSourceViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = AppContainer.customSourceManager
    private val repo = manager.repository

    private val _uiState = MutableStateFlow(CustomSourceUiState())
    val uiState: StateFlow<CustomSourceUiState> = _uiState

    init {
        viewModelScope.launch {
            try { repo.load() } catch (error: Exception) {
                if (error is CancellationException) throw error
                _uiState.update { it.copy(message = "音源加载失败: ${error.message}") }
            }
            combine(repo.sources, repo.priorityMode) { sources, priority ->
                sources to priority
            }.collect { (sources, priority) ->
                _uiState.value = _uiState.value.copy(
                    sources = sources,
                    priorityMode = priority
                )
            }
        }
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    /** Like LX Mobile, import stores metadata; explicit enable/test executes the script. */
    fun importScript(scriptContent: String) {
        importFrom { scriptContent }
    }

    /** 从 URL 下载脚本后导入。 */
    fun importScriptFromUrl(url: String) {
        importFrom {
            manager.fetchScriptFromUrl(url.trim()) ?: error("下载失败，请使用可访问的 HTTP/HTTPS 脚本直链")
        }
    }

    private fun importFrom(read: suspend () -> String) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                repo.load()
                val source = repo.importScript(read())
                _uiState.update { it.copy(message = "已保存 ${source.name}。启用或测试时才运行脚本；重复导入保留原设置。") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(message = "导入失败：${error.message}") }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true) }
        mutate {
            try {
                if (enabled) {
                    manager.enableSource(id)
                } else {
                    repo.setEnabled(id, false)
                    manager.onActiveSourceChanged()
                }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    fun rename(id: String, name: String) {
        if (name.trim().isBlank()) return
        mutate { repo.rename(id, name) }
    }

    fun moveUp(id: String) {
        mutate { repo.move(id, -1); manager.onActiveSourceChanged() }
    }

    fun moveDown(id: String) {
        mutate { repo.move(id, 1); manager.onActiveSourceChanged() }
    }

    fun setPriorityMode(priority: Boolean) {
        mutate { repo.setPriorityMode(priority); manager.onActiveSourceChanged() }
    }

    fun delete(id: String) {
        mutate {
            repo.delete(id)
            manager.onActiveSourceChanged()
        }
    }

    /** 端到端测试:对指定音源真实解析一首示例歌曲,显示详细结果。 */
    fun testSource(id: String) {
        if (_uiState.value.busy) return
        val source = _uiState.value.sources.firstOrNull { it.id == id } ?: return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val msg = moe.ouom.neriplayer.common.coroutines.runCatchingNonCancellation { manager.diagnoseNetease(source) }
                    .getOrElse { "测试异常: ${it.message}" }
                _uiState.update { it.copy(message = msg) }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    fun importScriptFromUri(uri: android.net.Uri) {
        importFrom {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(read <= CustomSourceMetadataParser.MAX_SCRIPT_BYTES - output.size()) { "脚本超过 9 MB" }
                        output.write(buffer, 0, read)
                    }
                    output.toString(Charsets.UTF_8)
                } ?: error("无法读取音源文件")
            }
        }
    }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _uiState.update { it.copy(message = "保存失败: ${error.message}") } }
        }
    }
}
