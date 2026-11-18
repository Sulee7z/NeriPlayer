package moe.ouom.neriplayer.ui.screen.tab.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.common.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary

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
        if (offlineMode) return@LaunchedEffect
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
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp + LocalMiniPlayerHeight.current)) {
        if (loading) item { CircularProgressIndicator() }
        if (error != null) item {
            Text(error.orEmpty())
            TextButton(onClick = { retry++ }) { Text(stringResource(R.string.ftp_refresh)) }
        }
        items(playlists, key = { "$platform:${it.id}" }) { playlist ->
            ListItem(headlineContent = { Text(playlist.name) },
                supportingContent = { Text("${playlist.trackCount}") },
                modifier = Modifier.clickable(enabled = !offlineMode) { onClick(playlist) })
        }
    }
}
