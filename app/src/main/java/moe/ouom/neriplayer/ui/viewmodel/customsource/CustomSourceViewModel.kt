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

    /** 导入脚本文本:先探测运行,回填支持平台,再入库。 */
    fun importScript(scriptContent: String) {
        if (scriptContent.isBlank()) return
        if (!manager.validateScriptContent(scriptContent)) {
            _uiState.value = _uiState.value.copy(
                message = "内容看起来不像 LX 音源脚本(缺少 lx.on / musicUrl 等标记), 已取消导入"
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, message = null)
            val result = moe.ouom.neriplayer.common.coroutines.runCatchingNonCancellation {
                val probe = manager.probeScript(scriptContent)
                repo.importScript(
                    scriptContent = scriptContent,
                    supportedSources = if (probe.ok) probe.sources else emptyMap()
                )
                if (!probe.ok) {
                    "已导入,但脚本自检未通过: ${probe.error ?: "未知"}"
                } else if (!probe.sources.containsKey(CustomAudioSource.LX_SOURCE_NETEASE)) {
                    "已导入,但该脚本似乎不支持网易云(wy)"
                } else {
                    "导入成功"
                }
            }
            manager.onActiveSourceChanged()
            _uiState.value = _uiState.value.copy(
                busy = false,
                message = result.getOrElse { "导入失败: ${it.message}" }
            )
        }
    }

    /** 从 URL 下载脚本后导入。 */
    fun importScriptFromUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, message = null)
            val result = moe.ouom.neriplayer.common.coroutines.runCatchingNonCancellation {
                val content = manager.fetchScriptFromUrl(trimmed)
                    ?: return@runCatchingNonCancellation "下载失败,请确认链接可访问(需以 http/https 开头)"
                if (!manager.validateScriptContent(content)) {
                    return@runCatchingNonCancellation "下载的内容看起来不像 LX 音源脚本, 已取消导入"
                }
                val probe = manager.probeScript(content)
                repo.importScript(
                    scriptContent = content,
                    supportedSources = if (probe.ok) probe.sources else emptyMap()
                )
                if (!probe.ok) {
                    "已导入,但脚本自检未通过: ${probe.error ?: "未知"}"
                } else if (!probe.sources.containsKey(CustomAudioSource.LX_SOURCE_NETEASE)) {
                    "已导入,但该脚本似乎不支持网易云(wy)"
                } else {
                    "导入成功"
                }
            }
            manager.onActiveSourceChanged()
            _uiState.value = _uiState.value.copy(
                busy = false,
                message = result.getOrElse { "导入失败: ${it.message}" }
            )
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        mutate {
            repo.setEnabled(id, enabled)
            manager.onActiveSourceChanged()
            if (enabled) manager.warmActive()
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
        viewModelScope.launch {
            val source = _uiState.value.sources.firstOrNull { it.id == id }
                ?: return@launch
            _uiState.value = _uiState.value.copy(busy = true, message = null)
            val msg = moe.ouom.neriplayer.common.coroutines.runCatchingNonCancellation { manager.diagnoseNetease(source) }
                .getOrElse { "测试异常: ${it.message}" }
            _uiState.value = _uiState.value.copy(busy = false, message = msg)
        }
    }

    fun importScriptFromUri(uri: android.net.Uri) {
        mutate {
            val content = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(read <= 2 * 1024 * 1024 - output.size()) { "脚本超过 2 MiB" }
                        output.write(buffer, 0, read)
                    }
                    output.toString(Charsets.UTF_8)
                } ?: error("无法读取音源文件")
            }
            importScript(content)
        }
    }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _uiState.update { it.copy(message = "保存失败: ${error.message}") } }
        }
    }
}
