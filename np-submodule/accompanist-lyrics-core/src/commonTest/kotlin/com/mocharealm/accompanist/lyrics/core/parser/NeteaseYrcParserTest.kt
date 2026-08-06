package com.mocharealm.accompanist.lyrics.core.parser

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NeteaseYrcParserTest {

    @Test
    fun `can detect yrc format`() {
        val content = """
            [12580,3470](12580,250,0)难(12830,300,0)以(13130,200,0)忘记
        """.trimIndent()

        assertTrue(NeteaseYrcParser.canParse(content))
        assertFalse(NeteaseYrcParser.canParse("[00:12.58]难以忘记"))
        assertFalse(NeteaseYrcParser.canParse("[30348,198]<0,33,0>(<33,33,0>And"))
    }

    @Test
    fun `parses absolute timed yrc syllables`() {
        val content = """
            [12580,3470](12580,250,0)难(12830,300,0)以(13130,200,0)忘记
        """.trimIndent()

        val result = NeteaseYrcParser.parse(content)
        val line = result.lines.single() as KaraokeLine.MainKaraokeLine

        assertEquals(3, line.syllables.size)
        assertEquals("难", line.syllables[0].content)
        assertEquals(12580, line.syllables[0].start)
        assertEquals(12830, line.syllables[0].end)
        assertEquals("忘记", line.syllables[2].content)
        assertEquals(13130, line.syllables[2].start)
        assertEquals(13330, line.syllables[2].end)
    }

    @Test
    fun `repairs missing spaces between english word syllables`() {
        // 网易云 YRC 真实样本: 部分英文词吞掉词尾空格 (she/in) , 逐字拼接会粘连成 shegot/inthe
        val content = """
            [11490,4740](11490,120,0)And (11610,240,0)she(11850,360,0)got (12210,1020,0)older
            [9420,2000](9420,990,0)fairest (10410,180,0)in(10590,150,0)the (10740,450,0)land
        """.trimIndent()

        val result = NeteaseYrcParser.parse(content)
        val first = result.lines[0] as KaraokeLine.MainKaraokeLine
        val second = result.lines[1] as KaraokeLine.MainKaraokeLine

        assertEquals("And she got older", first.syllables.joinToString("") { it.content })
        assertEquals("fairest in the land", second.syllables.joinToString("") { it.content })
    }

    @Test
    fun `keeps cjk syllables tightly joined without inserting spaces`() {
        // 中文逐字之间不能被插入空格
        val content = """
            [12580,3470](12580,250,0)难(12830,300,0)以(13130,200,0)忘记
        """.trimIndent()

        val result = NeteaseYrcParser.parse(content)
        val line = result.lines.single() as KaraokeLine.MainKaraokeLine

        assertEquals("难以忘记", line.syllables.joinToString("") { it.content })
    }

    @Test
    fun `parses relative timed yrc syllables`() {
        val content = """
            [1000,600](0,180,0)We(180,180,0) glow(360,240,0) now
        """.trimIndent()

        val result = NeteaseYrcParser.parse(content)
        val line = result.lines.single() as KaraokeLine.MainKaraokeLine

        assertEquals(1000, line.start)
        assertEquals(1600, line.end)
        assertEquals("We", line.syllables[0].content)
        assertEquals(1000, line.syllables[0].start)
        assertEquals(1180, line.syllables[0].end)
        assertEquals(" glow", line.syllables[1].content)
        assertEquals(" now", line.syllables[2].content)
    }
}
