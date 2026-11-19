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
 * File: moe.ouom.neriplayer.core.customsource/CustomAudioSource
 * Created: 2026/7/26
 *
 * 自定义音源(兼容洛雪音乐 LX Music 自定义源脚本)的元数据模型。
 */

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个已导入的自定义音源脚本。
 *
 * [scriptFileName] 指向 filesDir/custom_sources 下的脚本文件,内容为原始 JS。
 */
data class CustomAudioSource(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val scriptFileName: String,
    /** 脚本声明支持的平台(LX source key -> 支持的音质列表)。运行脚本 inited 后回填。 */
    val supportedSources: Map<String, List<String>> = emptyMap(),
    val enabled: Boolean = false,
    val importedAt: Long = 0L,
    val homepage: String = ""
) {
    fun supportsNetease(): Boolean = supportedSources.containsKey(LX_SOURCE_NETEASE)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("version", version)
        put("author", author)
        put("description", description)
        put("scriptFileName", scriptFileName)
        put("enabled", enabled)
        put("importedAt", importedAt)
        put("homepage", homepage)
        val sources = JSONObject()
        supportedSources.forEach { (key, qualities) ->
            sources.put(key, JSONArray(qualities))
        }
        put("supportedSources", sources)
    }

    companion object {
        const val LX_SOURCE_NETEASE = "wy"
        const val LX_SOURCE_TENCENT = "tx"
        const val LX_SOURCE_KUGOU = "kg"
        const val LX_SOURCE_KUWO = "kw"
        const val LX_SOURCE_MIGU = "mg"

        fun fromJson(obj: JSONObject): CustomAudioSource {
            val sources = mutableMapOf<String, List<String>>()
            obj.optJSONObject("supportedSources")?.let { s ->
                val keys = s.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = s.optJSONArray(k) ?: JSONArray()
                    val list = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) list.add(arr.optString(i))
                    sources[k] = list
                }
            }
            return CustomAudioSource(
                id = obj.optString("id"),
                name = obj.optString("name"),
                version = obj.optString("version"),
                author = obj.optString("author"),
                description = obj.optString("description"),
                scriptFileName = obj.optString("scriptFileName"),
                supportedSources = sources,
                enabled = obj.optBoolean("enabled", false),
                importedAt = obj.optLong("importedAt", 0L),
                homepage = obj.optString("homepage")
            )
        }
    }
}

/** 解析脚本头部注释里的元数据(LX 脚本头部形如 /** @name ... @version ... */)。 */
object CustomSourceMetadataParser {
    // LX Mobile's online importer allows up to 9,000,000 characters. Bound bytes
    // here as well so file, URL and paste imports have one Android memory budget.
    const val MAX_SCRIPT_BYTES = 9_000_000
    private val header = Regex("\\A\\s*/\\*[\\s\\S]*?\\*/")
    private val limits = mapOf("name" to 24, "description" to 36, "author" to 56,
        "homepage" to 1024, "version" to 36)

    /** LX imports the leading comment, not the presence of unobfuscated API names. */
    fun normalizeForImport(script: String): String {
        val content = script.removePrefix("\uFEFF")
        require(content.isNotBlank()) { "脚本内容为空" }
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_SCRIPT_BYTES) { "脚本超过 9 MB" }
        require(header.containsMatchIn(content)) { "不是有效的 LX 音源脚本：缺少开头的脚本说明注释" }
        return content
    }

    fun isImportable(script: String): Boolean = runCatching { normalizeForImport(script) }.isSuccess

    fun parse(script: String): ParsedMeta {
        val head = header.find(script.removePrefix("\uFEFF"))?.value.orEmpty()
            .removePrefix("/*").removeSuffix("*/")
        val values = mutableMapOf<String, String>()
        val tag = Regex("^\\s*\\*?\\s*@(\\w+)[ \\t]+(.+)$")
        head.lineSequence().forEach { line ->
            val match = tag.find(line) ?: return@forEach
            val key = match.groupValues[1]
            val limit = limits[key] ?: return@forEach
            val value = match.groupValues[2].trim()
            values[key] = if (value.length > limit) value.take(limit) + "..." else value
        }
        return ParsedMeta(values["name"].orEmpty().ifBlank { "自定义音源" },
            values["version"].orEmpty(), values["author"].orEmpty(),
            values["description"].orEmpty(), values["homepage"].orEmpty())
    }

    data class ParsedMeta(
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val homepage: String = ""
    )
}
