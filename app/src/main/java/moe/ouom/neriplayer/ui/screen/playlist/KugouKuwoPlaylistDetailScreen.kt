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
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.kugou.KugouPlaylistDetail
import moe.ouom.neriplayer.core.api.kuwo.KuwoPlaylistDetail
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary

enum class KugouKuwoPlatform {
    KUGOU,
    KUWO
}

private sealed interface KKPlaylistUiState {
    data object Loading : KKPlaylistUiState
    data class Ready(
        val title: String,
        val coverUrl: String?,
        val songs: List<SongItem>
    ) : KKPlaylistUiState
    data class Error(val message: String) : KKPlaylistUiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KugouKuwoPlaylistDetailScreen(
    platform: KugouKuwoPlatform,
    playlist: PlaylistSummary,
    onBack: () -> Unit,
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    offlineMode: Boolean = false
) {
    val offlineError = stringResource(R.string.qq_playlist_offline_unavailable)
    val state by produceState<KKPlaylistUiState>(
        initialValue = KKPlaylistUiState.Loading,
        key1 = playlist.id
    ) {
        value = if (offlineMode) {
            KKPlaylistUiState.Error(offlineError)
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    when (platform) {
                        KugouKuwoPlatform.KUGOU -> {
                            val detail: KugouPlaylistDetail =
                                AppContainer.kugouApi.getPlaylistDetail(playlist.id)
                            KKPlaylistUiState.Ready(
                                title = detail.title,
                                coverUrl = detail.coverUrl,
                                songs = detail.songs.map { song ->
                                    val mid = song.hash
                                    SongItem(
                                        id = mid.hashCode().toLong() and 0x7fffffffL,
                                        name = song.name,
                                        artist = song.artist,
                                        album = if (song.albumName.isNullOrBlank()) {
                                            "Kugou"
                                        } else {
                                            "Kugou${song.albumName}"
                                        },
                                        albumId = 0L,
                                        durationMs = song.durationMs,
                                        coverUrl = null,
                                        audioId = mid,
                                        sourceStableKey = "kugou:$mid"
                                    )
                                }
                            )
                        }

                        KugouKuwoPlatform.KUWO -> {
                            val detail: KuwoPlaylistDetail =
                                AppContainer.kuwoApi.getPlaylistDetail(playlist.id)
                            KKPlaylistUiState.Ready(
                                title = detail.title,
                                coverUrl = detail.coverUrl,
                                songs = detail.songs.map { song ->
                                    val mid = song.mid
                                    SongItem(
                                        id = mid.hashCode().toLong() and 0x7fffffffL,
                                        name = song.name,
                                        artist = song.artist,
                                        album = if (song.albumName.isNullOrBlank()) {
                                            "Kuwo"
                                        } else {
                                            "Kuwo${song.albumName}"
                                        },
                                        albumId = 0L,
                                        durationMs = song.durationMs,
                                        coverUrl = null,
                                        audioId = mid,
                                        sourceStableKey = "kuwo:$mid"
                                    )
                                }
                            )
                        }
                    }
                }.fold(
                    onSuccess = { it },
                    onFailure = { KKPlaylistUiState.Error(it.message ?: "unknown") }
                )
            }
        }
    }
    val ready = state as? KKPlaylistUiState.Ready
    val songs = ready?.songs.orEmpty()

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = { Text(ready?.title ?: playlist.name) },
                navigationIcon = {
                    HapticIconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    HapticIconButton(
                        enabled = songs.isNotEmpty(),
                        onClick = { onSongClick(songs, 0) }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.PlaylistPlay,
                            contentDescription = stringResource(R.string.cd_play_all)
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
            is KKPlaylistUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is KKPlaylistUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = current.message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            }

            is KKPlaylistUiState.Ready -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        end = 8.dp,
                        top = 8.dp,
                        bottom = 8.dp + LocalMiniPlayerHeight.current
                    )
                ) {
                    item(key = "kk_playlist_header") {
                        KKPlaylistHeader(
                            title = current.title,
                            coverUrl = current.coverUrl ?: playlist.picUrl,
                            offlineMode = offlineMode
                        )
                    }

                    if (songs.isEmpty()) {
                        item(key = "kk_playlist_empty") {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(280.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.qq_playlist_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        itemsIndexed(
                            songs,
                            key = { index, song -> "$index|${song.stableKey()}" }
                        ) { index, song ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSongClick(songs, index) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(32.dp)
                                )
                                Icon(
                                    Icons.Outlined.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = song.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (song.artist.isNotBlank()) {
                                        Text(
                                            text = song.artist,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KKPlaylistHeader(
    title: String,
    coverUrl: String,
    offlineMode: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            if (coverUrl.isNotBlank() && !offlineMode) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
