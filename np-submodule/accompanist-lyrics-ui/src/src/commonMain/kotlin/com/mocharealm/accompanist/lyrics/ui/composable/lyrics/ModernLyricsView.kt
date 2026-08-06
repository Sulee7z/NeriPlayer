package com.mocharealm.accompanist.lyrics.ui.composable.lyrics

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics

/**
 * A higher-level wrapper for [KaraokeLyricsView].
 *
 * It owns the list state internally so app integrations only need to provide
 * parsed lyrics data and the current playback position.
 */
@Composable
fun ModernLyricsView(
    lyrics: SyncedLyrics,
    currentPositionMs: Int,
    modifier: Modifier = Modifier,
    onLineClicked: (ISyncedLine) -> Unit = {},
    onLinePressed: (ISyncedLine) -> Unit = onLineClicked,
    normalLineTextStyle: TextStyle = LocalTextStyle.current.copy(
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        textMotion = TextMotion.Animated
    ),
    accompanimentLineTextStyle: TextStyle = LocalTextStyle.current.copy(
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        textMotion = TextMotion.Animated
    ),
    textColor: Color = Color.White,
    blendMode: BlendMode = BlendMode.Plus,
    useBlurEffect: Boolean = true,
    showTranslation: Boolean = true,
    showPhonetic: Boolean = true,
    offset: Dp = 32.dp,
    keepAliveZone: Dp = 100.dp,
    blurDelta: Float = 3f,
    showDebugRectangles: Boolean = false
) {
    val listState = rememberLazyListState()
    KaraokeLyricsView(
        listState = listState,
        lyrics = lyrics,
        currentPosition = { currentPositionMs },
        onLineClicked = onLineClicked,
        onLinePressed = onLinePressed,
        modifier = modifier,
        normalLineTextStyle = normalLineTextStyle,
        accompanimentLineTextStyle = accompanimentLineTextStyle,
        textColor = textColor,
        blendMode = blendMode,
        useBlurEffect = useBlurEffect,
        showTranslation = showTranslation,
        showPhonetic = showPhonetic,
        offset = offset,
        keepAliveZone = keepAliveZone,
        blurDelta = blurDelta,
        showDebugRectangles = showDebugRectangles
    )
}
