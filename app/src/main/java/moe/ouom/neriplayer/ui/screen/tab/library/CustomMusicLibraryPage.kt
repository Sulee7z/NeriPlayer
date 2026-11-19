package moe.ouom.neriplayer.ui.screen.tab.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.common.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.theme.neriRoundedShape

@Composable
internal fun CustomMusicLibraryPage(
    platform: String,
    listState: LazyListState,
    offlineMode: Boolean,
    onClick: (PlaylistSummary) -> Unit
) {
    val cookiesFlow = when (platform) {
        "qq" -> AppContainer.qqCookieRepo.cookieFlow
        "kugou" -> AppContainer.kugouCookieRepo.cookieFlow
        else -> AppContainer.kuwoCookieRepo.cookieFlow
    }
    val cookies by cookiesFlow.collectAsStateWithLifecycle()
    var retry by remember(platform) { mutableIntStateOf(0) }
    var playlists by remember(platform) { mutableStateOf<List<PlaylistSummary>>(emptyList()) }
    var loading by remember(platform) { mutableStateOf(false) }
    var error by remember(platform) { mutableStateOf<String?>(null) }
    LaunchedEffect(platform, cookies, offlineMode, retry) {
        playlists = emptyList()
        error = null
        if (offlineMode) { loading = false; return@LaunchedEffect }
        loading = true
        error = null
        try {
            playlists = when (platform) {
                "qq" -> {
                    val api = AppContainer.qqMusicApi
                    val own = if (api.hasLogin()) api.getUserPlaylists(cookies["uin"].orEmpty()) else emptyList()
                    (own.ifEmpty { api.getHotPlaylists() }).map {
                        PlaylistSummary(it.dissId, it.title, it.picUrl.orEmpty(), it.listenCount, it.songCount, source = platform)
                    }
                }
                "kugou" -> {
                    val api = AppContainer.kugouApi
                    val own = cookies["userid"]?.let { api.getUserPlaylists(it) }.orEmpty()
                    (own.ifEmpty { api.getHotPlaylists() }).map {
                        PlaylistSummary(it.specialId, it.title, it.picUrl.orEmpty(), it.listenCount, it.songCount, source = platform)
                    }
                }
                else -> {
                    val api = AppContainer.kuwoApi
                    val own = cookies["userid"]?.let { api.getUserPlaylists(it) }.orEmpty()
                    (own.ifEmpty { api.getHotPlaylists() }).map {
                        PlaylistSummary(it.playlistId, it.title, it.picUrl.orEmpty(), it.listenCount, it.songCount, source = platform)
                    }
                }
            }.distinctBy { it.id }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message
        } finally { loading = false }
    }
    CustomMusicLibraryContent(playlists, listState, loading, error, offlineMode, { retry++ }, onClick)
}

/** Use the same artwork, typography, hit area and spacing as the official remote library. */
@Composable
internal fun CustomMusicLibraryContent(
    playlists: List<PlaylistSummary>,
    listState: LazyListState,
    loading: Boolean,
    error: String?,
    offlineMode: Boolean,
    onRetry: () -> Unit,
    onClick: (PlaylistSummary) -> Unit
) {
    val context = LocalContext.current
    val shape = neriRoundedShape(12.dp)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("custom-music-library"),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp,
            bottom = 8.dp + LocalMiniPlayerHeight.current),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (loading) item {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        if (error != null || offlineMode || (!loading && playlists.isEmpty())) item {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error ?: stringResource(if (offlineMode) R.string.qq_playlist_offline_unavailable
                    else R.string.qq_playlist_empty))
                if (!offlineMode) TextButton(onClick = onRetry) { Text(stringResource(R.string.ftp_refresh)) }
            }
        }
        items(playlists, key = { "${it.source}:${it.id}" }) { playlist ->
            NeteasePlaylistRow(playlist = playlist, cardShape = shape, context = context,
                onClick = { if (!offlineMode) onClick(playlist) }, offlineMode = offlineMode)
        }
    }
}
