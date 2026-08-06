package moe.ouom.neriplayer.core.api.kuwo

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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.network.awaitResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "KuwoApi"

private const val KUWO_PC_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

/** 酷我歌单摘要 */
data class KuwoPlaylistSummary(
    val playlistId: Long,
    val title: String,
    val picUrl: String? = null,
    val listenCount: Long = 0L,
    val songCount: Int = 0
)

/** 酷我歌曲信息 */
data class KuwoSong(
    val mid: String,
    val name: String,
    val artist: String,
    val albumName: String? = null,
    val albumId: Long = 0L,
    val durationMs: Long = 0L
)

data class KuwoPlaylistDetail(
    val playlistId: Long,
    val title: String,
    val coverUrl: String?,
    val listenCount: Long,
    val totalSongCount: Int,
    val songs: List<KuwoSong>
)

/**
 * 酷我音乐 API 客户端。
 *
 * - 热门歌单: wapi.kuwo.cn 老接口, 匿名可用
 * - 歌单详情: nplserver.kuwo.cn pl.svc
 * - 播放地址: antiserver.kuwo.cn anti.s (rid=歌曲mid, 无需登录)
 */
class KuwoApi(
    private val client: OkHttpClient = AppContainer.sharedOkHttpClient,
    private val cookieProvider: () -> Map<String, String> = {
        runCatching { AppContainer.kuwoCookieRepo.getCookiesOnce() }.getOrDefault(emptyMap())
    }
) {
    private val playUrlCache = ConcurrentHashMap<String, String>()

    fun hasLogin(): Boolean = cookieProvider().isNotEmpty()

    /** 热门歌单 */
    suspend fun getHotPlaylists(page: Int = 1, count: Int = 30): List<KuwoPlaylistSummary> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("loginUid", "0")
                    .addQueryParameter("loginSid", "0")
                    .addQueryParameter("appUid", "76039576")
                    .addQueryParameter("pn", page.toString())
                    .addQueryParameter("rn", count.toString())
                    .addQueryParameter("order", "hot")
                    .build()
                val responseJson = execute(url.toString())
                val root = JSONObject(responseJson)
                val list = root.optJSONObject("data")
                    ?.optJSONArray("data")
                    ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until list.length()) {
                        val item = list.optJSONObject(i) ?: continue
                        val id = item.optString("id").toLongOrNull() ?: continue
                        add(
                            KuwoPlaylistSummary(
                                playlistId = id,
                                title = item.optString("name"),
                                picUrl = item.optString("img")
                                    .takeIf { it.isNotBlank() }
                                    ?.let { if (it.startsWith("http://")) "https://" + it.removePrefix("http://") else it },
                                listenCount = item.optLong("listencnt"),
                                songCount = item.optInt("total")
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.d(TAG, "热门歌单加载失败: ${e.message}")
                emptyList()
            }
        }
    }

    /** 登录用户的歌单(尽力而为, 需要 kw_token csrf, 失败返回空由调用方回退热门) */
    suspend fun getUserPlaylists(uid: String, count: Int = 30): List<KuwoPlaylistSummary> {
        if (uid.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://www.kuwo.cn/api/www/playlist/getUserPlaylist"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("uid", uid)
                    .addQueryParameter("pn", "1")
                    .addQueryParameter("rn", count.toString())
                    .build()
                val responseJson = execute(url.toString())
                val root = JSONObject(responseJson)
                if (root.optInt("code") != 200) return@withContext emptyList()
                val dataObj = root.optJSONObject("data") ?: return@withContext emptyList()
                val list = dataObj.optJSONArray("list")
                    ?: dataObj.optJSONObject("list")?.optJSONArray("data")
                    ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until list.length()) {
                        val item = list.optJSONObject(i) ?: continue
                        val id = item.optString("id").toLongOrNull()
                            ?: item.optLong("playlistId").takeIf { it > 0L }
                            ?: continue
                        val title = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                        add(
                            KuwoPlaylistSummary(
                                playlistId = id,
                                title = title,
                                picUrl = item.optString("pic")
                                    .takeIf { it.isNotBlank() },
                                listenCount = item.optLong("listencnt"),
                                songCount = item.optInt("total")
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.d(TAG, "我的歌单加载失败: ${e.message}")
                emptyList()
            }
        }
    }

    /** 歌单详情 */
    suspend fun getPlaylistDetail(playlistId: Long): KuwoPlaylistDetail {
        require(playlistId > 0L) { "invalid playlist id" }
        return withContext(Dispatchers.IO) {
            val url = "http://nplserver.kuwo.cn/pl.svc"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("op", "getlistinfo")
                .addQueryParameter("pid", playlistId.toString())
                .addQueryParameter("pn", "0")
                .addQueryParameter("rn", "1000")
                .addQueryParameter("encode", "utf8")
                .addQueryParameter("keyset", "pl2012")
                .addQueryParameter("identity", "kuwo")
                .addQueryParameter("pcmp4", "1")
                .addQueryParameter("vipver", "MUSIC_9.0.5.0_W1")
                .addQueryParameter("newver", "1")
                .build()
            val responseJson = execute(url.toString())
            val root = JSONObject(responseJson)
            if (root.optString("result") != "ok") {
                throw IOException("酷我歌单不存在: $playlistId")
            }
            val musicList = root.optJSONArray("musiclist")
            val songs = ArrayList<KuwoSong>()
            if (musicList != null) {
                for (i in 0 until musicList.length()) {
                    val item = musicList.optJSONObject(i) ?: continue
                    val mid = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    songs.add(
                        KuwoSong(
                            mid = mid,
                            name = item.optString("name"),
                            artist = item.optString("artist"),
                            albumName = item.optString("album").takeIf { it.isNotBlank() },
                            albumId = item.optLong("albumid"),
                            durationMs = item.optLong("duration") * 1000L
                        )
                    )
                }
            }
            KuwoPlaylistDetail(
                playlistId = playlistId,
                title = root.optString("title"),
                coverUrl = root.optString("pic").takeIf { it.isNotBlank() },
                listenCount = root.optLong("playnum"),
                totalSongCount = root.optInt("total"),
                songs = songs
            )
        }
    }

    /**
     * 解析歌曲播放地址(mid)。antiserver 接口无需登录。
     */
    suspend fun resolvePlayUrl(mid: String): String? {
        if (mid.isBlank()) return null
        playUrlCache[mid]?.let { return it }
        val url = withContext(Dispatchers.IO) {
            try {
                val target = "http://antiserver.kuwo.cn/anti.s"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("type", "convert_url")
                    .addQueryParameter("rid", mid)
                    .addQueryParameter("format", "mp3")
                    .addQueryParameter("response", "url")
                    .build()
                val responseJson = execute(target.toString())
                responseJson.trim().takeIf { it.startsWith("http") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.d(TAG, "播放地址解析失败($mid): ${e.message}")
                null
            }
        }
        if (url != null) {
            playUrlCache[mid] = url
        }
        return url
    }

    private fun buildCookieHeader(): String {
        val cookies = cookieProvider()
        if (cookies.isEmpty()) return ""
        return cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
    }

    @Throws(IOException::class)
    private suspend fun execute(url: String): String {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", KUWO_PC_UA)
        buildCookieHeader().takeIf { it.isNotBlank() }?.let { cookieHeader ->
            requestBuilder.header("Cookie", cookieHeader)
        }
        return client.newCall(requestBuilder.build()).awaitResponse { response ->
            if (!response.isSuccessful) {
                throw IOException("酷我请求失败: ${response.code} for url: $url")
            }
            response.body.string() ?: throw IOException("酷我响应为空")
        }
    }
}
