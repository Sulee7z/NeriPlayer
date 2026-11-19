package moe.ouom.neriplayer.platform.customsource

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
 * File: moe.ouom.neriplayer.core.customsource/CustomSourceRepository
 * Created: 2026/7/26
 *
 * 自定义音源脚本的持久化:脚本文件存 filesDir/custom_sources,
 * 元数据索引存单个 index.json。
 */

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.common.io.writeTextAtomically
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

private const val TAG = "NERI-CustomSourceRepo"
private const val DIR_NAME = "custom_sources"
private const val INDEX_FILE = "index.json"

class CustomSourceRepository(private val appContext: Context) {

    private val mutex = Mutex()
    private val baseDir: File by lazy {
        File(appContext.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
    }
    private val indexFile: File get() = File(baseDir, INDEX_FILE)

    private val _sources = MutableStateFlow<List<CustomAudioSource>>(emptyList())
    val sources: StateFlow<List<CustomAudioSource>> = _sources.asStateFlow()

    /**
     * 优先模式:true = 自定义音源优先(解析成功即用,失败回退官方);
     * false = 仅作回退(官方拿不到/试听片段时才用)。默认 false。
     */
    private val _priorityMode = MutableStateFlow(false)
    val priorityMode: StateFlow<Boolean> = _priorityMode.asStateFlow()

    private val modeFile: File get() = File(baseDir, "mode")
    @Volatile private var loaded = false

    /** 第一个启用的音源(向后兼容)。 */
    val activeSource: CustomAudioSource? get() = _sources.value.firstOrNull { it.enabled }

    /** 所有已启用的音源,按列表顺序(即导入顺序)。多源共存时依次尝试。 */
    val activeSources: List<CustomAudioSource> get() = _sources.value.filter { it.enabled }

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loaded) return@withLock
            _sources.value = readIndex()
            _priorityMode.value = runCatching {
                val mode = AtomicFile(modeFile)
                mode.openRead().bufferedReader().use { it.readText().trim() == "priority" }
            }.getOrDefault(false)
            loaded = true
        }
    }

    suspend fun setPriorityMode(priority: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            writeAtomic(modeFile, if (priority) "priority" else "fallback")
            _priorityMode.value = priority
        }
    }

    fun readScript(source: CustomAudioSource): String? {
        val f = scriptFile(source.scriptFileName)
        return if (f.exists() || File(f.path + ".bak").exists()) {
            AtomicFile(f).openRead().bufferedReader().use { it.readText() }
        } else null
    }

    /**
     * 导入一个脚本。返回导入后的音源(含解析元数据),失败抛异常。
     * [supportedSources] 可由调用方在运行脚本 inited 后回填。
     */
    suspend fun importScript(
        scriptContent: String,
        supportedSources: Map<String, List<String>> = emptyMap()
    ): CustomAudioSource = withContext(Dispatchers.IO) {
        mutex.withLock {
            val content = CustomSourceMetadataParser.normalizeForImport(scriptContent)
            val meta = CustomSourceMetadataParser.parse(content)
            fun contentId(text: String) = "cs_" + MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val id = contentId(content)
            val legacyBomId = contentId("\uFEFF$content")
            val fileName = "$id.js"
            val existing = readIndex().toMutableList()
            // An identical import must not rename, enable or reorder an existing source.
            existing.firstOrNull { it.id == id || it.id == legacyBomId }?.let { source ->
                if (!scriptFile(source.scriptFileName).exists()) {
                    writeAtomic(scriptFile(source.scriptFileName), content)
                }
                _sources.value = existing
                return@withLock source
            }
            writeAtomic(scriptFile(fileName), content)
            val source = CustomAudioSource(
                id = id,
                name = meta.name,
                version = meta.version,
                author = meta.author,
                description = meta.description,
                scriptFileName = fileName,
                supportedSources = supportedSources,
                enabled = false, // LX separates saving a script from selecting/executing it.
                importedAt = System.currentTimeMillis(),
                homepage = meta.homepage
            )
            existing.add(source)
            writeIndex(existing)
            _sources.value = existing
            source
        }
    }

    suspend fun updateSupportedSources(id: String, supportedSources: Map<String, List<String>>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val list = readIndex().map {
                    if (it.id == id) it.copy(supportedSources = supportedSources) else it
                }
                writeIndex(list)
                _sources.value = list
            }
        }

    /** 单独启用/禁用某个音源(多源共存,互不影响)。 */
    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = readIndex().map { if (it.id == id) it.copy(enabled = enabled) else it }
            writeIndex(list)
            _sources.value = list
        }
    }

    /** 重命名音源(仅展示名,脚本文件名不变)。 */
    suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return@withContext
        mutex.withLock {
            val list = readIndex().map { if (it.id == id) it.copy(name = trimmed) else it }
            writeIndex(list)
            _sources.value = list
        }
    }

    /**
     * 调整音源顺序(列表顺序即回退尝试顺序)。
     * [direction] = -1 上移(优先级更高), +1 下移。
     */
    suspend fun move(id: String, direction: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = readIndex().toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) return@withContext
            val target = index + direction
            if (target < 0 || target >= list.size) return@withContext
            val item = list.removeAt(index)
            list.add(target, item)
            writeIndex(list)
            _sources.value = list
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = readIndex()
            val target = list.firstOrNull { it.id == id }
            val remaining = list.filter { it.id != id }
            writeIndex(remaining)
            _sources.value = remaining
            target?.let { scriptFile(it.scriptFileName).delete() }
        }
    }

    private fun readIndex(): List<CustomAudioSource> {
        val atomic = AtomicFile(indexFile)
        if (!indexFile.exists() && !File(indexFile.path + ".bak").exists()) return emptyList()
        return try {
            val arr = JSONArray(atomic.openRead().bufferedReader().use { it.readText() })
            val out = ArrayList<CustomAudioSource>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let {
                    val source = CustomAudioSource.fromJson(it)
                    scriptFile(source.scriptFileName)
                    out.add(source)
                }
            }
            out
        } catch (e: Exception) {
            NPLogger.e(TAG, "读取音源索引失败，已保留原文件", e)
            throw IOException("读取音源索引失败，已保留原文件", e)
        }
    }

    private fun writeIndex(list: List<CustomAudioSource>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        writeAtomic(indexFile, arr.toString())
    }

    private fun scriptFile(name: String): File {
        require(name.matches(Regex("cs_[a-zA-Z0-9_]+\\.js"))) { "无效的音源脚本路径" }
        return File(baseDir, name)
    }

    private fun writeAtomic(file: File, text: String) {
        // Recover any legacy .bak before switching to the shared checked atomic writer.
        val backup = File(file.path + ".bak")
        if (backup.exists()) AtomicFile(file).openRead().close()
        file.writeTextAtomically(text)
    }
}
