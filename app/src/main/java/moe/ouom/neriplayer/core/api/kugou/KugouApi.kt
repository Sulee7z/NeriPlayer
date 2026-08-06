package moe.ouom.neriplayer.core.api.kugou

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

private const val TAG = "KugouApi"

private const val KUGOU_MOBILE_UA =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 11_0 like Mac OS X) AppleWebKit/604.1.38 (KHTML, like Gecko) Version/11.0 Mobile/15A372 Safari/604.1"

/** 酷狗歌单摘要 */
data class KugouPlaylistSummary(
    val specialId: Long,
    val title: String,
    val picUrl: String? = null,
    val listenCount: Long = 0L,
    val songCount: Int = 0
)

/** 酷狗歌单/歌曲信息 */
data class KugouSong(
    val hash: String,
    val name: String,
    val artist: String,
    val albumName: String? = null,
    val albumId: Long = 0L,
    val durationMs: Long = 0L
)

data class KugouPlaylistDetail(
    val specialId: Long,
    val title: String,
    val coverUrl: String?,
    val listenCount: Long,
    val totalSongCount: Int,
    val songs: List<KugouSong>
)

/**
 * 酷狗音乐 API 客户端(移动端接口, 匿名可用)。
 *
 * 播放地址通过 getSongInfo 解析(hash), 返回直链。
 */
class KugouApi(
    private val client: OkHttpClient = AppContainer.sharedOkHttpClient,
    private val cookieProvider: () -> Map<String, String> = {
        runCatching { AppContainer.kugouCookieRepo.getCookiesOnce() }.getOrDefault(emptyMap())
    }
) {
    private val playUrlCache = ConcurrentHashMap<String, String>()

    fun hasLogin(): Boolean = cookieProvider().isNotEmpty()

    /** 热门歌单 */
    suspend fun getHotPlaylists(page: Int = 1, count: Int = 30): List<KugouPlaylistSummary> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://m.kugou.com/plist/index&json=true&page=$page"
                    .toHttpUrl()
                    .newBuilder()
                    .build()
                val responseJson = execute(url.toString())
                val root = JSONObject(responseJson)
                val info = root.optJSONObject("plist")
                    ?.optJSONObject("list")
                    ?.optJSONArray("info")
                    ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until info.length()) {
                        val item = info.optJSONObject(i) ?: continue
                        val id = item.optString("specialid").toLongOrNull() ?: continue
                        add(
                            KugouPlaylistSummary(
                                specialId = id,
                                title = item.optString("specialname"),
                                picUrl = item.optString("imgurl")
                                    .replace("{size}", "400")
                                    .takeIf { it.isNotBlank() },
                                listenCount = item.optLong("playcount"),
                                songCount = item.optInt("songcount")
                            )
                        )
                        if (size >= count) break
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

    /** 登录用户的歌单(尽力而为, 失败返回空由调用方回退热门) */
    suspend fun getUserPlaylists(userId: String, count: Int = 30): List<KugouPlaylistSummary> {
        if (userId.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://wwwapi.kugou.com/playlist/mine"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("r", "playlist/mine")
                    .addQueryParameter("userid", userId)
                    .addQueryParameter("page", "1")
                    .addQueryParameter("pagesize", count.toString())
                    .build()
                val responseJson = execute(url.toString())
                val root = JSONObject(responseJson)
                val list = root.optJSONArray("playlist") ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until list.length()) {
                        val item = list.optJSONObject(i) ?: continue
                        val id = item.optLong("specialid").takeIf { it > 0L }
                            ?: item.optString("specialid").toLongOrNull()
                            ?: continue
                        val title = item.optString("specialname")
                            .takeIf { it.isNotBlank() }
                            ?: continue
                        add(
                            KugouPlaylistSummary(
                                specialId = id,
                                title = title,
                                picUrl = item.optString("imgurl")
                                    .replace("{size}", "400")
                                    .takeIf { it.isNotBlank() },
                                listenCount = item.optLong("playcount"),
                                songCount = item.optInt("songcount")
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
    suspend fun getPlaylistDetail(specialId: Long): KugouPlaylistDetail {
        require(specialId > 0L) { "invalid specialid" }
        return withContext(Dispatchers.IO) {
            val url = "https://m.kugou.com/plist/list/$specialId"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("json", "true")
                .build()
            val responseJson = execute(url.toString())
            val root = JSONObject(responseJson)
            val info = root.optJSONObject("info")
                ?.optJSONObject("list")
                ?: throw IOException("酷狗歌单不存在: $specialId")
            val list = root.optJSONObject("list")
                ?.optJSONObject("list")
                ?.optJSONArray("info")
            val songs = ArrayList<KugouSong>()
            if (list != null) {
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val hash = item.optString("hash").takeIf { it.isNotBlank() } ?: continue
                    val filename = item.optString("filename")
                    val dash = filename.lastIndexOf(" - ")
                    val artist = if (dash > 0) filename.substring(0, dash).trim() else ""
                    val name = if (dash > 0) filename.substring(dash + 3).trim() else filename
                    songs.add(
                        KugouSong(
                            hash = hash,
                            name = name.ifBlank { filename },
                            artist = artist,
                            albumName = null,
                            albumId = item.optLong("album_id"),
                            durationMs = 0L
                        )
                    )
                }
            }
            KugouPlaylistDetail(
                specialId = specialId,
                title = info.optString("specialname"),
                coverUrl = info.optString("imgurl")
                    .replace("{size}", "400")
                    .takeIf { it.isNotBlank() },
                listenCount = info.optLong("playcount"),
                totalSongCount = info.optInt("songcount"),
                songs = songs
            )
        }
    }

    /**
     * 解析歌曲播放地址(hash)。返回可播放直链或 null。
     */
    suspend fun resolvePlayUrl(hash: String): String? {
        if (hash.isBlank()) return null
        playUrlCache[hash]?.let { return it }
        val url = withContext(Dispatchers.IO) {
            try {
                val target = "https://m.kugou.com/app/i/getSongInfo.php"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("cmd", "playInfo")
                    .addQueryParameter("hash", hash)
                    .build()
                val responseJson = execute(target.toString())
                val root = JSONObject(responseJson)
                root.optString("url").takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.d(TAG, "播放地址解析失败($hash): ${e.message}")
                null
            }
        }
        if (url != null) {
            playUrlCache[hash] = url
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
            .header("User-Agent", KUGOU_MOBILE_UA)
        buildCookieHeader().takeIf { it.isNotBlank() }?.let { cookieHeader ->
            requestBuilder.header("Cookie", cookieHeader)
        }
        return client.newCall(requestBuilder.build()).awaitResponse { response ->
            if (!response.isSuccessful) {
                throw IOException("酷狗请求失败: ${response.code} for url: $url")
            }
            response.body.string() ?: throw IOException("酷狗响应为空")
        }
    }
}
