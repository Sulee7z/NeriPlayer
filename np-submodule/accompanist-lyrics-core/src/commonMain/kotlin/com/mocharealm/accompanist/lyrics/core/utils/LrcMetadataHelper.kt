package com.mocharealm.accompanist.lyrics.core.utils

import com.mocharealm.accompanist.lyrics.core.model.Attributes

/**
 * Helper object for parsing and managing specific, known metadata from lyrics lines.
 * It identifies lines matching a [tag:value] format and processes only the tags
 * defined in the METADATA_TAGS set.
 */
object LrcMetadataHelper {

    // A set of known, standard metadata tags to be processed or removed.
    // Any other tags (like 'bg') will be ignored.
    private val METADATA_TAGS = setOf("ar", "ti", "al", "offset", "length")

    // This regex is still good for finding the general [tag:value] pattern.
    private val attributeParser = Regex("""^\[([a-zA-Z]+):\s*(.*)\]\s*$""")

    /**
     * Parses a list of lines to extract values for the known metadata tags.
     *
     * @param lines The list of strings from the lyrics file.
     * @return An [com.mocharealm.accompanist.lyrics.core.model.Attributes] object containing the parsed metadata.
     */
    fun parse(lines: List<String>): Attributes {
        val attributesMap = lines.mapNotNull { line ->
            attributeParser.find(line)?.destructured?.let { (tag, value) ->
                // Only consider the tag if it's in our known set.
                if (tag in METADATA_TAGS) {
                    tag.trim() to value.trim()
                } else {
                    null
                }
            }
        }.toMap()

        return Attributes(
            artist = attributesMap["ar"],
            title = attributesMap["ti"],
            album = attributesMap["al"],
            offset = attributesMap["offset"]?.toIntOrNull() ?: 0,
            duration = attributesMap["length"]?.toIntOrNull() ?: 0
        )
    }

    /**
     * Removes only the lines corresponding to known metadata tags.
     * Lines like [bg:...] will be ignored and therefore kept.
     *
     * @param lines The list of strings to filter.
     * @return A new list containing only non-metadata lines.
     */
    fun removeAttributes(lines: List<String>): List<String> {
        return lines.filterNot { line ->
            // Try to parse the line as a generic attribute.
            attributeParser.find(line)?.destructured?.let { (tag, _) ->
                // Check if the parsed tag is one of the known metadata tags.
                // If yes, this line should be removed (return true for filterNot).
                // If no (e.g., tag is 'bg'), this line should be kept (return false).
                tag in METADATA_TAGS
            } == true // If it doesn't match the pattern at all, keep it.
        }
    }

    // 常见的制作信息角色 (简繁 + 英文) , 用于识别"作词 : X""OP: Y"这类非歌词的元数据行
    private val CREDIT_ROLES = setOf(
        "作词", "作詞", "作曲", "编曲", "編曲", "制作人", "製作人", "制作", "製作",
        "出品", "出品人", "联合出品", "聯合出品", "营销", "營銷", "策划", "策劃",
        "企划", "企劃", "监制", "監製", "统筹", "統籌", "发行", "發行", "混音",
        "母带", "母帶", "录音", "錄音", "和声", "和聲", "和音", "配唱", "演唱",
        "原唱", "词", "詞", "曲", "吉他", "贝斯", "貝斯", "鼓", "键盘", "鍵盤",
        "弦乐", "弦樂", "录音师", "錄音師", "混音师", "混音師", "母带工程师",
        "制作公司", "版权", "版權", "鸣谢", "鳴謝", "特别鸣谢", "特別鳴謝",
        "op", "sp", "lyricist", "composer", "arranger", "producer", "mixing",
        "mastering", "recording", "vocal", "guitar", "bass", "drums", "keyboard",
        "strings"
    )

    /**
     * Detects credit/metadata content lines such as "作词 : 罗言" or "OP: 唯迹文化"
     *
     * Only treats a line as credit when the token before the separator is a known
     * production role, so ordinary lyrics that happen to contain a colon are kept.
     * Implemented without unicode-property regex to stay Kotlin-multiplatform safe.
     */
    fun isCreditLine(content: String): Boolean {
        val trimmed = content.trim()
        val separatorIndex = trimmed.indexOfFirst { it == ':' || it == '：' }
        if (separatorIndex <= 0) return false
        val role = trimmed.substring(0, separatorIndex).trim().lowercase()
        if (role.isEmpty() || role.length > 12) return false
        if (role.any { it.isWhitespace() }) return false
        if (trimmed.substring(separatorIndex + 1).isBlank()) return false
        return role in CREDIT_ROLES
    }
}