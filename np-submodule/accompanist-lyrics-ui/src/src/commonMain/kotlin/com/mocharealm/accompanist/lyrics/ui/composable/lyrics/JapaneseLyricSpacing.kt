package com.mocharealm.accompanist.lyrics.ui.composable.lyrics

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val JapaneseKanaRanges = listOf(
    '\u3040'..'\u30FF',
    '\u31F0'..'\u31FF',
    '\uFF66'..'\uFF9F'
)

internal fun containsJapaneseKana(text: String): Boolean {
    return text.any { char -> JapaneseKanaRanges.any { range -> char in range } }
}

internal fun resolveJapaneseLyricTranslationTopPadding(lyricText: String): Dp {
    return if (containsJapaneseKana(lyricText)) 3.dp else 0.dp
}
