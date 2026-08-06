package com.mocharealm.accompanist.lyrics.core.parser

import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine

/**
 * Parser for NetEase Cloud Music YRC lyrics.
 *
 * Typical line format:
 * `[12580,3470](12580,250,0)难(12830,300,0)以...`
 *
 * Some payloads may also prepend JSON credit lines from the newer lyric API.
 * Those lines are ignored here because they are not part of timed karaoke data.
 */
object NeteaseYrcParser : ILyricsParser {
    private val lineRegex = Regex("""^\[(\d+),\s*(\d+)](.*)$""")
    private val syllableRegex = Regex("""\((\d+),\s*(\d+),\s*-?\d+\)([^()\r\n]*)""")

    override fun canParse(content: String): Boolean {
        return content.lineSequence().any { line ->
            val trimmedLine = line.trim()
            val match = lineRegex.matchEntire(trimmedLine) ?: return@any false
            syllableRegex.containsMatchIn(match.groupValues[3])
        }
    }

    override fun parse(lines: List<String>): SyncedLyrics {
        val parsedLines = lines.mapNotNull(::parseLine)
        return SyncedLyrics(lines = parsedLines)
    }

    private fun parseLine(rawLine: String): ISyncedLine? {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("{")) {
            return null
        }

        val match = lineRegex.matchEntire(line) ?: return null
        val lineStart = match.groupValues[1].toIntOrNull() ?: return null
        val lineDuration = match.groupValues[2].toIntOrNull() ?: return null
        val lineEnd = lineStart + lineDuration
        val content = match.groupValues[3]

        val rawSyllables = syllableRegex.findAll(content).mapNotNull { syllableMatch ->
            val rawStart = syllableMatch.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val duration = syllableMatch.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            KaraokeSyllable(
                content = syllableMatch.groupValues[3],
                start = rawStart,
                end = rawStart + duration
            )
        }.toList()

        if (rawSyllables.isEmpty()) {
            val plainText = content.trim()
            return if (plainText.isNotEmpty()) {
                SyncedLine(
                    content = plainText,
                    translation = null,
                    start = lineStart,
                    end = lineEnd
                )
            } else {
                null
            }
        }

        val syllables = normalizeSyllableSpacing(normalizeSyllableTimes(rawSyllables, lineStart))
        val effectiveStart = syllables.first().start
        val effectiveEnd = maxOf(lineEnd, syllables.last().end)
        return KaraokeLine.MainKaraokeLine(
            syllables = syllables,
            translation = null,
            alignment = KaraokeAlignment.Unspecified,
            start = effectiveStart,
            end = effectiveEnd
        )
    }

    // 网易云 YRC 数据里部分英文词会吞掉词尾空格 (如 `(..)in(..)the` 应为 "in the")
    // 逐字渲染时直接拼接就会粘连成 "inthe"; 此处在相邻两个 ASCII 单词音节间补回空格
    // CJK 逐字 (无词间空格) 与标点边界不是 ASCII 字母数字, 不受影响
    private fun normalizeSyllableSpacing(
        syllables: List<KaraokeSyllable>
    ): List<KaraokeSyllable> {
        if (syllables.size < 2) {
            return syllables
        }
        return syllables.mapIndexed { index, syllable ->
            if (index == syllables.lastIndex) {
                return@mapIndexed syllable
            }
            val current = syllable.content
            val next = syllables[index + 1].content
            val needsSpace = current.isNotEmpty() && next.isNotEmpty() &&
                !current.last().isWhitespace() && !next.first().isWhitespace() &&
                current.last().isAsciiWordChar() && next.first().isAsciiWordChar()
            if (needsSpace) syllable.copy(content = "$current ") else syllable
        }
    }

    private fun Char.isAsciiWordChar(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun normalizeSyllableTimes(
        syllables: List<KaraokeSyllable>,
        lineStart: Int
    ): List<KaraokeSyllable> {
        val usesRelativeTime = syllables.first().start < lineStart
        if (!usesRelativeTime) {
            return syllables
        }

        return syllables.map { syllable ->
            syllable.copy(
                start = lineStart + syllable.start,
                end = lineStart + syllable.end
            )
        }
    }
}
