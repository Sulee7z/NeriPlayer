package moe.ouom.neriplayer.core.api.search

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
 * File: moe.ouom.neriplayer.core.api.search/BiliSearchApi
 * Created: 2026/8/6
 *
 * 把 Bilibili 视频搜索包装成 SearchApi, 供"获取歌曲信息/匹配歌曲"面板使用。
 * 视频标题/UP主 作为歌曲名/歌手候选, BVID 作为 ID。
 * 注意: B 站没有独立的歌词接口, getSongInfo 不返回歌词。
 */

import android.annotation.SuppressLint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.logging.NPLogger

private const val TAG = "BiliSearchApi"

class BiliSearchApi(
    private val client: BiliClient
) : SearchApi {

    override suspend fun search(keyword: String, page: Int): List<SongSearchInfo> {
        return withContext(Dispatchers.IO) {
            try {
                client.searchVideos(keyword = keyword, page = page, order = "totalrank")
                    .items
                    .map { item ->
                        SongSearchInfo(
                            id = item.bvid,
                            songName = item.titlePlain.ifBlank { item.titleHtml },
                            singer = item.author,
                            duration = formatDuration(item.durationSec),
                            source = MusicPlatform.BILIBILI,
                            albumName = null,
                            coverUrl = item.coverUrl
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.e(TAG, "bilibili search failed: $keyword", e)
                throw e
            }
        }
    }

    override suspend fun getSongInfo(id: String): SongDetails {
        return withContext(Dispatchers.IO) {
            val info = client.getVideoBasicInfoByBvid(id)
            SongDetails(
                id = info.bvid,
                songName = info.title,
                singer = info.ownerName,
                album = "",
                coverUrl = info.coverUrl,
                lyric = null,
                translatedLyric = null
            )
        }
    }

    @SuppressLint("DefaultLocale")
    private fun formatDuration(totalSeconds: Int): String {
        if (totalSeconds <= 0) return "--:--"
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}
