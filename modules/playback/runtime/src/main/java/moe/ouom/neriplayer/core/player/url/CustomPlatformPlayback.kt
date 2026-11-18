package moe.ouom.neriplayer.core.player.url

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.core.player.quality.effectiveNeteaseQuality
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.model.playback.SongUrlResult

internal fun customPlatformKey(song: SongItem): String? = when {
    song.channelId == "qq" || song.album.startsWith("QQMusic") -> "tx"
    song.channelId == "kugou" || song.album.startsWith("Kugou") -> "kg"
    song.channelId == "kuwo" || song.album.startsWith("Kuwo") -> "kw"
    else -> null
}

internal suspend fun PlayerManager.resolveCustomPlatformAudio(
    song: SongItem,
    suppressError: Boolean,
    sideEffects: RefreshResolverSideEffects,
    forceRefresh: Boolean = false
): SongUrlResult {
    val platform = customPlatformKey(song) ?: return SongUrlResult.Failure
    val repositories = PlayerDependencies.repositories
    val id = song.audioId?.takeIf { it.isNotBlank() } ?: return SongUrlResult.Failure
    try {
        val custom = repositories.customSourceManager?.resolveSongByPlatform(
            song, platform, id, qualityKey = effectiveNeteaseQuality(), forceRefresh = forceRefresh
        )
        val url = custom ?: when (platform) {
            "tx" -> repositories.qqMusicApi?.resolveSongPlayUrl(id, forceRefresh)
            "kg" -> repositories.kugouApi?.resolvePlayUrl(id)
            "kw" -> repositories.kuwoApi?.resolvePlayUrl(id)
            else -> null
        }
        if (!url.isNullOrBlank()) return customAudioResult(song, url)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        NPLogger.w("NERI-PlayerManager", "自有平台音源解析失败: $platform", error)
    }
    if (!suppressError) sideEffects.emitError {
        postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(CoreCommonR.string.error_no_play_url)))
    }
    return SongUrlResult.Failure
}

internal suspend fun PlayerManager.resolveNeteaseCustomAudio(song: SongItem, forceRefresh: Boolean): SongUrlResult.Success? {
    val manager = PlayerDependencies.repositories.customSourceManager ?: return null
    return try {
        val url = manager.resolveNeteaseUrl(song, effectiveNeteaseQuality(), forceRefresh)
        url?.let { customAudioResult(song, it) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        NPLogger.w("NERI-PlayerManager", "自定义音源解析失败，继续使用上游回退", error)
        null
    }
}

private fun customAudioResult(song: SongItem, url: String): SongUrlResult.Success {
    val identity = "${song.channelId}:${song.audioId}:${song.id}:$url"
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return SongUrlResult.Success(url, durationMs = song.durationMs.takeIf { it > 0L },
        cacheKeyOverride = "custom-source-$digest", representationIdentity = "custom-source-$digest")
}
