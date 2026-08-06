package moe.ouom.neriplayer.data.netease

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
 * File: moe.ouom.neriplayer.data.netease/NeteaseSongIdentity
 */

import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private const val NETEASE_ALBUM_PREFIX = "Netease"
private const val TAG = "NeteaseSongIdentity"

/**
 * 判断一首 [SongItem] 是否可以确定地映射到一个网易云音乐曲目 ID, 并返回该 ID
 *
 * 仅在能够比较有把握地确认这首歌确实来自网易云音乐时才返回非空值, 用于
 * "听歌记录上报" 与 "本地红心自动同步到网易云我喜欢" 等只应作用于网易云歌曲的场景
 *
 * 判定优先级:
 * 1. 专辑字段带有 Netease 来源前缀 (歌单/搜索等页面播放的网易云歌曲)
 * 2. 歌词匹配来源明确是网易云, 且记录了匹配到的网易云歌曲 ID
 * 3. 封面地址指向网易云 CDN (music.126.net), 此时沿用 song.id 作为网易云曲目 ID
 */
fun resolveNeteaseSongIdOrNull(song: SongItem): Long? {
    val songId = song.id.takeIf { it > 0 } ?: return null
    if (song.album.startsWith(NETEASE_ALBUM_PREFIX)) {
        return songId
    }
    if (song.matchedLyricSource == MusicPlatform.CLOUD_MUSIC) {
        val matched = song.matchedSongId?.toLongOrNull()
        if (matched != null && matched > 0) return matched
    }
    if (song.coverUrl.isNeteaseCoverUrlForIdentity() || song.originalCoverUrl.isNeteaseCoverUrlForIdentity()) {
        return songId
    }
    return null
}

/**
 * 第三方音源(QQ/酷狗/酷我/B站等)歌曲的网易云 ID 转换:
 * 用歌名+歌手在网易云搜索, 匹配到真实曲目 ID 后用于听歌记录上报。
 *
 * 结果按 "歌名|歌手" 做内存缓存, 避免每次播放都重新搜索。
 */
private val searchIdCache = ConcurrentHashMap<String, Long?>()

suspend fun resolveNeteaseSongIdWithSearch(song: SongItem): Long? {
    val name = song.name.trim().takeIf { it.isNotBlank() } ?: return null
    val artist = song.artist.trim()
    val cacheKey = "$name|$artist"
    searchIdCache[cacheKey]?.let { return it }
    val resolved = runCatching {
        val raw = AppContainer.neteaseClient.searchSongsCancellable(
            keyword = if (artist.isNotBlank()) "$name $artist" else name,
            limit = 10,
            type = 1,
            usePersistedCookies = true
        )
        parseSearchSongId(raw, name, artist)
    }.getOrElse { error ->
        NPLogger.d(TAG, "搜索转换网易云 ID 失败($name - $artist): ${error.message}")
        null
    }
    if (searchIdCache.size >= 512) {
        searchIdCache.clear()
    }
    searchIdCache[cacheKey] = resolved
    return resolved
}

private fun parseSearchSongId(raw: String, name: String, artist: String): Long? {
    val root = JSONObject(raw)
    if (root.optInt("code") != 200) return null
    val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return null
    val normName = normalizeForMatch(name)
    val normArtist = normalizeForMatch(artist)
    var firstWithName: Long? = null
    for (i in 0 until songs.length()) {
        val song = songs.optJSONObject(i) ?: continue
        val id = song.optLong("id").takeIf { it > 0L } ?: continue
        val songName = normalizeForMatch(song.optString("name"))
        val artistNames = buildSet {
            song.optJSONArray("ar")?.let { ar ->
                for (j in 0 until ar.length()) {
                    ar.optJSONObject(j)?.optString("name")?.let { add(normalizeForMatch(it)) }
                }
            }
        }
        if (songName != normName) continue
        if (firstWithName == null) firstWithName = id
        if (normArtist.isBlank() || artistNames.isEmpty() ||
            artistNames.any { it == normArtist } ||
            artistNames.any { it.isNotBlank() && normArtist.isNotBlank() && (it.contains(normArtist) || normArtist.contains(it)) }
        ) {
            return id
        }
    }
    return firstWithName
}

private fun normalizeForMatch(value: String): String {
    return value.trim()
        .lowercase()
        .replace(Regex("[\\s·•\\-—_()（）【】\\[\\]\"'`~!@#$%^&*+=.,;:、，。！？/\\\\]"), "")
}

private fun String?.isNeteaseCoverUrlForIdentity(): Boolean {
    if (this.isNullOrBlank()) return false
    return contains("music.126.net", ignoreCase = true)
}
