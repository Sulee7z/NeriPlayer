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
import moe.ouom.neriplayer.data.model.SongItem

private const val NETEASE_ALBUM_PREFIX = "Netease"

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

private fun String?.isNeteaseCoverUrlForIdentity(): Boolean {
    if (this.isNullOrBlank()) return false
    return contains("music.126.net", ignoreCase = true)
}
