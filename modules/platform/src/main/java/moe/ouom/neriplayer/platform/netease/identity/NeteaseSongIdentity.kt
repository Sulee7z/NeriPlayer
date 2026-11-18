package moe.ouom.neriplayer.platform.netease.identity

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Account mutations must never infer a song ID from its cover URL. */
fun resolveNeteaseSongIdOrNull(song: SongItem): Long? {
    if (song.channelId.equals("netease", ignoreCase = true)) {
        return song.audioId?.toLongOrNull()?.takeIf { it > 0L }
            ?: song.id.takeIf { it > 0L }
    }
    if (song.matchedLyricSource == MusicPlatform.CLOUD_MUSIC) {
        song.matchedSongId?.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
    }
    if (song.channelId.isNullOrBlank() && song.album == "Netease") {
        return song.id.takeIf { it > 0L }
    }
    return null
}

class NeteaseSongIdentityResolver(
    private val client: NeteaseClient,
    private val now: () -> Long = SystemClock::elapsedRealtime
) {
    private data class Entry(val id: Long?, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    suspend fun resolve(song: SongItem): Long? {
        resolveNeteaseSongIdOrNull(song)?.let { return it }
        val name = song.originalName?.takeIf { it.isNotBlank() } ?: song.name
        val artist = song.originalArtist?.takeIf { it.isNotBlank() } ?: song.artist
        if (name.isBlank() || artist.isBlank()) return null
        val key = "${normalize(name)}|${normalize(artist)}|${song.durationMs}"
        cache[key]?.takeIf { now() < it.expiresAt }?.let { return it.id }
        val raw = try {
            client.searchSongsCancellable("$name $artist", limit = 10, type = 1, usePersistedCookies = true)
        } catch (error: CancellationException) {
            throw error
        }
        val id = matchNeteaseSearchSongId(raw, name, artist, song.durationMs)
        if (cache.size >= 512) cache.clear()
        cache[key] = Entry(id, now() + if (id == null) 60_000L else 3_600_000L)
        return id
    }
}

internal fun matchNeteaseSearchSongId(raw: String, name: String, artist: String, durationMs: Long): Long? {
    val root = JSONObject(raw)
    if (root.optInt("code") != 200) return null
    val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return null
    val expectedArtists = artist.split(Regex("[、,，/&;；]|\\s+[xX]\\s+"))
        .map(::normalize).filter(String::isNotEmpty).toSet()
    if (expectedArtists.isEmpty()) return null
    for (index in 0 until songs.length()) {
        val song = songs.optJSONObject(index) ?: continue
        val id = song.optLong("id").takeIf { it > 0L } ?: continue
        if (normalize(song.optString("name")) != normalize(name)) continue
        val artists = song.optJSONArray("ar") ?: song.optJSONArray("artists") ?: continue
        val actualArtists = (0 until artists.length()).mapNotNull {
            artists.optJSONObject(it)?.optString("name")?.let(::normalize)
        }.toSet()
        if (!actualArtists.containsAll(expectedArtists)) continue
        val actualDuration = song.optLong("dt", song.optLong("duration"))
        if (durationMs > 0L && actualDuration > 0L && abs(actualDuration - durationMs) > 10_000L) continue
        return id
    }
    return null
}

private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
    .replace(Regex("[\\s·•_\\-—()（）【】\\[\\]]"), "")
