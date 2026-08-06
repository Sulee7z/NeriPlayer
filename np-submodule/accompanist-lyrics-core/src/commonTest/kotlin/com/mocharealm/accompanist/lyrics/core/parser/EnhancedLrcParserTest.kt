package com.mocharealm.accompanist.lyrics.core.parser

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.exporter.EnhancedLrcExporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnhancedLrcParserTest {

    @Test
    fun testBilingualLrcPairsOriginalWithTranslation() {
        val lrc = """
            [00:10.00]English line one
            [00:10.00]中文第一行
            [00:13.00]English line two
            [00:13.00]中文第二行
        """.trimIndent().split("\n")

        val data = EnhancedLrcParser.parse(lrc)

        assertEquals(2, data.lines.size)
        val first = data.lines[0] as SyncedLine
        val second = data.lines[1] as SyncedLine
        assertEquals("English line one", first.content)
        assertEquals("中文第一行", first.translation)
        assertEquals("English line two", second.content)
        assertEquals("中文第二行", second.translation)
    }

    @Test
    fun testCreditLineDoesNotStealTranslationOnSharedTimestamp() {
        // 制作信息行与第一句正文共享时间戳; 修复前 credit 会窃取第一句英文做译文, 导致整体错位一行
        val lrc = """
            [00:15.00]作词 : Anson Seabra
            [00:15.00]I've been on the low
            [00:15.00]我一直很低落
            [00:18.00]Keep your head up
            [00:18.00]抬起头来
        """.trimIndent().split("\n")

        val data = EnhancedLrcParser.parse(lrc)

        assertEquals(3, data.lines.size)
        val credit = data.lines[0] as SyncedLine
        val firstLyric = data.lines[1] as SyncedLine
        val secondLyric = data.lines[2] as SyncedLine
        assertEquals("作词 : Anson Seabra", credit.content)
        assertNull(credit.translation)
        assertEquals("I've been on the low", firstLyric.content)
        assertEquals("我一直很低落", firstLyric.translation)
        assertEquals("Keep your head up", secondLyric.content)
        assertEquals("抬起头来", secondLyric.translation)
    }

    @Test
    fun testCreditOnlyLyricsProduceNoFalseTranslation() {
        val lrc = """
            [00:00.00]作词 : Someone
            [00:00.00]作曲 : Someone
            [00:15.00]Only English here
            [00:18.00]Another english line
        """.trimIndent().split("\n")

        val data = EnhancedLrcParser.parse(lrc)

        assertEquals(4, data.lines.size)
        data.lines.forEach { line ->
            assertNull((line as SyncedLine).translation)
        }
    }

    @Test
    fun testParseBgWithTranslation() {
        val lrc = """
            [00:10.00]<00:10.00>Main <00:10.50>Lyrics<00:10.70>
            [00:10.00]主歌词翻译
            [bg: <00:10.00>Back<00:10.50>ground<00:11.00>]
            [bg: <00:10.00>背景音翻译<00:11.00>]
        """.trimIndent().split("\n")
        
        val data = EnhancedLrcParser.parse(lrc)
        
        assertEquals(1, data.lines.size)
        val line = data.lines[0] as KaraokeLine.MainKaraokeLine
        assertEquals("主歌词翻译", line.translation)
        
        val bg = line.accompanimentLines?.first()
        assertNotNull(bg)
        assertEquals("Background", bg.syllables.joinToString("") { it.content }.trim())
        assertEquals("背景音翻译", bg.translation)
    }

    @Test
    fun testELRCRoundTrip() {
        val lrc = """
            [ti:Test Title]
            [00:01.00]<00:01.00>Hello <00:02.00>World<00:02.50>
            [00:01.00]你好世界
            [bg: <00:01.50>Chorus<00:02.00>]
            [bg: <00:01.50>合唱<00:02.00>]
        """.trimIndent().split("\n")
        
        val parsed = EnhancedLrcParser.parse(lrc)
        val exported = EnhancedLrcExporter.export(parsed)
        
        val reParsed = EnhancedLrcParser.parse(exported.split("\n"))
        
        assertEquals(parsed.lines.size, reParsed.lines.size)
        val p1 = parsed.lines[0] as KaraokeLine.MainKaraokeLine
        val p2 = reParsed.lines[0] as KaraokeLine.MainKaraokeLine
        
        assertEquals(p1.translation, p2.translation)
        assertEquals(p1.accompanimentLines?.size, p2.accompanimentLines?.size)
        assertEquals(p1.accompanimentLines?.first()?.translation, p2.accompanimentLines?.first()?.translation)
    }

    @Test
    fun testOutOfOrderTimestampsDoNotThrowAndKeepOtherLines() {
        // 乱序时间戳 (第二行早于第一行) 修复前会让 rearrangeUncheckedLineTime 产生 end < start
        // 触发 SyncedLine 的 require(end>=start) 抛异常, 导致 AutoParser/parse 失败, 整份歌词被丢弃
        // 修复后应容错解析并保留全部行, 异常行的时长被钳制为非负
        val lrc = """
            [01:00.00]Later line
            [00:30.00]Earlier line
            [01:30.00]Final line
        """.trimIndent().split("\n")

        val data = EnhancedLrcParser.parse(lrc)

        assertEquals(3, data.lines.size)
        val first = data.lines[0] as SyncedLine
        val second = data.lines[1] as SyncedLine
        val third = data.lines[2] as SyncedLine
        assertEquals("Later line", first.content)
        assertEquals("Earlier line", second.content)
        assertEquals("Final line", third.content)
        // 乱序行的 end 被钳制为不小于 start, 时长非负, 不再触发构造异常
        assertTrue(first.duration >= 0)
        assertTrue(first.end >= first.start)
    }

    @Test
    fun testModelConstructionToleratesEndBeforeStart() {
        // 直接验证模型层去掉硬 require 后的容错: end < start 不再抛异常, 时长钳制为 0
        val synced = SyncedLine(
            content = "malformed",
            translation = null,
            start = 200,
            end = 100
        )
        assertEquals(0, synced.duration)

        val main = KaraokeLine.MainKaraokeLine(
            syllables = listOf(KaraokeSyllable("x", 100, 200)),
            translation = null,
            alignment = KaraokeAlignment.Unspecified,
            start = 200,
            end = 100
        )
        assertEquals(0, main.duration)

        val accompaniment = KaraokeLine.AccompanimentKaraokeLine(
            syllables = listOf(KaraokeSyllable("y", 100, 200)),
            translation = null,
            alignment = KaraokeAlignment.Unspecified,
            start = 200,
            end = 100
        )
        assertEquals(0, accompaniment.duration)
    }
}
