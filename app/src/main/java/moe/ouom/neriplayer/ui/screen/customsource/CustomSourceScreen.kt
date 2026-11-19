package moe.ouom.neriplayer.ui.screen.customsource

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
 * File: moe.ouom.neriplayer.ui.screen.customsource/CustomSourceScreen
 * Created: 2026/7/26
 *
 * 自定义音源(兼容洛雪音乐 LX 脚本)管理界面。
 */

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsOutlinedButton as OutlinedButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField as OutlinedTextField
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch as Switch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog as AlertDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsDetailScaffold
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsHeader
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import moe.ouom.neriplayer.common.R
import moe.ouom.neriplayer.platform.customsource.CustomAudioSource
import moe.ouom.neriplayer.platform.customsource.CustomSourceMetadataParser
import moe.ouom.neriplayer.ui.viewmodel.customsource.CustomSourceViewModel
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.testTag

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomSourceScreen(
    onBack: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    sourceViewModel: CustomSourceViewModel? = null,
    showBackButton: Boolean = true
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val vm: CustomSourceViewModel = sourceViewModel ?: viewModel(
        factory = viewModelFactory {
            initializer {
                CustomSourceViewModel(appContext as Application)
            }
        }
    )
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val settings = if (sourceViewModel == null) AppContainer.settingsRepo else null
    val bypass by (settings?.customSourceBypassProxyFlow ?: kotlinx.coroutines.flow.flowOf(true))
        .collectAsStateWithLifecycle(true)
    val alwaysProxy by (settings?.customSourceAlwaysProxyFlow ?: kotlinx.coroutines.flow.flowOf(false))
        .collectAsStateWithLifecycle(false)
    val scope = rememberCoroutineScope()
    var showPasteDialog by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }

    // 结果/诊断信息用对话框显示(可多行,不会被截断)
    if (ui.message != null) {
        AlertDialog(
            onDismissRequest = { vm.consumeMessage() },
            confirmButton = {
                OutlinedButton(onClick = { vm.consumeMessage() }) {
                    Text(stringResource(R.string.custom_source_ok))
                }
            },
            text = { Text(ui.message ?: "") }
        )
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            vm.importScriptFromUri(uri)
        }
    }

    MiuixSettingsDetailScaffold(
        modifier = Modifier.fillMaxSize().testTag("custom-source-manager"),
        title = stringResource(R.string.custom_source_title),
        onBack = onBack,
        listState = listState,
        topAppBarState = rememberTopAppBarState(),
        showBackButton = showBackButton
    ) {
            item {
                MiuixSettingsHeader(
                    icon = SettingsPage.CustomSource.icon,
                    title = stringResource(R.string.custom_source_title),
                    description = stringResource(R.string.custom_source_desc),
                    page = SettingsPage.CustomSource
                )
            }

            // 导入按钮
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { filePicker.launch("*/*") },
                        enabled = !ui.busy
                    ) { Text(stringResource(R.string.custom_source_import_file)) }
                    OutlinedButton(
                        onClick = { showPasteDialog = true },
                        enabled = !ui.busy
                    ) { Text(stringResource(R.string.custom_source_import_paste)) }
                    OutlinedButton(
                        onClick = { showUrlDialog = true },
                        enabled = !ui.busy
                    ) { Text(stringResource(R.string.custom_source_import_url)) }
                }
            }
            if (ui.busy) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = stringResource(R.string.custom_source_importing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 优先模式开关
            item {
                MiuixSettingsSectionCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.custom_source_priority_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                stringResource(R.string.custom_source_priority_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(R.string.custom_source_priority_order),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = ui.priorityMode,
                            onCheckedChange = { vm.setPriorityMode(it) },
                            enabled = !ui.busy
                        )
                    }
                }
            }

            item(key = "source:network") {
                MiuixSettingsSectionCard {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_custom_source_bypass_proxy)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        trailingContent = { Switch(checked = bypass,
                            onCheckedChange = { scope.launch { settings?.setCustomSourceBypassProxy(it) } },
                            enabled = !ui.busy) }
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_custom_source_always_proxy)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        trailingContent = { Switch(checked = alwaysProxy,
                            onCheckedChange = { scope.launch { settings?.setCustomSourceAlwaysProxy(it) } },
                            enabled = !ui.busy) }
                    )
                }
            }

            if (ui.sources.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.custom_source_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            } else {
                itemsIndexed(ui.sources, key = { _, it -> it.id }) { index, source ->
                    SourceCard(
                        source = source,
                        canMoveUp = index > 0,
                        canMoveDown = index < ui.sources.lastIndex,
                        onEnabledChange = { enabled -> vm.setEnabled(source.id, enabled) },
                        onRename = { name -> vm.rename(source.id, name) },
                        onMoveUp = { vm.moveUp(source.id) },
                        onMoveDown = { vm.moveDown(source.id) },
                        onTest = { vm.testSource(source.id) },
                        onDelete = { vm.delete(source.id) },
                        busy = ui.busy
                    )
                }
            }
    }

    if (showPasteDialog) {
        PasteScriptDialog(
            onDismiss = { showPasteDialog = false },
            onConfirm = { text ->
                showPasteDialog = false
                vm.importScript(text)
            }
        )
    }

    if (showUrlDialog) {
        UrlImportDialog(
            busy = ui.busy,
            onDismiss = { showUrlDialog = false },
            onConfirm = { url ->
                showUrlDialog = false
                vm.importScriptFromUrl(url)
            }
        )
    }
}

@Composable
private fun SourceCard(
    source: CustomAudioSource,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
    busy: Boolean
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    MiuixSettingsSectionCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        source.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val meta = buildString {
                        if (source.version.isNotBlank()) append("v${source.version}")
                        if (source.author.isNotBlank()) append(" · ${source.author}")
                        val platforms = source.supportedSources.keys.joinToString(", ")
                        if (platforms.isNotBlank()) append(" · $platforms")
                    }
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { showRenameDialog = true },
                    enabled = !busy
                ) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.custom_source_rename)
                    )
                }
                Switch(
                    checked = source.enabled,
                    onCheckedChange = { onEnabledChange(it) },
                    enabled = !busy
                )
                IconButton(onClick = onDelete, enabled = !busy) {
                    Icon(Icons.Outlined.Delete, contentDescription = null)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onMoveUp, enabled = canMoveUp && !busy) {
                    Icon(
                        Icons.Outlined.ArrowUpward,
                        contentDescription = stringResource(R.string.custom_source_move_up)
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown && !busy) {
                    Icon(
                        Icons.Outlined.ArrowDownward,
                        contentDescription = stringResource(R.string.custom_source_move_down)
                    )
                }
                TextButton(
                    onClick = onTest,
                    enabled = !busy
                ) {
                    Text(stringResource(R.string.custom_source_test_source))
                }
            }
        }
    }

    if (showRenameDialog) {
        var name by remember { mutableStateOf(source.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.custom_source_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.custom_source_rename_hint)) }
                )
            },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        showRenameDialog = false
                        onRename(name)
                    },
                    enabled = name.trim().isNotBlank()
                ) { Text(stringResource(R.string.custom_source_import_confirm)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.custom_source_cancel))
                }
            }
        )
    }
}

@Composable
private fun PasteScriptDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val meta = remember(text) {
        CustomSourceMetadataParser.parse(text)
    }
    val looksLikeScript = remember(text) { CustomSourceMetadataParser.isImportable(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_source_import_paste)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    singleLine = false,
                    placeholder = { Text("// LX Music source script...") }
                )
                if (text.isNotBlank()) {
                    Text(
                        text = buildString {
                            append(meta.name)
                            if (meta.version.isNotBlank()) append(" · v${meta.version}")
                            if (meta.author.isNotBlank()) append(" · ${meta.author}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (looksLikeScript) {
                            stringResource(R.string.custom_source_validate_ok)
                        } else {
                            stringResource(R.string.custom_source_validate_warn)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (looksLikeScript) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank() && looksLikeScript
            ) { Text(stringResource(R.string.custom_source_import_confirm)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.custom_source_cancel))
            }
        }
    )
}

@Composable
private fun UrlImportDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    val urlValid = url.trim().startsWith("http://") || url.trim().startsWith("https://")
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.custom_source_import_url)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !busy,
                    placeholder = { Text("https://example.com/source.js") }
                )
                Text(
                    stringResource(R.string.custom_source_import_url_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.custom_source_importing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = { onConfirm(url) },
                enabled = urlValid && !busy
            ) { Text(stringResource(R.string.custom_source_import_confirm)) }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                enabled = !busy
            ) { Text(stringResource(R.string.custom_source_cancel)) }
        }
    )
}
