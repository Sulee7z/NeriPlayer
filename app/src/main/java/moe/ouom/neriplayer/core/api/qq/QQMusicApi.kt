package moe.ouom.neriplayer.core.api.qq

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
 * File: moe.ouom.neriplayer.core.api.qq/QQMusicApi
 *
 * QQ 音乐完整 API 封装。
 *
 * 说明: QQ 音乐自 2026 年起对匿名请求大幅收紧 —— 歌曲播放地址(vkey)、歌单搜索、
 * 排行榜等接口都需要登录态或 VIP。本模块覆盖当前仍可匿名访问的全部接口(搜索、
 * 歌曲详情、歌词、歌单详情、专辑详情), 并保留播放地址解析的实现(接口放开时立即可用);
 * QQ 歌曲的实际播放优先走自定义音源(LX 脚本), 见 CustomSourceManager.resolveQqSongUrl。
 */

import android.annotation.SuppressLint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.util.network.awaitResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

private const val TAG = "QQMusicApi"

private const val QQ_REFERER_PLAYLIST = "https://y.qq.com/portal/playlist.html"
private const val QQ_REFERER_PORTAL = "https://y.qq.com"
private const val QQ_LIKE_COVER = "https://y.gtimg.cn/mediastyle/y/img/cover_love_300.jpg"
private const val QQ_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

/** 播放地址缓存时长: vkey 带过期时间, 短 TTL 即可 */
private const val PLAY_URL_CACHE_TTL_MS = 10 * 60 * 1000L

/** 歌单摘要 */
@Serializable
data class QQPlaylistSummary(
    @SerialName("dissid") val dissId: Long,
    @SerialName("dissname") val title: String,
    @SerialName("pic_url") val picUrl: String? = null,
    @SerialName("listen_num") val listenCount: Long = 0L,
    @SerialName("song_count") val songCount: Int = 0,
    @SerialName("creator") val creator: String? = null
)

/** 歌单/专辑内的一首歌 */
@Serializable
data class QQPlaylistSong(
    @SerialName("songmid") val songMid: String,
    @SerialName("songname") val songName: String,
    @SerialName("singer") val singer: List<QQSongArtist> = emptyList(),
    @SerialName("albumname") val albumName: String? = null,
    @SerialName("albummid") val albumMid: String? = null,
    @SerialName("interval") val interval: Long = 0L
)

@Serializable
data class QQSongArtist(
    val name: String = "",
    val mid: String? = null
)

/** 歌单详情 */
data class QQPlaylistDetail(
    val dissId: Long,
    val title: String,
    val coverUrl: String?,
    val creator: String?,
    val listenCount: Long,
    val totalSongCount: Int,
    val songs: List<QQPlaylistSong>
)

/** 专辑详情 */
data class QQAlbumDetail(
    val albumMid: String,
    val name: String,
    val singer: String,
    val coverUrl: String?,
    val publishTime: String?,
    val songs: List<QQPlaylistSong>
)

/**
 * QQ 音乐 API 客户端。
 */
class QQMusicApi(
    private val client: OkHttpClient = AppContainer.sharedOkHttpClient,
    private val cookieProvider: () -> Map<String, String> = {
        runCatching { AppContainer.qqCookieRepo.getCookiesOnce() }.getOrDefault(emptyMap())
    }
) {
    private val json = Json { ignoreUnknownKeys = true }

    private data class CachedPlayUrl(val url: String, val ts: Long)
    private val playUrlCache = ConcurrentHashMap<String, CachedPlayUrl>()

    /** 当前是否持有可用的 QQ 登录态 (uin + 票据) */
    fun hasLogin(): Boolean {
        val cookies = cookieProvider()
        return !cookies["uin"].isNullOrBlank() && (
            !cookies["qm_keyst"].isNullOrBlank() ||
                !cookies["p_skey"].isNullOrBlank()
            )
    }

    private fun buildCookieHeader(): String {
        val cookies = cookieProvider()
        if (cookies.isEmpty()) return ""
        return cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
    }

    /**
     * 登录用户的"我的歌单"(我创建的歌单 + 我收藏的歌单)。
     *
     * 对齐 listen1 实现:
     * - 创建的歌单: c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss (uin/hostuin, 分页 sin/size)
     * - 收藏的歌单: c.y.qq.com/fav/fcgi-bin/fcg_get_profile_order_asset (userid, reqtype=3, sin/ein)
     * uin 需保留 cookie 中的原值 (通常带 o 前缀, 如 o123456789), 去掉前缀会导致 500003 未登录。
     */
    suspend fun getUserPlaylists(
        uin: String,
        begin: Int = 0,
        num: Int = 30,
        maxPages: Int = 10
    ): List<QQPlaylistSummary> {
        val resolvedUin = uin.trim()
        if (resolvedUin.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            val out = ArrayList<QQPlaylistSummary>()
            val seen = HashSet<Long>()
            var rejectedCode = 0
            // 1) 我创建的歌单 (fcg_user_created_diss, listen1 验证)
            run {
                var offset = begin
                var page = 0
                while (page < maxPages) {
                    val url = "https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("cv", "4747474")
                        .addQueryParameter("ct", "24")
                        .addQueryParameter("format", "json")
                        .addQueryParameter("inCharset", "utf-8")
                        .addQueryParameter("outCharset", "utf-8")
                        .addQueryParameter("notice", "0")
                        .addQueryParameter("platform", "yqq.json")
                        .addQueryParameter("needNewCode", "1")
                        .addQueryParameter("uin", resolvedUin)
                        .addQueryParameter("hostuin", resolvedUin)
                        .addQueryParameter("sin", offset.toString())
                        .addQueryParameter("size", num.toString())
                        .build()
                    val request = Request.Builder().url(url)
                        .header("Referer", QQ_REFERER_PORTAL)
                        .header("User-Agent", QQ_USER_AGENT)
                        .build()
                    val responseJson = execute(request)
                    NPLogger.d(TAG, "我的歌单(created, sin=$offset): ${responseJson.take(400)}")
                    val root = JSONObject(responseJson)
                    if (root.optInt("code") != 0) {
                        rejectedCode = root.optInt("code")
                        NPLogger.w(TAG, "我的歌单(created)被拒: code=${root.optInt("code")} subcode=${root.optInt("subcode")}")
                        break
                    }
                    val list = root.optJSONObject("data")
                        ?.optJSONArray("disslist")
                        ?: break
                    var addedInPage = 0
                    for (i in 0 until list.length()) {
                        val item = list.optJSONObject(i) ?: continue
                        if (item.optInt("dir_show") == 0 && item.optLong("tid") == 0L) continue
                        val id = item.optString("tid").ifBlank { item.optString("dissid") }.toLongOrNull() ?: continue
                        val title = item.optString("diss_name")
                            .takeIf { it.isNotBlank() }
                            ?: item.optString("dissname").takeIf { it.isNotBlank() }
                            ?: continue
                        if (!seen.add(id)) continue
                        val isLikeFolder = title == "我喜欢" && item.optInt("dir_show") == 0
                        val rawPic = item.optString("diss_cover")
                            .ifBlank { item.optString("logo") }
                            .ifBlank { item.optString("imgurl") }
                        out.add(
                            QQPlaylistSummary(
                                dissId = id,
                                title = title,
                                picUrl = (if (isLikeFolder && rawPic.isBlank()) QQ_LIKE_COVER else rawPic)
                                    .takeIf { it.isNotBlank() }
                                    ?.let { if (it.startsWith("http://")) "https://" + it.removePrefix("http://") else it },
                                listenCount = item.optLong("listen_num"),
                                songCount = item.optInt("song_count")
                            )
                        )
                        addedInPage += 1
                    }
                    NPLogger.d(TAG, "我的歌单(created, sin=$offset): +$addedInPage (total=${out.size})")
                    if (addedInPage < num) break
                    offset += num
                    page += 1
                }
            }
            // 2) 我收藏的歌单 (fcg_get_profile_order_asset, listen1 验证)
            run {
                var offset = begin
                var page = 0
                while (page < maxPages) {
                    val url = "https://c.y.qq.com/fav/fcgi-bin/fcg_get_profile_order_asset.fcg"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("ct", "20")
                        .addQueryParameter("cid", "205360956")
                        .addQueryParameter("userid", resolvedUin)
                        .addQueryParameter("reqtype", "3")
                        .addQueryParameter("sin", offset.toString())
                        .addQueryParameter("ein", (offset + num - 1).toString())
                        .addQueryParameter("format", "json")
                        .build()
                    val request = Request.Builder().url(url)
                        .header("Referer", QQ_REFERER_PORTAL)
                        .header("User-Agent", QQ_USER_AGENT)
                        .build()
                    val responseJson = execute(request)
                    NPLogger.d(TAG, "我的歌单(favorite, sin=$offset): ${responseJson.take(400)}")
                    val root = JSONObject(responseJson)
                    if (root.optInt("code") != 0) {
                        rejectedCode = root.optInt("code")
                        NPLogger.w(TAG, "我的歌单(favorite)被拒: code=${root.optInt("code")} subcode=${root.optInt("subcode")}")
                        break
                    }
                    val list = root.optJSONObject("data")
                        ?.optJSONArray("cdlist")
                        ?: break
                    var addedInPage = 0
                    for (i in 0 until list.length()) {
                        val item = list.optJSONObject(i) ?: continue
                        if (item.optInt("dir_show") == 0) continue
                        val id = item.optString("dissid").ifBlank { item.optString("tid") }.toLongOrNull() ?: continue
                        val title = item.optString("dissname")
                            .takeIf { it.isNotBlank() }
                            ?: item.optString("diss_name").takeIf { it.isNotBlank() }
                            ?: continue
                        if (!seen.add(id)) continue
                        out.add(
                            QQPlaylistSummary(
                                dissId = id,
                                title = title,
                                picUrl = item.optString("logo")
                                    .ifBlank { item.optString("diss_cover") }
                                    .ifBlank { item.optString("imgurl") }
                                    .takeIf { it.isNotBlank() }
                                    ?.let { if (it.startsWith("http://")) "https://" + it.removePrefix("http://") else it },
                                listenCount = item.optLong("listennum"),
                                songCount = item.optInt("songnum")
                            )
                        )
                        addedInPage += 1
                    }
                    NPLogger.d(TAG, "我的歌单(favorite, sin=$offset): +$addedInPage (total=${out.size})")
                    if (addedInPage < num) break
                    offset += num
                    page += 1
                }
            }
            if (out.isEmpty() && rejectedCode != 0) {
                throw IOException("QQ 我的歌单请求被拒(code=$rejectedCode)")
            }
            out
        }
    }

    /**
     * 热门歌单(匿名可用)。
     *
     * @param categoryId 分类 ID, 10000000=全部, 具体分类可用 [getPlaylistCategories]
     * @param sortId 排序: 5=推荐, 2=最新, 3=播放最多
     */
    suspend fun getHotPlaylists(
        categoryId: Long = 10000000L,
        sortId: Int = 5,
        begin: Int = 0,
        count: Int = 30
    ): List<QQPlaylistSummary> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("categoryId", categoryId.toString())
                    .addQueryParameter("sortId", sortId.toString())
                    .addQueryParameter("sin", begin.toString())
                    .addQueryParameter("ein", (begin + count - 1).toString())
                    .addQueryParameter("format", "json")
                    .build()
                val request = Request.Builder().url(url)
                    .header("Referer", QQ_REFERER_PORTAL)
                    .header("User-Agent", QQ_USER_AGENT)
                    .build()
                val responseJson = execute(request)
                val root = JSONObject(responseJson)
                if (root.optInt("code") != 0) return@withContext emptyList()
                val list = root.optJSONObject("data")
                    ?.optJSONArray("list")
                    ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until list.length()) {
                        val item = list.optJSONObject(i) ?: continue
                        val id = item.optString("dissid").toLongOrNull() ?: continue
                        add(
                            QQPlaylistSummary(
                                dissId = id,
                                title = item.optString("dissname"),
                                picUrl = item.optString("imgurl")
                                    .takeIf { it.isNotBlank() }
                                    ?.let { if (it.startsWith("http://")) "https://" + it.removePrefix("http://") else it },
                                listenCount = item.optLong("listennum"),
                                songCount = 0,
                                creator = item.optJSONObject("creator")
                                    ?.optString("name")
                                    ?.takeIf { it.isNotBlank() }
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

    /**
     * 歌单搜索。接口近期对匿名请求返回登录要求, 可能得到空列表, 属正常现象。
     */
    suspend fun searchPlaylists(keyword: String, page: Int = 1): List<QQPlaylistSummary> {
        if (keyword.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val req = JSONObject().put(
                    "comm", JSONObject().put("ct", 19).put("cv", 0)
                ).put(
                    "req_0", JSONObject()
                        .put("module", "music.musichallDiss.DissSearch")
                        .put("method", "DoSearchForQQMusicDesktop")
                        .put("param", JSONObject().apply {
                            put("query", keyword)
                            put("start", (page - 1).coerceAtLeast(0) * 10)
                            put("num", 10)
                            put("search_type", 5)
                        })
                ).toString()

                val url = "https://u.y.qq.com/cgi-bin/musicu.fcg".toHttpUrl().newBuilder()
                    .addQueryParameter("format", "json")
                    .addQueryParameter("data", req)
                    .build()
                val responseJson = execute(url.toString())
                val root = JSONObject(responseJson)
                val envelope = root.optJSONObject("req_0") ?: return@withContext emptyList()
                if (envelope.optInt("code") != 0) {
                    NPLogger.d(TAG, "歌单搜索被拒: code=${envelope.optInt("code")}")
                    return@withContext emptyList()
                }
                val list = envelope.optJSONObject("data")
                    ?.optJSONObject("diss")
                    ?.optJSONArray("list")
                    ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until list.length()) {
                        val item = list.optJSONObject(i) ?: continue
                        val id = item.optString("dissid").toLongOrNull() ?: continue
                        add(
                            QQPlaylistSummary(
                                dissId = id,
                                title = item.optString("dissname"),
                                picUrl = item.optString("pic_url").takeIf { it.isNotBlank() },
                                listenCount = item.optLong("listennum"),
                                songCount = item.optInt("song_count"),
                                creator = item.optString("creator").takeIf { it.isNotBlank() }
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.d(TAG, "歌单搜索失败: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * 歌单详情(含歌曲列表)。
     *
     * 对齐 listen1/lx-music 的实现: i.y.qq.com/qzone-music 的
     * fcg_ucc_getcdinfo_byids_cp.fcg, 带 nosign=1, 不带 new_format/song_begin
     * (带 new_format=1 时响应歌曲字段结构不同, 旧式解析会得到空列表)。
     * [begin]/[num] 保留用于兼容调用方, 实际一次性拉全量。
     */
    suspend fun getPlaylistDetail(
        dissId: Long,
        begin: Int = 0,
        num: Int = 100
    ): QQPlaylistDetail {
        require(dissId > 0L) { "invalid dissid" }
        return withContext(Dispatchers.IO) {
            val url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("type", "1")
                .addQueryParameter("json", "1")
                .addQueryParameter("utf8", "1")
                .addQueryParameter("onlysong", "0")
                .addQueryParameter("nosign", "1")
                .addQueryParameter("disstid", dissId.toString())
                .addQueryParameter("g_tk", "5381")
                .addQueryParameter("loginUin", "0")
                .addQueryParameter("hostUin", "0")
                .addQueryParameter("format", "json")
                .addQueryParameter("inCharset", "GB2312")
                .addQueryParameter("outCharset", "utf-8")
                .addQueryParameter("notice", "0")
                .addQueryParameter("platform", "yqq")
                .addQueryParameter("needNewCode", "0")
                .build()

            val request = Request.Builder().url(url)
                .header("Referer", QQ_REFERER_PLAYLIST)
                .header("User-Agent", QQ_USER_AGENT)
                .build()
            val responseJson = execute(request)
            NPLogger.d(TAG, "歌单详情($dissId): ${responseJson.take(300)}")
            val root = JSONObject(responseJson)
            if (root.optInt("code") != 0) {
                throw IOException("QQ 歌单接口返回 code=${root.optInt("code")}")
            }
            val cdlist = root.optJSONArray("cdlist")
            val info = cdlist?.optJSONObject(0) ?: throw IOException("QQ 歌单不存在: $dissId")
            val songList = info.optJSONArray("songlist")
            val songs = ArrayList<QQPlaylistSong>(songList?.length() ?: 0)
            if (songList != null) {
                for (i in 0 until songList.length()) {
                    songList.optJSONObject(i)?.let { songs.add(parseSong(it)) }
                }
            }
            QQPlaylistDetail(
                dissId = dissId,
                title = info.optString("dissname"),
                coverUrl = info.optString("logo").takeIf { it.isNotBlank() },
                creator = info.optString("nickname").takeIf { it.isNotBlank() },
                listenCount = info.optLong("listen_num"),
                totalSongCount = info.optInt("songnum"),
                songs = songs
            )
        }
    }

    /**
     * 专辑详情(含歌曲列表)。
     */
    suspend fun getAlbumDetail(albumMid: String): QQAlbumDetail {
        require(albumMid.isNotBlank()) { "invalid albummid" }
        return withContext(Dispatchers.IO) {
            val url = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_album_info_cp.fcg"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("albummid", albumMid)
                .addQueryParameter("format", "json")
                .build()
            val request = Request.Builder().url(url)
                .header("Referer", QQ_REFERER_PORTAL)
                .header("User-Agent", QQ_USER_AGENT)
                .build()
            val responseJson = execute(request)
            val root = JSONObject(responseJson)
            if (root.optInt("code") != 0) {
                throw IOException("QQ 专辑接口返回 code=${root.optInt("code")}")
            }
            val data = root.optJSONObject("data") ?: throw IOException("QQ 专辑不存在: $albumMid")
            val list = data.optJSONArray("list")
            val songs = ArrayList<QQPlaylistSong>(list?.length() ?: 0)
            if (list != null) {
                for (i in 0 until list.length()) {
                    list.optJSONObject(i)?.let { songs.add(parseSong(it)) }
                }
            }
            QQAlbumDetail(
                albumMid = albumMid,
                name = data.optString("name"),
                singer = data.optString("singer_name"),
                coverUrl = "https://y.qq.com/music/photo_new/T002R800x800M000$albumMid.jpg",
                publishTime = data.optString("aDate").takeIf { it.isNotBlank() },
                songs = songs
            )
        }
    }

    /**
     * 解析歌曲播放地址 (CgiGetVkey)。
     *
     * 对齐 listen1_desktop 的实现: 使用 POST musicu.fcg, 请求体含 req_1/comm/loginUin
     * 与 filename 字段; 匿名/无 VIP 时接口对绝大多数歌曲返回空 purl(此时返回 null)。
     */
    suspend fun resolveSongPlayUrl(songMid: String): String? {
        if (songMid.isBlank()) return null
        val now = System.currentTimeMillis()
        playUrlCache[songMid]?.let { entry ->
            if (now - entry.ts < PLAY_URL_CACHE_TTL_MS) return entry.url
            playUrlCache.remove(songMid)
        }
        val url = withContext(Dispatchers.IO) {
            try {
                val cookies = cookieProvider()
                val uin = cookies["uin"]?.trim()?.trimStart('o').orEmpty()
                val guid = Random.nextLong(100_000_000, 9_999_999_999).toString()
                // listen1: 单曲时 filename = "C400<songmid><songmid>.m4a"
                val filename = "C400$songMid$songMid.m4a"
                val reqData = JSONObject().apply {
                    put(
                        "req_1", JSONObject()
                            .put("module", "vkey.GetVkeyServer")
                            .put("method", "CgiGetVkey")
                            .put("param", JSONObject().apply {
                                put("filename", JSONArrayOf(filename))
                                put("guid", guid)
                                put("songmid", JSONArrayOf(songMid))
                                put("songtype", JSONArrayOf(0))
                                put("uin", uin)
                                put("loginflag", if (uin.isNotBlank()) 1 else 0)
                                put("platform", "20")
                            })
                    )
                    put("loginUin", uin)
                    put(
                        "comm", JSONObject().apply {
                            put("uin", uin.toLongOrNull() ?: 0L)
                            put("format", "json")
                            put("ct", 24)
                            put("cv", 0)
                        }
                    )
                }
                val requestUrl = "https://u.y.qq.com/cgi-bin/musicu.fcg".toHttpUrl().newBuilder()
                    .addQueryParameter("format", "json")
                    .addQueryParameter("loginUin", uin)
                    .build()
                val requestBuilder = Request.Builder().url(requestUrl)
                    .header("Referer", QQ_REFERER_PORTAL)
                    .header("User-Agent", QQ_USER_AGENT)
                    .post(reqData.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                buildCookieHeader().takeIf { it.isNotBlank() }?.let { cookieHeader ->
                    requestBuilder.header("Cookie", cookieHeader)
                }
                val responseJson = execute(requestBuilder.build())
                val root = JSONObject(responseJson)
                val data = root.optJSONObject("req_1")
                    ?.optJSONObject("data")
                    ?: return@withContext null
                val info = data.optJSONArray("midurlinfo")
                    ?.optJSONObject(0)
                    ?: return@withContext null
                val purl = info.optString("purl").takeIf { it.isNotBlank() }
                    ?: return@withContext null
                val sip = data.optJSONArray("sip")?.optString(0)
                    ?: return@withContext null
                if (!sip.startsWith("http")) return@withContext null
                sip.trimEnd('/') + "/" + purl
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.d(TAG, "播放地址解析失败($songMid): ${e.message}")
                null
            }
        }
        if (url != null) {
            playUrlCache[songMid] = CachedPlayUrl(url, System.currentTimeMillis())
        }
        return url
    }

    private fun parseSong(obj: JSONObject): QQPlaylistSong {
        val singerArr = obj.optJSONArray("singer")
        val singers = ArrayList<QQSongArtist>()
        if (singerArr != null) {
            for (i in 0 until singerArr.length()) {
                val s = singerArr.optJSONObject(i) ?: continue
                singers.add(
                    QQSongArtist(
                        name = s.optString("name"),
                        mid = s.optString("mid").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
        // 兼容新旧两种字段: 旧式 songmid/songname/albumname/albummid/interval,
        // 新式 mid/name/album{name,mid}/duration(毫秒)
        val album = obj.optJSONObject("album")
        val albumName = obj.optString("albumname")
            .ifBlank { album?.optString("name").orEmpty() }
        val albumMid = obj.optString("albummid")
            .ifBlank { album?.optString("mid").orEmpty() }
        val durationMs = when {
            obj.optLong("duration") > 0L -> obj.optLong("duration")
            obj.optLong("interval") > 0L -> obj.optLong("interval") * 1000L
            else -> 0L
        }
        return QQPlaylistSong(
            songMid = obj.optString("songmid")
                .ifBlank { obj.optString("mid") },
            songName = obj.optString("songname")
                .ifBlank { obj.optString("name") },
            singer = singers,
            albumName = albumName.takeIf { it.isNotBlank() },
            albumMid = albumMid.takeIf { it.isNotBlank() },
            interval = durationMs / 1000L
        )
    }

    private fun JSONArrayOf(value: Any): org.json.JSONArray = org.json.JSONArray().put(value)

    @Throws(IOException::class)
    private suspend fun execute(url: String): String {
        val request = Request.Builder().url(url)
            .header("Referer", QQ_REFERER_PORTAL)
            .header("User-Agent", QQ_USER_AGENT)
            .build()
        return execute(request)
    }

    @Throws(IOException::class)
    private suspend fun execute(request: Request): String {
        val requestBuilder = request.newBuilder()
        buildCookieHeader().takeIf { it.isNotBlank() }?.let { cookieHeader ->
            requestBuilder.header("Cookie", cookieHeader)
        }
        return client.newCall(requestBuilder.build()).awaitResponse { response ->
            if (!response.isSuccessful) {
                throw IOException("QQ 请求失败: ${response.code} for url: ${request.url}")
            }
            response.body.string() ?: throw IOException("QQ 响应为空")
        }
    }
}

object QQMusicSongBuilder {
    const val QQ_SOURCE_TAG = "QQMusic"

    fun isQqMusicSong(song: SongItem): Boolean = song.album.startsWith(QQ_SOURCE_TAG)

    fun qqSongMidOrNull(song: SongItem): String? {
        song.audioId?.takeIf { it.isNotBlank() }?.let { return it }
        return song.id.takeIf { it > 0L }?.toString()
    }
}

/**
 * 把 QQ 歌单/专辑歌曲转换为播放器 [SongItem]。
 *
 * 平台识别沿用 album 前缀约定: 以 [QQMusicSongBuilder.QQ_SOURCE_TAG]("QQMusic") 开头,
 * 真实专辑名接在后面; QQ 原生 ID(songmid) 保存在 [SongItem.audioId]。
 */
@SuppressLint("DefaultLocale")
fun QQPlaylistSong.toSongItem(albumTag: String = QQMusicSongBuilder.QQ_SOURCE_TAG): SongItem {
    val mid = songMid.trim()
    return SongItem(
        id = (if (mid.isNotBlank()) mid.hashCode() else songName.trim().hashCode().coerceAtLeast(1))
            .toLong() and 0x7fffffffL,
        name = songName,
        artist = singer.joinToString("/") { it.name },
        album = if (albumName.isNullOrBlank()) albumTag else "$albumTag$albumName",
        albumId = 0L,
        durationMs = interval * 1000L,
        coverUrl = albumMid?.let { "https://y.qq.com/music/photo_new/T002R800x800M000$it.jpg" },
        audioId = mid.takeIf { it.isNotBlank() },
        sourceStableKey = mid.takeIf { it.isNotBlank() }?.let { "qq:$it" }
    )
}
