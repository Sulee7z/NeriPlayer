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
import kotlin.random.Random

private const val TAG = "NERI-CustomSourceMgr"
private const val MAX_RESOLVE_RETRIES = 2
private const val RETRY_DELAY_BASE_MS = 1_500L
private const val RETRY_DELAY_MAX_MS = 5_000L

class CustomSourceManager(
    private val appContext: Context,
    val repository: CustomSourceRepository
) {
    private val engineMutex = Mutex()
    // 多源共存:每个启用音源各持有一个引擎,按 id 缓存
    private val engines = HashMap<String, LxScriptEngine>()

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
        val baseMusicInfo = buildMusicInfo(song)

        for (active in actives) {
            val eng = ensureEngine(active) ?: continue
            val platforms = resolvePlatformOrder(active)

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
                    val url = try {
                        eng.getMusicUrl(source = platform, quality = lxQuality, musicInfo = musicInfo)
                    } catch (e: Exception) {
                        NPLogger.w(TAG, "自定义音源解析异常(${active.name}/$platform/$lxQuality)", e)
                        null
                    }
                    if (!url.isNullOrBlank()) {
                        NPLogger.i(TAG, "自定义音源命中: ${active.name}/$platform quality=$lxQuality id=${song.id} retries=$retries")
                        return url
                    }
                    if (retries >= MAX_RESOLVE_RETRIES) break
                    retries++
                    val backoffMs = Random.nextLong(RETRY_DELAY_BASE_MS, RETRY_DELAY_MAX_MS)
                    NPLogger.d(TAG, "自定义音源重试: ${active.name}/$platform retry=$retries backoffMs=$backoffMs name=${song.name}")
                    delay(backoffMs)
                }
            }
        }
        NPLogger.w(TAG, "自定义音源全部失败: id=${song.id} name=${song.name} artist=${song.artist}")
        return null
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
