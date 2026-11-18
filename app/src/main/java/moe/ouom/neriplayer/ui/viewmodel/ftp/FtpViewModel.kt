package moe.ouom.neriplayer.ui.viewmodel.ftp

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.platform.ftp.FtpClient
import moe.ouom.neriplayer.platform.ftp.FtpEntry
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.platform.ftp.storage.FtpServerConfig
import moe.ouom.neriplayer.data.model.SongItem
import java.io.File

data class FtpDownloadState(
    val fileName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long
) {
    val fraction: Float
        get() = if (totalBytes > 0L) {
            (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

data class FtpUiState(
    val config: FtpServerConfig = FtpServerConfig(),
    val configured: Boolean = false,
    val editingConfig: Boolean = false,
    val currentPath: String = "/",
    val entries: List<FtpEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val downloading: FtpDownloadState? = null,
    val savingConfig: Boolean = false,
    val configError: String? = null,
    val scanning: Boolean = false,
    val scanError: String? = null,
    val mediaFiles: List<FtpEntry> = emptyList(),
    val showMediaResults: Boolean = false
)

class FtpViewModel(application: Application) : AndroidViewModel(application) {
    private val storage = AppContainer.ftpStorage

    private val initialConfig = storage.loadConfig()

    private val _uiState = MutableStateFlow(
        FtpUiState(
            config = initialConfig,
            configured = initialConfig.isConfigured(),
            currentPath = initialConfig.normalizedBasePath
        )
    )
    val uiState: StateFlow<FtpUiState> = _uiState.asStateFlow()

    private var pendingPath: String? = null
    private var listingJob: Job? = null
    private var downloadJob: Job? = null
    private var scanJob: Job? = null
    private var listingGeneration = 0L

    init {
        if (_uiState.value.configured) {
            refresh()
        }
    }

    fun saveConfig(
        host: String,
        port: Int,
        username: String,
        password: String,
        basePath: String
    ) {
        _uiState.update { it.copy(savingConfig = true, configError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val candidate = FtpServerConfig(
                        host = host.trim(),
                        port = port,
                        username = username.trim(),
                        password = password,
                        basePath = basePath.trim().ifBlank { "/" }
                    )
                    FtpClient.testConnection(candidate)
                    storage.saveConfig(candidate)
                    candidate
                }
            }
            _uiState.update { it.copy(savingConfig = false) }
            result.onSuccess { config ->
                listingJob?.cancel()
                scanJob?.cancel()
                downloadJob?.cancel()
                _uiState.update {
                    it.copy(
                        config = config,
                        configured = true,
                        editingConfig = false,
                        currentPath = config.normalizedBasePath,
                        configError = null
                    )
                }
                refresh()
            }.onFailure { error ->
                NPLogger.w("FtpViewModel", "FTP 连接失败: ${error.message}")
                _uiState.update {
                    it.copy(configError = error.message ?: "connect_failed")
                }
            }
        }
    }

    fun setEditingConfig(editing: Boolean) {
        _uiState.update { it.copy(editingConfig = editing, configError = null) }
    }

    fun clearConfig() {
        listingGeneration++
        listingJob?.cancel()
        scanJob?.cancel()
        downloadJob?.cancel()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { storage.clear() }
                _uiState.value = FtpUiState()
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                _uiState.update { it.copy(configError = error.message) }
            }
        }
    }

    fun refresh() {
        val config = _uiState.value.config
        if (!config.isConfigured()) return
        val path = pendingPath ?: _uiState.value.currentPath
        pendingPath = null
        val generation = ++listingGeneration
        listingJob?.cancel()
        listingJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching { FtpClient.list(config, path) }
            }
            if (generation != listingGeneration || config != _uiState.value.config) return@launch
            _uiState.update {
                result.fold(
                    onSuccess = { entries ->
                        it.copy(loading = false, entries = entries, currentPath = path)
                    },
                    onFailure = { error ->
                        it.copy(
                            loading = false,
                            error = error.message ?: "list_failed"
                        )
                    }
                )
            }
        }
    }

    fun enterDirectory(entry: FtpEntry) {
        if (!entry.isDirectory) return
        pendingPath = entry.path
        refresh()
    }

    fun goUp() {
        val current = _uiState.value.currentPath
        val parent = parentPath(current)
        if (parent == current) return
        pendingPath = parent
        refresh()
    }

    /** 扫描当前目录下的音乐/视频文件(递归最多 4 层)。 */
    fun scanMedia() {
        val config = _uiState.value.config
        if (!config.isConfigured() || _uiState.value.scanning) return
        val path = _uiState.value.currentPath
        scanJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    scanning = true,
                    scanError = null,
                    mediaFiles = emptyList(),
                    showMediaResults = true
                )
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    FtpClient.scanMediaFiles(config, path)
                }
            }
            _uiState.update {
                result.fold(
                    onSuccess = { files ->
                        it.copy(scanning = false, mediaFiles = files)
                    },
                    onFailure = { error ->
                        it.copy(
                            scanning = false,
                            scanError = error.message ?: "scan_failed"
                        )
                    }
                )
            }
        }
    }

    fun showBrowse() {
        _uiState.update { it.copy(showMediaResults = false, mediaFiles = emptyList()) }
    }

    fun downloadAndPlay(entry: FtpEntry) {
        if (entry.isDirectory) return
        if (downloadJob?.isActive == true) return
        val config = _uiState.value.config
        if (!config.isConfigured()) return
        downloadJob = viewModelScope.launch {
            val target = withContext(Dispatchers.IO) {
                resolveTargetFile(config, entry)
            }
            if (target.exists() && target.length() > 0L &&
                (entry.size <= 0L || target.length() == entry.size)
            ) {
                playLocalFile(target, entry)
                return@launch
            }
            _uiState.update {
                it.copy(downloading = FtpDownloadState(entry.name, 0L, entry.size))
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    downloadToFile(config, entry, target)
                }
            }
            _uiState.update { it.copy(downloading = null) }
            result.onSuccess { file ->
                playLocalFile(file, entry)
            }.onFailure { error ->
                // 清理半截文件, 避免下次直接播放损坏文件
                runCatching { target.delete() }
                NPLogger.w("FtpViewModel", "FTP 下载失败: ${error.message}")
                _uiState.update {
                    it.copy(error = "download_failed:${error.message}")
                }
            }
        }
    }

    private suspend fun downloadToFile(
        config: FtpServerConfig,
        entry: FtpEntry,
        target: File
    ): File {
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        try {
        withContext(Dispatchers.IO) {
            val jobContext = currentCoroutineContext()
            partial.outputStream().use { output ->
                FtpClient.download(
                    config = config,
                    remotePath = entry.path,
                    expectedSize = entry.size,
                    output = output,
                    onProgress = { bytes, total ->
                        jobContext.ensureActive()
                        _uiState.update {
                            it.copy(
                                downloading = FtpDownloadState(
                                    fileName = entry.name,
                                    bytesDownloaded = bytes,
                                    totalBytes = total
                                )
                            )
                        }
                    }
                )
            }
            jobContext.ensureActive()
            if (!partial.renameTo(target)) throw java.io.IOException("FTP 缓存提交失败")
        }
        } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { partial.delete() } }
        return target
    }

    private fun playLocalFile(file: File, entry: FtpEntry) {
        val name = entry.name.substringBeforeLast('.')
        val song = SongItem(
            id = file.absolutePath.hashCode().toLong() and 0x7fffffffL,
            name = name.ifBlank { file.name },
            artist = FTP_ARTIST_TAG,
            album = FTP_ALBUM_TAG,
            albumId = 0L,
            durationMs = 0L,
            coverUrl = null,
            mediaUri = file.absolutePath,
            originalName = name.ifBlank { file.name },
            originalArtist = FTP_ARTIST_TAG,
            localFileName = file.name,
            localFilePath = file.absolutePath,
            channelId = "local",
            audioId = file.absolutePath,
            sourceStableKey = "ftp:${entry.path}"
        )
        PlayerManager.playPlaylist(listOf(song), 0)
    }

    private fun resolveTargetFile(config: FtpServerConfig, entry: FtpEntry): File {
        fun hash(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        val hostDir = hash("${config.host}:${config.port}:${config.username}")
        val extension = entry.name.substringAfterLast('.', "bin").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "bin"
        val safePath = hash("${entry.path}:${entry.size}:${entry.modifiedTime}") + ".$extension"
        return File(
            File(getApplication<Application>().cacheDir, "ftp/$hostDir"),
            safePath
        )
    }

    private fun parentPath(path: String): String {
        val normalized = FtpClient.normalizePath(path).trimEnd('/')
        if (normalized.isEmpty() || normalized == "/") return "/"
        val lastSlash = normalized.lastIndexOf('/')
        if (lastSlash <= 0) return "/"
        return normalized.substring(0, lastSlash)
    }

    companion object {
        const val FTP_ALBUM_TAG = "FTP"
        const val FTP_ARTIST_TAG = "FTP"
    }
}
