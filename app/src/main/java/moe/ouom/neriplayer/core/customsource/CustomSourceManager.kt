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
 * File: moe.ouom.neriplayer.core.customsource/CustomSourceManager
 * Created: 2026/7/26
 *
 * 自定义音源的高层门面:管理引擎生命周期,给播放链路提供 resolveNeteaseUrl,
 * 给 UI 提供导入/启用/删除/测试。
 */

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

private const val TAG = "NERI-CustomSourceMgr"
private const val MAX_RESOLVE_RETRIES = 2
private const val RETRY_DELAY_BASE_MS = 1_500L
private const val RETRY_DELAY_MAX_MS = 5_000L

/** 解析成功 URL 的内存缓存时长: 短 TTL 即可覆盖"同一首重播/seek 刷新"的热点场景 */
private const val URL_CACHE_TTL_MS = 15 * 60 * 1000L

/** 音源整体判定失败后的冷却窗口: 冷却期内直接跳过该音源, 避免每次都白等超时 */
private const val SOURCE_FAILURE_COOLDOWN_MS = 60 * 1000L

class CustomSourceManager(
    private val appContext: Context,
    val repository: CustomSourceRepository
) {
    private val engineMutex = Mutex()
    // 多源共存:每个启用音源各持有一个引擎,按 id 缓存
    private val engines = HashMap<String, LxScriptEngine>()

    private data class CachedUrl(val url: String, val ts: Long)
    private val urlCache = ConcurrentHashMap<String, CachedUrl>()

    /** sourceId -> 最近一次确定性失败时间戳 */
    private val failureCooldowns = ConcurrentHashMap<String, Long>()

    private fun cacheKey(songId: String, qualityKey: String, sourceId: String) =
        "$sourceId:$qualityKey:$songId"

    private fun readUrlCache(key: String): String? {
        val now = System.currentTimeMillis()
        val entry = urlCache[key] ?: return null
        if (now - entry.ts >= URL_CACHE_TTL_MS) {
            urlCache.remove(key)
            return null
        }
        return entry.url
    }

    private fun writeUrlCache(key: String, url: String) {
        urlCache[key] = CachedUrl(url, System.currentTimeMillis())
    }

    /**
     * 是否存在已启用的音源。
     * 只要启用了音源就尝试(即使脚本没声明 wy,也可能通过其它平台"解锁"匹配)。
     */
    fun hasActiveNeteaseSource(): Boolean = repository.activeSources.isNotEmpty()

    /**
     * 按优先级排序脚本声明的平台:网易云(wy)优先,其余按常见顺序。
     * 若脚本未声明任何平台(旧数据/探测失败),回退为仅尝试 wy。
     */
    private fun resolvePlatformOrder(active: CustomAudioSource): List<String> {
        val declared = active.supportedSources.keys
        if (declared.isEmpty()) return listOf(CustomAudioSource.LX_SOURCE_NETEASE)
        val preferred = listOf(
            CustomAudioSource.LX_SOURCE_NETEASE,
            CustomAudioSource.LX_SOURCE_TENCENT,
            CustomAudioSource.LX_SOURCE_KUGOU,
            CustomAudioSource.LX_SOURCE_KUWO,
            CustomAudioSource.LX_SOURCE_MIGU
        )
        val ordered = preferred.filter { declared.contains(it) }.toMutableList()
        // 追加脚本声明但不在预设顺序里的其它平台
        declared.forEach { if (!ordered.contains(it)) ordered.add(it) }
        return ordered
    }

    /**
     * 完全按照 LX Music 的逻辑解析歌曲 URL:
     * 1. 构建符合 toOldMusicInfo 格式的 musicInfo
     * 2. 按平台顺序尝试 (wy -> tx -> kg -> kw -> mg), 每个平台用脚本声明的音质
     * 3. 单次失败后进行带随机退避的重试
     * @param neteaseQualityKey NeriPlayer 的网易云音质 key
     * @return 可播放 URL,失败或无可用音源返回 null
     */
    suspend fun resolveNeteaseUrl(song: SongItem, neteaseQualityKey: String): String? {
        val actives = repository.activeSources
        if (actives.isEmpty()) return null

        val lxQuality = mapNeteaseQualityToLx(neteaseQualityKey)

        // 同一首歌短时间内重播/seek 刷新时, 直接复用上次解析结果, 不再跑脚本
        val songIdKey = "${song.id}:$neteaseQualityKey"
        val cached = readUrlCache(songIdKey)
        if (cached != null) {
            NPLogger.d(TAG, "自定义音源命中缓存: id=${song.id} quality=$neteaseQualityKey")
            return cached
        }

        val baseMusicInfo = buildMusicInfo(song)

        for (active in actives) {
            val now = System.currentTimeMillis()
            val cooldownUntil = failureCooldowns[active.id] ?: 0L
            if (now < cooldownUntil) {
                NPLogger.d(TAG, "跳过冷却中的音源: ${active.name}")
                continue
            }

            val eng = ensureEngine(active) ?: continue
            val platforms = resolvePlatformOrder(active)

            var sawTransient = false
            for (platform in platforms) {
                // 网易云直接用源曲目自己的 ID;其它平台的 ID 命名空间跟网易云不通用,
                // 必须先按歌名+歌手在目标平台搜一次,换成该平台自己的原生 ID 再解析,
                // 否则脚本拿着网易云的数字 ID 去问酷我/QQ音乐/酷狗/咪咕必然 404。
                val musicInfo = if (platform == CustomAudioSource.LX_SOURCE_NETEASE) {
                    baseMusicInfo
                } else {
                    val match = PlatformSongMatcher.findNativeId(
                        platform = platform,
                        name = song.name,
                        artist = song.artist,
                        durationMs = song.durationMs
                    )
                    if (match == null) {
                        NPLogger.d(TAG, "跨平台匹配未命中,跳过: ${active.name}/$platform name=${song.name}")
                        continue
                    }
                    JSONObject(baseMusicInfo.toString()).apply {
                        put("songmid", match.songmid)
                        put("id", match.songmid)
                        put("source", platform)
                        match.extra.forEach { (k, v) -> if (v.isNotBlank()) put(k, v) }
                    }
                }

                var retries = 0
                while (true) {
                    val result = try {
                        eng.resolve(source = platform, quality = lxQuality, musicInfo = musicInfo)
                    } catch (e: Exception) {
                        NPLogger.w(TAG, "自定义音源解析异常(${active.name}/$platform/$lxQuality)", e)
                        LxScriptEngine.ResolveResult(null, "异常: ${e.message}", transient = true)
                    }
                    val url = result.url
                    if (!url.isNullOrBlank()) {
                        writeUrlCache(songIdKey, url)
                        NPLogger.i(TAG, "自定义音源命中: ${active.name}/$platform quality=$lxQuality id=${song.id} retries=$retries")
                        return url
                    }
                    // 确定性失败(脚本明确说没有/无版权)再试多少次都一样, 不重试
                    if (result.transient) sawTransient = true
                    if (!result.transient || retries >= MAX_RESOLVE_RETRIES) {
                        NPLogger.d(TAG, "自定义音源放弃: ${active.name}/$platform transient=${result.transient}: ${result.detail}")
                        break
                    }
                    retries++
                    val backoffMs = Random.nextLong(RETRY_DELAY_BASE_MS, RETRY_DELAY_MAX_MS)
                    NPLogger.d(TAG, "自定义音源重试: ${active.name}/$platform retry=$retries backoffMs=$backoffMs name=${song.name}")
                    delay(backoffMs)
                }
            }

            // 该音源对这首歌全是确定性失败(没有超时/网络抖动迹象) → 进入冷却, 下次解析优先跳过
            if (!sawTransient) {
                failureCooldowns[active.id] = System.currentTimeMillis() + SOURCE_FAILURE_COOLDOWN_MS
            }
        }
        NPLogger.w(TAG, "自定义音源全部失败: id=${song.id} name=${song.name} artist=${song.artist}")
        return null
    }

    /**
     * 直接解析一首 QQ 音乐歌曲的播放地址。
     *
     * 不经过跨平台搜索: [songQqSongMid] 就是 QQ 音乐原生 ID, 直接交给脚本的 tx 平台解析。
     * 供 QQ 音乐曲目的播放链路使用(QQ 官方接口已不再对匿名请求下发播放地址)。
     */
    suspend fun resolveQqSongUrl(
        song: SongItem,
        qqSongMid: String,
        qualityKey: String
    ): String? {
        if (qqSongMid.isBlank()) return null
        val actives = repository.activeSources
        if (actives.isEmpty()) return null

        val lxQuality = mapNeteaseQualityToLx(qualityKey)
        val cacheKey = "qq:$song.id:$qualityKey"
        readUrlCache(cacheKey)?.let { return it }

        val baseInfo = buildMusicInfo(song).apply {
            put("songmid", qqSongMid)
            put("id", qqSongMid)
            put("source", CustomAudioSource.LX_SOURCE_TENCENT)
        }

        for (active in actives) {
            val now = System.currentTimeMillis()
            val cooldownUntil = failureCooldowns[active.id] ?: 0L
            if (now < cooldownUntil) continue

            val eng = ensureEngine(active) ?: continue
            var sawTransient = false
            var retries = 0
            while (true) {
                val result = try {
                    eng.resolve(
                        source = CustomAudioSource.LX_SOURCE_TENCENT,
                        quality = lxQuality,
                        musicInfo = baseInfo
                    )
                } catch (e: Exception) {
                    NPLogger.w(TAG, "自定义音源解析异常(QQ/${active.name})", e)
                    LxScriptEngine.ResolveResult(null, "异常: ${e.message}", transient = true)
                }
                if (!result.url.isNullOrBlank()) {
                    writeUrlCache(cacheKey, result.url)
                    NPLogger.i(TAG, "自定义音源命中(QQ): ${active.name} id=${song.id}")
                    return result.url
                }
                if (result.transient) sawTransient = true
                if (!result.transient || retries >= MAX_RESOLVE_RETRIES) break
                retries++
                delay(Random.nextLong(RETRY_DELAY_BASE_MS, RETRY_DELAY_MAX_MS))
            }
            if (!sawTransient) {
                failureCooldowns[active.id] = System.currentTimeMillis() + SOURCE_FAILURE_COOLDOWN_MS
            }
        }
        NPLogger.w(TAG, "自定义音源 QQ 解析全部失败: id=${song.id} songmid=$qqSongMid")
        return null
    }

    /**
     * 导入前快速校验脚本内容是否像 LX 脚本:
     * 必须存在 lx 对象注册 (lx.on / globalThis.lx / EVENT_NAMES) 或 musicUrl 处理器关键词,
     * 避免把明显不是脚本的文件丢进 WebView 白等 20 秒超时。
     */
    fun validateScriptContent(scriptContent: String): Boolean {
        if (scriptContent.isBlank() || scriptContent.length < 64) return false
        val sample = scriptContent.take(30_000).lowercase()
        val hasLxRegistration = sample.contains("lx.on") ||
            sample.contains("globalthis.lx") ||
            sample.contains("window.lx") ||
            sample.contains("lx =") ||
            sample.contains("lx=")
        val hasMusicUrlHandler = sample.contains("musicurl") ||
            sample.contains("EVENT_NAMES".lowercase()) ||
            sample.contains("request")
        return hasLxRegistration || hasMusicUrlHandler
    }

    /**
     * 从 URL 下载脚本内容(导入用)。返回 null 表示下载失败。
     */
    suspend fun fetchScriptFromUrl(url: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext null
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body.string() ?: return@withContext null
                if (body.length > 2 * 1024 * 1024) return@withContext null
                body
            }
        } catch (e: Exception) {
            NPLogger.w(TAG, "下载脚本失败: $url -> ${e.message}")
            null
        }
    }

    /**
     * 运行一段脚本并等待其 inited,返回解析出的支持平台。用于导入时探测与"测试"。
     * 使用一次性引擎,用完即销毁。
     */
    suspend fun probeScript(scriptContent: String): LxScriptEngine.InitResult {
        val probe = LxScriptEngine(appContext, scriptContent)
        return try {
            probe.start(timeoutMs = 22_000)
        } finally {
            probe.destroy()
        }
    }

    /**
     * 端到端诊断:用当前启用音源真实解析一首示例网易云歌曲,返回可读结果。
     * 供设置页"测试"按钮使用,无需 logcat 即可看到失败原因。
     */
    // 几首常年在架的热门网易云歌曲,用于测试解析(避免用无效 ID 误报 404)
    private val sampleNeteaseIds = listOf(186016L, 347230L, 1974443814L, 25906124L)

    suspend fun diagnoseActiveNetease(): String {
        val active = repository.activeSource ?: return "请先启用一个音源"

        // 复用已缓存的引擎(不再每次测试都重建 WebView);失败时才建一次性引擎拿错误详情
        val eng = ensureEngine(active)
        if (eng == null) {
            val script = repository.readScript(active) ?: return "脚本内容缺失"
            val probe = LxScriptEngine(appContext, script)
            return try {
                val init = probe.start()
                val log = probe.recentHttpLog()
                val logBlock = if (log.isEmpty()) "" else "\n③ 请求:\n" + log.joinToString("\n") { "   $it" }
                "① 初始化失败: ${init.error ?: "未知"}$logBlock"
            } finally {
                probe.destroy()
            }
        }

        val init = eng.initInfo() ?: return "引擎状态异常,请重试"
        val platforms = init.sources.entries.joinToString(" | ") { (k, v) ->
            "$k: ${v.joinToString(",")}"
        }.ifBlank { "(无)" }
        val hasNetease = init.sources.containsKey(CustomAudioSource.LX_SOURCE_NETEASE)
        val looksLikeFallback = init.sources.keys == setOf(CustomAudioSource.LX_SOURCE_KUWO) &&
            init.sources[CustomAudioSource.LX_SOURCE_KUWO] == listOf("128k")

        // 依次尝试几首热门歌曲,任一成功即算通过(网易云本源)
        var lastDetail = "无"
        var success = false
        if (hasNetease) {
            for (id in sampleNeteaseIds) {
                val musicInfo = JSONObject().apply {
                    put("songmid", id)
                    put("id", id)
                    put("name", "测试歌曲")
                    put("singer", "测试")
                    put("albumName", "")
                    put("source", CustomAudioSource.LX_SOURCE_NETEASE)
                }
                val result = eng.resolve(source = CustomAudioSource.LX_SOURCE_NETEASE, quality = "320k", musicInfo = musicInfo)
                lastDetail = result.detail
                if (result.url != null) { success = true; break }
            }
        }

        // 对脚本声明的其它平台逐一跑一遍"搜索匹配原生ID + 解析"全链路,
        // 用几首常见热门歌验证跨平台切换是否真的能用,而不是静默跳过。
        val crossPlatformSamples = listOf("晴天" to "周杰伦", "光年之外" to "邓紫棋", "起风了" to "买辣椒也用券")
        val crossPlatformLines = mutableListOf<String>()
        for (platform in init.sources.keys) {
            if (platform == CustomAudioSource.LX_SOURCE_NETEASE) continue
            var matchedLine: String? = null
            for ((name, artist) in crossPlatformSamples) {
                val match = PlatformSongMatcher.findNativeId(platform, name, artist, -1L)
                if (match != null) {
                    val musicInfo = JSONObject().apply {
                        put("songmid", match.songmid)
                        put("id", match.songmid)
                        put("name", name)
                        put("singer", artist)
                        put("albumName", "")
                        put("source", platform)
                        match.extra.forEach { (k, v) -> if (v.isNotBlank()) put(k, v) }
                    }
                    val result = eng.resolve(source = platform, quality = "320k", musicInfo = musicInfo)
                    matchedLine = if (result.url != null) {
                        "$platform: 搜到「$name」(原生id=${match.songmid}),解析成功"
                    } else {
                        "$platform: 搜到「$name」(原生id=${match.songmid}),但脚本解析失败: ${result.detail}"
                    }
                    break
                }
            }
            crossPlatformLines.add(
                matchedLine ?: "$platform: 跨平台搜索未命中测试曲目(接口可能被限流/失效,不代表所有歌都搜不到)"
            )
        }

        val httpLines = eng.recentHttpLog()
        val httpBlock = if (httpLines.isEmpty()) "③ 网络请求: (无)"
        else "③ 配置/解析请求:\n" + httpLines.takeLast(6).joinToString("\n") { "   $it" }

        val hint = buildString {
            when {
                looksLikeFallback ->
                    append("\n⚠ 疑似配置拉取失败,回退默认(仅酷我128k)。多为 npm 源在国内被墙,换网络/代理再试。")
                success ->
                    append("\n✅ 音源可用!直接开启「自定义音源优先」播放真实歌曲即可。")
                !hasNetease ->
                    append("\n⚠ 该源不支持网易云(wy),无法用于本 App 的网易云歌曲。")
                else ->
                    append("\nℹ 示例歌曲在音源服务器上暂无资源(如 404),不代表音源不可用——实际播放真实歌曲时会用真实 ID,可直接试播。")
            }
        }

        val crossBlock = if (crossPlatformLines.isEmpty()) ""
        else "\n④ 跨平台匹配(自动切源):\n" + crossPlatformLines.joinToString("\n") { "   $it" }

        return "① 初始化成功(平台: $platforms)\n② 网易云示例解析: $lastDetail\n$httpBlock$crossBlock$hint"
    }

    /** 预热:提前构建/初始化所有启用音源的引擎,让首次播放不必等待配置拉取。 */
    suspend fun warmActive() {
        repository.activeSources.forEach { active ->
            runCatching { ensureEngine(active) }
        }
    }

    /**
     * 启用集合变化后调用:销毁不再启用的引擎,保留仍启用的(避免无谓重建)。
     */
    suspend fun onActiveSourceChanged() {
        engineMutex.withLock {
            val activeIds = repository.activeSources.mapTo(HashSet()) { it.id }
            val toRemove = engines.keys.filter { it !in activeIds }
            toRemove.forEach { id ->
                engines.remove(id)?.destroy()
            }
        }
    }

    private suspend fun ensureEngine(source: CustomAudioSource): LxScriptEngine? {
        engineMutex.withLock {
            engines[source.id]?.let { return it }

            val script = repository.readScript(source)
            if (script.isNullOrBlank()) {
                NPLogger.w(TAG, "音源脚本内容缺失: ${source.id}")
                return null
            }
            val eng = LxScriptEngine(appContext, script)
            val init = eng.start()
            if (!init.ok) {
                NPLogger.w(TAG, "音源引擎启动失败(${source.name}): ${init.error}")
                eng.destroy()
                return null
            }
            engines[source.id] = eng
            return eng
        }
    }

    /**
     * 构建符合 LX Music toOldMusicInfo 格式的 musicInfo。
     * 完全匹配 LX Mobile 传递给脚本 handler 的数据结构。
     */
    private fun buildMusicInfo(song: SongItem): JSONObject {
        val interval = formatDurationToLxInterval(song.durationMs)
        val types = JSONArray()
        val qualityTypes = JSONObject()
        return JSONObject().apply {
            put("songmid", song.id.toString())
            put("id", song.id.toString())
            put("name", song.name)
            put("singer", song.artist)
            put("source", CustomAudioSource.LX_SOURCE_NETEASE)
            put("albumName", song.album)
            put("albumId", song.albumId.toString())
            put("interval", interval)
            put("img", song.coverUrl ?: "")
            put("typeUrl", JSONObject())
            put("types", types)
            put("_types", qualityTypes)
        }
    }

    private fun formatDurationToLxInterval(durationMs: Long): String {
        if (durationMs <= 0L) return ""
        val totalSec = durationMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "$min:${sec.toString().padStart(2, '0')}"
    }

    companion object {
        /**
         * NeriPlayer 网易云音质 -> LX 音质。
         * LX 常见音质: 128k / 320k / flac / flac24bit / master
         */
        fun mapNeteaseQualityToLx(qualityKey: String): String = when (qualityKey) {
            "standard" -> "128k"
            "exhigh" -> "320k"
            "lossless" -> "flac"
            "hires" -> "flac24bit"
            "jyeffect", "sky", "jymaster" -> "flac24bit"
            else -> "320k"
        }
    }
}
