package moe.ouom.neriplayer.core.customsource

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
 * File: moe.ouom.neriplayer.core.customsource/PlatformSongMatcher
 * Created: 2026/8/1
 *
 * 多平台歌名/ID 匹配器。
 *
 * 自定义音源脚本(LX 协议)只实现 musicUrl 这一个动作,不提供搜索能力;而不同平台的
 * 歌曲 ID 命名空间互不相通——网易云的数字 ID 在酷我/QQ音乐/酷狗/咪咕里没有任何意义。
 * 所以要让脚本在非网易云平台上解析成功,必须先在目标平台"真实"搜索一次,用歌名+歌手
 * (+时长兜底判重)找到该平台自己的原生曲目 ID,再把这个 ID 交给脚本去解析播放地址。
 *
 * 这与官方 LX Music Mobile 的 getOtherSource / findMusic 流程思路一致:官方在切换源前
 * 也是先用内置的各平台搜索 SDK 做一次同名搜索,拿到原生 ID 后才调用对应平台的解析逻辑。
 */

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.network.CustomSourceProxySelector
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val TAG = "NERI-PlatformMatcher"

/** 某个平台上与源曲目对应的原生曲目标识,及该平台解析可能需要的附加字段。 */
data class PlatformMatch(
    val songmid: String,
    val extra: Map<String, String> = emptyMap()
)

object PlatformSongMatcher {

    private val http = OkHttpClient.Builder()
        .proxySelector(CustomSourceProxySelector)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36"

    /** 命中阈值:低于这个分数的最佳候选也当作"没搜到",避免匹配到完全不相关的歌曲。 */
    private const val MATCH_THRESHOLD = 0.55

    /** 命中/未命中都缓存,避免每次播放同一首歌都重新发起搜索请求。 */
    private data class CacheEntry(val match: PlatformMatch?, val ts: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 小时

    /**
     * 在目标平台上按歌名+歌手搜索,返回该平台的原生曲目 ID。
     * @return 找不到可信匹配时返回 null(调用方应跳过该平台,而不是硬传网易云 ID)
     */
    suspend fun findNativeId(
        platform: String,
        name: String,
        artist: String,
        durationMs: Long
    ): PlatformMatch? {
        if (name.isBlank()) return null
        val key = "$platform:${name.trim()}:${artist.trim()}"
        cache[key]?.let { entry ->
            if (System.currentTimeMillis() - entry.ts < CACHE_TTL_MS) return entry.match
        }

        val match = withContext(Dispatchers.IO) {
            try {
                when (platform) {
                    CustomAudioSource.LX_SOURCE_KUWO -> searchKuwo(name, artist, durationMs)
                    CustomAudioSource.LX_SOURCE_KUGOU -> searchKugou(name, artist, durationMs)
                    CustomAudioSource.LX_SOURCE_TENCENT -> searchTencent(name, artist, durationMs)
                    CustomAudioSource.LX_SOURCE_MIGU -> searchMigu(name, artist, durationMs)
                    else -> null
                }
            } catch (e: Exception) {
                NPLogger.w(TAG, "跨平台搜索失败($platform): ${e.message}")
                null
            }
        }

        cache[key] = CacheEntry(match, System.currentTimeMillis())
        NPLogger.d(TAG, "跨平台匹配($platform): name=$name artist=$artist -> ${match?.songmid ?: "未命中"}")
        return match
    }

    // ---------------- 酷我 ----------------
    private fun searchKuwo(name: String, artist: String, durationMs: Long): PlatformMatch? {
        val url = "http://search.kuwo.cn/r.s?client=kt&all=${enc("$name $artist".trim())}&pn=0&rn=10" +
            "&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1" +
            "&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1"
        val body = get(url) ?: return null
        val root = try { JSONObject(stripJsonp(body)) } catch (e: Exception) { return null }
        val list = root.optJSONArray("abslist") ?: root.optJSONArray("list") ?: return null

        var best: JSONObject? = null
        var bestScore = -1.0
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val itemName = item.optString("SONGNAME", item.optString("NAME"))
            val itemArtist = item.optString("ARTIST")
            val score = scoreMatch(name, artist, itemName, itemArtist, durationMs, item.optLong("DURATION", -1L) * 1000)
            if (score > bestScore) { bestScore = score; best = item }
        }
        if (best == null || bestScore < MATCH_THRESHOLD) return null
        val rid = best.optString("MUSICRID").removePrefix("MUSIC_")
        return rid.takeIf { it.isNotBlank() }?.let { PlatformMatch(it) }
    }

    // ---------------- 酷狗 ----------------
    private fun searchKugou(name: String, artist: String, durationMs: Long): PlatformMatch? {
        val url = "https://songsearch.kugou.com/song_search_v2?keyword=${enc("$name $artist".trim())}" +
            "&page=1&pagesize=10&userid=0&clientver=&platform=WebFilter&filter=2&iscorrection=1&privilege_filter=0&area_code=1"
        val body = get(url) ?: return null
        val root = try { JSONObject(body) } catch (e: Exception) { return null }
        val list = root.optJSONObject("data")?.optJSONArray("lists") ?: return null

        var best: JSONObject? = null
        var bestScore = -1.0
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val itemName = item.optString("SongName", item.optString("FileName"))
            val itemArtist = item.optString("SingerName")
            val score = scoreMatch(name, artist, itemName, itemArtist, durationMs, item.optLong("Duration", -1L) * 1000)
            if (score > bestScore) { bestScore = score; best = item }
        }
        if (best == null || bestScore < MATCH_THRESHOLD) return null
        // 酷狗脚本一般用音频 hash 当原生 ID,优先取高品质 hash,回退标准 hash
        val hash = best.optString("HQFileHash").ifBlank {
            best.optString("SQFileHash").ifBlank { best.optString("FileHash") }
        }
        if (hash.isBlank()) return null
        return PlatformMatch(hash, mapOf("hash" to hash, "album_id" to best.optString("AlbumID")))
    }

    // ---------------- QQ音乐 ----------------
    private fun searchTencent(name: String, artist: String, durationMs: Long): PlatformMatch? {
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?ct=24&qqmusic_ver=1298" +
            "&new_json=1&remoteplace=txt.yqq.song&t=0&aggr=1&cr=1&catZhida=1&lossless=0" +
            "&flag_qc=0&p=1&n=10&w=${enc("$name $artist".trim())}&g_tk=5381&loginUin=0&hostUin=0" +
            "&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
        val body = get(url) ?: return null
        val root = try { JSONObject(body) } catch (e: Exception) { return null }
        val list = root.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: return null

        var best: JSONObject? = null
        var bestScore = -1.0
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val itemName = item.optString("songname")
            val singers = item.optJSONArray("singer")
            val itemArtist = buildString {
                if (singers != null) for (j in 0 until singers.length()) {
                    if (isNotEmpty()) append('/')
                    append(singers.optJSONObject(j)?.optString("name") ?: "")
                }
            }
            val score = scoreMatch(name, artist, itemName, itemArtist, durationMs, item.optLong("interval", -1L) * 1000)
            if (score > bestScore) { bestScore = score; best = item }
        }
        if (best == null || bestScore < MATCH_THRESHOLD) return null
        val songmid = best.optString("songmid")
        return songmid.takeIf { it.isNotBlank() }?.let { PlatformMatch(it) }
    }

    // ---------------- 咪咕 ----------------
    private fun searchMigu(name: String, artist: String, durationMs: Long): PlatformMatch? {
        val switch = "%7B%22song%22%3A1%2C%22album%22%3A0%2C%22singer%22%3A0%2C%22tagSong%22%3A0" +
            "%2C%22mvSong%22%3A0%2C%22songlist%22%3A0%2C%22bestShow%22%3A1%7D"
        val url = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do" +
            "?isCopyright=1&isCorrect=1&pageNo=1&pageSize=10&searchSwitch=$switch&sort=0&text=${enc("$name $artist".trim())}"
        val body = get(url) ?: return null
        val root = try { JSONObject(body) } catch (e: Exception) { return null }
        val list = root.optJSONObject("songResultData")?.optJSONArray("result") ?: return null

        var best: JSONObject? = null
        var bestScore = -1.0
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val itemName = item.optString("songName")
            val singers = item.optJSONArray("singers")
            val itemArtist = singers?.optJSONObject(0)?.optString("name") ?: ""
            val score = scoreMatch(name, artist, itemName, itemArtist, durationMs, -1L)
            if (score > bestScore) { bestScore = score; best = item }
        }
        if (best == null || bestScore < MATCH_THRESHOLD) return null
        val copyrightId = best.optString("copyrightId").ifBlank { best.optString("id") }
        return copyrightId.takeIf { it.isNotBlank() }?.let { PlatformMatch(it) }
    }

    // ---------------- 打分 ----------------

    /**
     * 用歌名相似度(权重最高) + 歌手是否命中 + 时长接近程度给候选打分。
     * 名字都对不上时直接短路返回低分,不必再看歌手/时长。
     */
    private fun scoreMatch(
        wantName: String, wantArtist: String,
        gotName: String, gotArtist: String,
        wantDurationMs: Long, gotDurationMs: Long
    ): Double {
        val nameScore = similarity(normalize(wantName), normalize(gotName))
        if (nameScore < 0.4) return nameScore
        var score = nameScore * 0.7

        val wantArtists = normalize(wantArtist).split('/', ',', '、', '&')
            .map { it.trim() }.filter { it.isNotEmpty() }
        val gotArtistNorm = normalize(gotArtist)
        val artistHit = wantArtists.isEmpty() || wantArtists.any { gotArtistNorm.contains(it) }
        score += if (artistHit) 0.25 else 0.0

        if (wantDurationMs > 0 && gotDurationMs > 0) {
            val diffSec = abs(wantDurationMs - gotDurationMs) / 1000.0
            score += when {
                diffSec <= 3 -> 0.05
                diffSec <= 8 -> 0.0
                else -> -0.15 // 时长差太多,大概率是翻唱/伴奏/live 版本
            }
        }
        return score
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[\\s()\\[\\]（）【】\\-_·.,，。!！?？]"), "")

    /** 基于最长公共子序列的相似度,足够区分"基本同名"与"名字对不上"这两种情况。 */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val dp = IntArray(b.length + 1)
        for (i in 1..a.length) {
            var prev = 0
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev + 1 else maxOf(dp[j], dp[j - 1])
                prev = tmp
            }
        }
        return (2.0 * dp[b.length]) / (a.length + b.length)
    }

    // ---------------- 工具 ----------------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun stripJsonp(s: String): String {
        val trimmed = s.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
    }

    private fun get(url: String): String? {
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body.string()
        }
    }
}
