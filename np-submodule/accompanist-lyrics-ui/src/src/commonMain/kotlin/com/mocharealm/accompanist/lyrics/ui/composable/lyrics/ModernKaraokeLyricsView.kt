package com.mocharealm.accompanist.lyrics.ui.composable.lyrics

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics

/**
 * Tuned wrapper around [KaraokeLyricsView] for a denser, more modern player layout.
 *
 * It keeps the original renderer API intact while exposing a higher-level preset with:
 * - tighter focus / de-focus scaling
 * - stronger depth separation on inactive lines
 * - larger viewport padding for centered lyrics
 * - additive blend for brighter, glass-like highlights
 */
@Composable
fun ModernKaraokeLyricsView(
    listState: LazyListState,
    lyrics: SyncedLyrics,
    currentPosition: () -> Int,
    renderCurrentPosition: (() -> Int)? = null,
    onLineClicked: (ISyncedLine) -> Unit,
    onLinePressed: (ISyncedLine) -> Unit,
    modifier: Modifier = Modifier,
    normalLineTextStyle: TextStyle = LocalTextStyle.current.copy(
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        textMotion = TextMotion.Animated,
        lineHeight = 38.sp
    ),
    accompanimentLineTextStyle: TextStyle = LocalTextStyle.current.copy(
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
        textMotion = TextMotion.Animated,
        lineHeight = 24.sp
    ),
    textColor: Color = Color.White,
    showTranslation: Boolean = true,
    showPhonetic: Boolean = true,
    useBlurEffect: Boolean = true,
    animateViewportScroll: Boolean = false,
    offset: Dp = 72.dp,
    keepAliveZone: Dp = 128.dp,
    bottomContentInset: Dp = 0.dp,
    blurDelta: Float = 2.6f,
    topFadeLength: Dp = 20.dp,
    bottomFadeLength: Dp = 100.dp,
    showDebugRectangles: Boolean = false,
    useAdditiveBlend: Boolean = true
) {
    val resolvedModifier = if (useAdditiveBlend) {
        modifier.graphicsLayer {
            blendMode = BlendMode.Plus
            compositingStrategy = CompositingStrategy.Offscreen
        }
    } else {
        modifier
    }

    KaraokeLyricsView(
        listState = listState,
        lyrics = lyrics,
        currentPosition = currentPosition,
        renderCurrentPosition = renderCurrentPosition,
        onLineClicked = onLineClicked,
        onLinePressed = onLinePressed,
        modifier = resolvedModifier,
        normalLineTextStyle = normalLineTextStyle,
        accompanimentLineTextStyle = accompanimentLineTextStyle,
        textColor = textColor,
        showTranslation = showTranslation,
        showPhonetic = showPhonetic,
        animateViewportScroll = animateViewportScroll,
        focusedLineScale = 1.015f,
        unfocusedLineScale = 0.965f,
        activeLineAlpha = 1f,
        inactiveLineAlpha = 0.28f,
        useBlurEffect = useBlurEffect,
        offset = offset,
        keepAliveZone = keepAliveZone,
        bottomContentInset = bottomContentInset,
        blurDelta = blurDelta,
        topFadeLength = topFadeLength,
        bottomFadeLength = bottomFadeLength,
        showDebugRectangles = showDebugRectangles
    )
}
