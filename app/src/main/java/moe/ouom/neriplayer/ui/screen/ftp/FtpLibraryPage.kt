package moe.ouom.neriplayer.ui.screen.ftp

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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.ftp.FtpEntry
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.ui.viewmodel.ftp.FtpViewModel
import java.util.Locale

private val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "ape", "alac", "wv", "amr"
)
private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "webm", "avi", "mov", "ts", "flv", "m4v", "3gp", "mpg", "mpeg", "wmv"
)

private fun extensionOf(name: String): String {
    return name.substringAfterLast('.', "").lowercase(Locale.ROOT)
}

private fun isAudioFile(name: String): Boolean = extensionOf(name) in AUDIO_EXTENSIONS

private fun isVideoFile(name: String): Boolean = extensionOf(name) in VIDEO_EXTENSIONS

private fun isPlayableMedia(name: String): Boolean = isAudioFile(name) || isVideoFile(name)

private fun formatFileSize(size: Long): String {
    if (size <= 0L) return ""
    return when {
        size >= 1024L * 1024L * 1024L ->
            String.format(Locale.ROOT, "%.1f GB", size / (1024.0 * 1024.0 * 1024.0))
        size >= 1024L * 1024L ->
            String.format(Locale.ROOT, "%.1f MB", size / (1024.0 * 1024.0))
        else ->
            String.format(Locale.ROOT, "%.0f KB", size / 1024.0)
    }
}

/**
 * 图书馆页的 FTP 服务器浏览器。
 */
@Composable
fun FtpLibraryPage(offlineMode: Boolean = false) {
    val vm: FtpViewModel = viewModel()
    val ui by vm.uiState.collectAsState()

    if (!ui.configured || ui.editingConfig) {
        FtpConfigForm(
            vm = vm,
            config = ui.config,
            saving = ui.savingConfig,
            error = ui.configError,
            canClear = ui.configured,
            onCancel = { vm.setEditingConfig(false) },
            onClear = vm::clearConfig
        )
        return
    }

    val miniPlayerHeight = LocalMiniPlayerHeight.current
    Column(Modifier.fillMaxSize()) {
        FtpBrowserHeader(
            path = ui.currentPath,
            loading = ui.loading || ui.scanning,
            onUp = vm::goUp,
            onRefresh = vm::refresh,
            onScan = vm::scanMedia,
            onShowBrowse = vm::showBrowse,
            showingScanResults = ui.showMediaResults,
            onEditConfig = { vm.setEditingConfig(true) }
        )
        if (ui.showMediaResults) {
            val scanErrorMessage = ui.scanError
            when {
                ui.scanning -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.ftp_scanning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                scanErrorMessage != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = scanErrorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }

                ui.mediaFiles.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.ftp_scan_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            end = 8.dp,
                            top = 8.dp,
                            bottom = 8.dp + miniPlayerHeight
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        item(key = "ftp_scan_header") {
                            Text(
                                text = stringResource(R.string.ftp_scan_results, ui.mediaFiles.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                        items(ui.mediaFiles, key = { it.path }) { entry ->
                            FtpEntryRow(
                                entry = entry,
                                onClick = {
                                    if (!entry.isDirectory && isPlayableMedia(entry.name)) {
                                        vm.downloadAndPlay(entry)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        } else {
            when {
            ui.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.ftp_connecting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            else -> {
                val errorMessage = ui.error
                if (errorMessage != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = errorMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 32.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = vm::refresh) {
                                Text(stringResource(R.string.ftp_refresh))
                            }
                        }
                    }
                } else if (ui.entries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.ftp_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            end = 8.dp,
                            top = 8.dp,
                            bottom = 8.dp + miniPlayerHeight
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(ui.entries, key = { it.path }) { entry ->
                            FtpEntryRow(
                                entry = entry,
                                onClick = {
                                    if (entry.isDirectory) {
                                        vm.enterDirectory(entry)
                                    } else if (isPlayableMedia(entry.name)) {
                                        vm.downloadAndPlay(entry)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    }

    ui.downloading?.let { download ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.ftp_downloading)) },
            text = {
                Column {
                    Text(
                        text = download.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { download.fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = formatDownloadPercent(download),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {}
        )
    }
}

private fun formatDownloadPercent(download: moe.ouom.neriplayer.ui.viewmodel.ftp.FtpDownloadState): String {
    if (download.totalBytes <= 0L) return formatFileSize(download.bytesDownloaded)
    return String.format(
        Locale.ROOT,
        "%s / %s (%.0f%%)",
        formatFileSize(download.bytesDownloaded),
        formatFileSize(download.totalBytes),
        download.fraction * 100f
    )
}

@Composable
private fun FtpBrowserHeader(
    path: String,
    loading: Boolean,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onScan: () -> Unit,
    onShowBrowse: () -> Unit,
    showingScanResults: Boolean,
    onEditConfig: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = if (showingScanResults) onShowBrowse else onUp,
            enabled = !loading && (showingScanResults || path != "/")
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = if (showingScanResults) {
                    stringResource(R.string.ftp_scan_back)
                } else {
                    stringResource(R.string.ftp_go_up)
                }
            )
        }
        Text(
            text = if (showingScanResults) {
                stringResource(R.string.ftp_scan_results_title)
            } else {
                path
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!showingScanResults) {
            IconButton(onClick = onScan, enabled = !loading) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = stringResource(R.string.ftp_scan)
                )
            }
        }
        IconButton(onClick = onRefresh, enabled = !loading) {
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = stringResource(R.string.ftp_refresh)
            )
        }
        IconButton(onClick = onEditConfig) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.ftp_edit_config)
            )
        }
    }
}

@Composable
private fun FtpEntryRow(
    entry: FtpEntry,
    onClick: () -> Unit
) {
    val playable = entry.isDirectory || isPlayableMedia(entry.name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = playable, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when {
                entry.isDirectory -> Icons.Filled.Folder
                isVideoFile(entry.name) -> Icons.Outlined.VideoFile
                isAudioFile(entry.name) -> Icons.Outlined.AudioFile
                else -> Icons.Outlined.MusicNote
            },
            contentDescription = null,
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else if (playable) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            },
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (playable) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                }
            )
            if (!entry.isDirectory) {
                Text(
                    text = formatFileSize(entry.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FtpConfigForm(
    vm: FtpViewModel,
    config: moe.ouom.neriplayer.data.ftp.FtpServerConfig,
    saving: Boolean,
    error: String?,
    canClear: Boolean,
    onCancel: () -> Unit,
    onClear: () -> Unit
) {
    var host by remember { mutableStateOf(config.host) }
    var port by remember { mutableStateOf(config.port.toString()) }
    var username by remember { mutableStateOf(config.username) }
    var password by remember { mutableStateOf(config.password) }
    var basePath by remember { mutableStateOf(config.basePath) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.ftp_config_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (canClear) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.ftp_clear_config))
                }
            }
        }
        Text(
            text = stringResource(R.string.ftp_config_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text(stringResource(R.string.ftp_host)) },
            placeholder = { Text(stringResource(R.string.ftp_host_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text(stringResource(R.string.ftp_port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.ftp_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.ftp_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = basePath,
            onValueChange = { basePath = it },
            label = { Text(stringResource(R.string.ftp_base_path)) },
            placeholder = { Text(stringResource(R.string.ftp_base_path_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Button(
            onClick = {
                vm.saveConfig(
                    host = host,
                    port = port.toIntOrNull() ?: 21,
                    username = username,
                    password = password,
                    basePath = basePath
                )
            },
            enabled = !saving && host.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.ftp_save_and_connect))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (canClear) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
            Text(
                text = stringResource(R.string.ftp_save_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}



