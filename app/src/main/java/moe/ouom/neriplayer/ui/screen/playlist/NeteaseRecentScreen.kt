package moe.ouom.neriplayer.ui.screen.playlist

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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 网易云云端听歌记录的一条 */
private data class NeteaseRecentRecord(
    val songId: Long,
    val name: String,
    val artists: String,
    val albumName: String,
    val coverUrl: String?,
    val playTimeMs: Long
)

private sealed interface NeteaseRecentUiState {
    data object Loading : NeteaseRecentUiState
    data object NeedLogin : NeteaseRecentUiState
    data class Ready(val records: List<NeteaseRecentRecord>) : NeteaseRecentUiState
    data class Error(val message: String) : NeteaseRecentUiState
}

private fun parseRecentRecords(raw: String): List<NeteaseRecentRecord> {
    val root = JSONObject(raw)
    if (root.optInt("code") != 200) return emptyList()
    val list = root.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
    val out = ArrayList<NeteaseRecentRecord>(list.length())
    for (i in 0 until list.length()) {
        val item = list.optJSONObject(i) ?: continue
        val song = item.optJSONObject("data") ?: continue
        val id = song.optLong("id").takeIf { it > 0L } ?: continue
        val name = song.optString("name").takeIf { it.isNotBlank() } ?: continue
        val artists = buildString {
            song.optJSONArray("ar")?.let { ar ->
                for (j in 0 until ar.length()) {
                    val artistName = ar.optJSONObject(j)?.optString("name").orEmpty()
                    if (artistName.isNotBlank()) {
                        if (isNotEmpty()) append(" / ")
                        append(artistName)
                    }
                }
            }
        }
        val album = song.optJSONObject("al")
        out.add(
            NeteaseRecentRecord(
                songId = id,
                name = name,
                artists = artists,
                albumName = album?.optString("name").orEmpty(),
                coverUrl = album?.optString("picUrl")?.takeIf { it.isNotBlank() },
                playTimeMs = item.optLong("playTime")
            )
        )
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeteaseRecentScreen(
    onBack: () -> Unit,
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    offlineMode: Boolean = false
) {
    val offlineError = stringResource(R.string.qq_playlist_offline_unavailable)
    val needLoginText = stringResource(R.string.netease_recent_need_login)
    val state by produceState<NeteaseRecentUiState>(
        initialValue = NeteaseRecentUiState.Loading
    ) {
        value = if (offlineMode) {
            NeteaseRecentUiState.Error(offlineError)
        } else {
            withContext(Dispatchers.IO) {
                val client = AppContainer.neteaseClient
                if (!client.hasLogin()) {
                    NeteaseRecentUiState.NeedLogin
                } else {
                    runCatching {
                        val raw = client.getRecentPlayRecords(limit = 100)
                        val records = parseRecentRecords(raw)
                        if (records.isEmpty()) {
                            NeteaseRecentUiState.Error(
                                "code=" + JSONObject(raw).optInt("code") + ", 无记录"
                            )
                        } else {
                            NeteaseRecentUiState.Ready(records)
                        }
                    }.getOrElse { error ->
                        NeteaseRecentUiState.Error(error.message ?: "unknown")
                    }
                }
            }
        }
    }
    val records = (state as? NeteaseRecentUiState.Ready)?.records.orEmpty()
    val songs = remember(records) {
        records.map { record ->
            SongItem(
                id = record.songId,
                name = record.name,
                artist = record.artists,
                album = if (record.albumName.isBlank()) {
                    "Netease"
                } else {
                    "Netease${record.albumName}"
                },
                albumId = 0L,
                durationMs = 0L,
                coverUrl = record.coverUrl,
                audioId = record.songId.toString(),
                sourceStableKey = "netease:${record.songId}"
            )
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.netease_recent_title)) },
                navigationIcon = {
                    HapticIconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        when (val current = state) {
            is NeteaseRecentUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is NeteaseRecentUiState.NeedLogin -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = needLoginText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            }

            is NeteaseRecentUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = current.message,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = onBack) {
                            Text(stringResource(R.string.action_back))
                        }
                    }
                }
            }

            is NeteaseRecentUiState.Ready -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        end = 8.dp,
                        top = 8.dp,
                        bottom = 8.dp + LocalMiniPlayerHeight.current
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(
                        records,
                        key = { index, record -> "$index|${record.songId}" }
                    ) { index, record ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSongClick(songs, index) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (record.coverUrl != null) {
                                    AsyncImage(
                                        model = record.coverUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        Icons.Outlined.MusicNote,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = record.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (record.artists.isNotBlank()) {
                                    Text(
                                        text = record.artists,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (record.playTimeMs > 0L) {
                                Text(
                                    text = formatRecordTime(record.playTimeMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatRecordTime(timeMs: Long): String {
    return runCatching {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timeMs))
    }.getOrDefault("")
}
