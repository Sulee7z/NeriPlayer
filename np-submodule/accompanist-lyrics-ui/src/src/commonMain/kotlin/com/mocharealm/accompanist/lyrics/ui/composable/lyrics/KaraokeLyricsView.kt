package com.mocharealm.accompanist.lyrics.ui.composable.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastRoundToInt
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.ui.utils.isRtl
import com.mocharealm.accompanist.lyrics.ui.utils.modifier.springPlacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

internal data class FocusState(
    val firstIndex: Int,
    val allIndices: List<Int>,
    val activeInterludeIndex: Int?,
    val activeIntro: Boolean
)

private const val InitialPlacementSuppressionMs = 80L
private const val FocusFollowPlacementSuppressionMs = 0L
private const val FocusJumpPlacementSuppressionMs = 220L
private const val FocusedLineAlignmentCorrectionPasses = 18
private const val FocusedLineAlignmentTolerancePx = 1f
private const val ManualViewportRecenterPlacementSuppressionMs = 180L
private const val ManualViewportRecenterMaxTotalDurationMs = 900
private const val ManualViewportRecenterMainMinDurationMs = 260
private const val ManualViewportRecenterMainMaxDurationMs = 760
private const val ManualViewportRecenterMainBaseDurationMs = 220
private const val ManualViewportRecenterMainDistanceDivisorPx = 3.8f
private const val ManualViewportRecenterRefineMinDurationMs = 120
private const val ManualViewportRecenterRefineMaxDurationMs = 180
private const val ManualViewportRecenterRefineBaseDurationMs = 90
private const val ManualViewportRecenterRefineDistanceDivisorPx = 10f
private const val ManualViewportRecenterMaxPasses = 3
private const val VisibleItemStrideFallbackPx = 96f
private val ManualViewportRecenterEasing = CubicBezierEasing(0.18f, 0.96f, 0.28f, 1f)

internal enum class FocusedLineScrollMode {
    Snap,
    Animate
}

internal fun resolveFocusedLineScrollMode(
    previousAutoScrollIndex: Int?,
    targetIndex: Int
): FocusedLineScrollMode {
    return if (previousAutoScrollIndex == null || targetIndex < 0) {
        FocusedLineScrollMode.Snap
    } else {
        // 首次布局可以直接对齐，后续包括大跨度 seek 都应保留平滑滚动
        FocusedLineScrollMode.Animate
    }
}

internal fun resolveFocusedLinePlacementSuppressionMs(
    previousAutoScrollIndex: Int?,
    targetIndex: Int
): Long {
    if (targetIndex < 0) {
        return 0L
    }
    if (previousAutoScrollIndex == null) {
        return InitialPlacementSuppressionMs
    }
    val focusShiftDistance = (targetIndex - previousAutoScrollIndex).absoluteValue
    return when {
        focusShiftDistance == 0 -> 0L
        focusShiftDistance == 1 -> {
            // 正常逐句播放要保留 accompanist 原本的 placement spring，
            // 否则会把 Apple Music 风格的弹性跟随压成线性平移
            FocusFollowPlacementSuppressionMs
        }
        else -> {
            // seek 后内部显隐/高度动画还在跑，提前恢复 placement spring 会把相邻歌词压到一起
            FocusJumpPlacementSuppressionMs
        }
    }
}

internal fun shouldRealignFocusedLineAfterLayout(
    previousAutoScrollIndex: Int?,
    targetIndex: Int
): Boolean {
    if (targetIndex < 0) {
        return false
    }
    if (previousAutoScrollIndex == null) {
        return true
    }
    return (targetIndex - previousAutoScrollIndex).absoluteValue > 1
}

internal fun shouldSuppressLinePlacementAnimation(
    isManualScrolling: Boolean,
    suppressPlacementAnimation: Boolean
): Boolean {
    return isManualScrolling || suppressPlacementAnimation
}

internal fun shouldSuppressFocusedLinePlacementAnimation(
    useManualViewportRecenter: Boolean,
    animateViewportScroll: Boolean,
    placementSuppressionMs: Long,
    targetItemVisible: Boolean,
    scrollMode: FocusedLineScrollMode
): Boolean {
    if (useManualViewportRecenter) {
        return true
    }
    if (animateViewportScroll || placementSuppressionMs > 0L) {
        return true
    }
    return !targetItemVisible && scrollMode == FocusedLineScrollMode.Animate
}

internal fun shouldWaitForManualViewportRecenter(
    manualViewportRecenterPending: Boolean,
    manualViewportRecenterTriggerIndex: Int?,
    animateViewportScroll: Boolean,
    currentFocusIndex: Int
): Boolean {
    if (!manualViewportRecenterPending || animateViewportScroll) {
        return false
    }
    val triggerIndex = manualViewportRecenterTriggerIndex ?: return true
    return currentFocusIndex == triggerIndex
}

internal fun shouldStartManualViewportRecenter(
    manualViewportRecenterPending: Boolean,
    manualViewportRecenterTriggerIndex: Int?,
    animateViewportScroll: Boolean,
    currentFocusIndex: Int
): Boolean {
    if (!manualViewportRecenterPending || animateViewportScroll) {
        return false
    }
    val triggerIndex = manualViewportRecenterTriggerIndex ?: return false
    return currentFocusIndex != triggerIndex
}

internal fun resolveManualViewportRecenterMainDurationMs(distancePx: Float): Int {
    val normalizedDistancePx = abs(distancePx)
    return (
        ManualViewportRecenterMainBaseDurationMs +
            normalizedDistancePx / ManualViewportRecenterMainDistanceDivisorPx
        ).roundToInt().coerceIn(
            ManualViewportRecenterMainMinDurationMs,
            ManualViewportRecenterMainMaxDurationMs
        )
}

internal fun resolveManualViewportRecenterRefineDurationMs(distancePx: Float): Int {
    val normalizedDistancePx = abs(distancePx)
    return (
        ManualViewportRecenterRefineBaseDurationMs +
            normalizedDistancePx / ManualViewportRecenterRefineDistanceDivisorPx
        ).roundToInt().coerceIn(
            ManualViewportRecenterRefineMinDurationMs,
            ManualViewportRecenterRefineMaxDurationMs
        )
}

internal fun shouldAnimateVisibleFocusedLineScroll(
    previousAutoScrollIndex: Int?,
    targetIndex: Int
): Boolean {
    if (previousAutoScrollIndex == null || targetIndex < 0) {
        return false
    }
    return (targetIndex - previousAutoScrollIndex).absoluteValue > 1
}

internal fun resolveFocusedLineViewportDelta(
    itemOffset: Int,
    viewportStartOffset: Int,
    stableOffsetPx: Int,
    keepAliveZonePx: Float
): Float {
    return itemOffset - (viewportStartOffset + stableOffsetPx + keepAliveZonePx)
}

private fun resolveAverageVisibleItemStridePx(visibleItems: List<LazyListItemInfo>): Float {
    val sampledStridePx = visibleItems.zipWithNext { first, second ->
        (second.offset - first.offset).absoluteValue.toFloat()
    }.average().toFloat()
    if (sampledStridePx > 0f) {
        return sampledStridePx
    }
    val averageSizePx = visibleItems.map { it.size }.average().toFloat()
    return averageSizePx.takeIf { it > 0f } ?: VisibleItemStrideFallbackPx
}

private fun LazyListState.resolveApproximateFocusedLineViewportDelta(
    targetIndex: Int,
    stableOffsetPx: Int,
    keepAliveZonePx: Float
): Float {
    val visibleItems = layoutInfo.visibleItemsInfo
    val targetItem = visibleItems.firstOrNull { it.index == targetIndex }
    if (targetItem != null) {
        return resolveFocusedLineViewportDelta(
            itemOffset = targetItem.offset,
            viewportStartOffset = layoutInfo.viewportStartOffset,
            stableOffsetPx = stableOffsetPx,
            keepAliveZonePx = keepAliveZonePx
        )
    }
    if (visibleItems.isEmpty()) {
        return 0f
    }

    val viewportAnchor = layoutInfo.viewportStartOffset + stableOffsetPx + keepAliveZonePx
    val averageStridePx = resolveAverageVisibleItemStridePx(visibleItems)
    val firstVisibleItem = visibleItems.first()
    val lastVisibleItem = visibleItems.last()

    return when {
        targetIndex < firstVisibleItem.index -> {
            val itemsAway = (firstVisibleItem.index - targetIndex).toFloat()
            val estimatedOffset = firstVisibleItem.offset - (itemsAway * averageStridePx)
            estimatedOffset - viewportAnchor
        }

        targetIndex > lastVisibleItem.index -> {
            val itemsAway = (targetIndex - lastVisibleItem.index).toFloat()
            val estimatedOffset = lastVisibleItem.offset +
                lastVisibleItem.size +
                ((itemsAway - 1f).coerceAtLeast(0f) * averageStridePx)
            estimatedOffset - viewportAnchor
        }

        else -> 0f
    }
}

private suspend fun LazyListState.animateFocusedLineManualRecenter(
    targetIndex: Int,
    targetScrollOffset: Int,
    stableOffsetPx: Int,
    keepAliveZonePx: Float,
    tolerancePx: Float = FocusedLineAlignmentTolerancePx
) {
    stopScroll()
    var remainingDurationMs = ManualViewportRecenterMaxTotalDurationMs

    fun isTargetVisible(): Boolean = layoutInfo.visibleItemsInfo.any { it.index == targetIndex }

    repeat(ManualViewportRecenterMaxPasses) { passIndex ->
        val delta = resolveApproximateFocusedLineViewportDelta(
            targetIndex = targetIndex,
            stableOffsetPx = stableOffsetPx,
            keepAliveZonePx = keepAliveZonePx
        )
        if (abs(delta) <= tolerancePx) {
            return
        }

        val requestedDurationMs = if (passIndex == 0) {
            resolveManualViewportRecenterMainDurationMs(delta)
        } else {
            resolveManualViewportRecenterRefineDurationMs(delta)
        }
        val durationMillis = requestedDurationMs.coerceAtMost(remainingDurationMs)
        if (durationMillis <= 0) {
            return
        }

        animateScrollBy(
            value = delta,
            animationSpec = tween(
                durationMillis = durationMillis,
                easing = ManualViewportRecenterEasing
            )
        )
        remainingDurationMs = (remainingDurationMs - durationMillis).coerceAtLeast(0)

        if (isTargetVisible()) {
            val exactDelta = resolveApproximateFocusedLineViewportDelta(
                targetIndex = targetIndex,
                stableOffsetPx = stableOffsetPx,
                keepAliveZonePx = keepAliveZonePx
            )
            if (abs(exactDelta) <= tolerancePx) {
                return
            }
            if (passIndex == ManualViewportRecenterMaxPasses - 1 ||
                remainingDurationMs < ManualViewportRecenterRefineMinDurationMs
            ) {
                scrollBy(exactDelta)
                return
            }
        }
    }

    val finalDelta = resolveApproximateFocusedLineViewportDelta(
        targetIndex = targetIndex,
        stableOffsetPx = stableOffsetPx,
        keepAliveZonePx = keepAliveZonePx
    )
    if (isTargetVisible()) {
        if (abs(finalDelta) > tolerancePx) {
            scrollBy(finalDelta)
        }
        return
    }

    // 这里只保留兜底对齐，且整个过程 placement 仍被压制，不会再触发旧的掉落动画
    scrollToItem(targetIndex, targetScrollOffset)
}

private suspend fun LazyListState.realignFocusedLineAfterLayout(
    targetIndex: Int,
    stableOffsetPx: Int,
    keepAliveZonePx: Float,
    correctionPasses: Int = FocusedLineAlignmentCorrectionPasses,
    tolerancePx: Float = FocusedLineAlignmentTolerancePx
) {
    repeat(correctionPasses) {
        withFrameNanos { }
        val targetItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex } ?: return
        val delta = resolveFocusedLineViewportDelta(
            itemOffset = targetItem.offset,
            viewportStartOffset = layoutInfo.viewportStartOffset,
            stableOffsetPx = stableOffsetPx,
            keepAliveZonePx = keepAliveZonePx
        )
        if (abs(delta) <= tolerancePx) {
            return
        }
        scrollBy(delta)
    }
}

/**
 * A comprehensive lyrics view that supports Karaoke and Synced lyrics with advanced rendering.
 *
 * This composable handles:
 * - Scrolling and auto-scrolling to the current line
 * - Rendering karaoke lines with syllable-level timing and animations
 * - Rendering synced lines
 * - Displaying breathing dots during instrumental interludes
 * - Determining active and accompaniment lines
 *
 * @param listState The scroll state for the lazy list.
 * @param lyrics The lyrics data to display.
 * @param currentPosition A lambda returning the current playback position in milliseconds.
 * @param onLineClicked Callback when a line is clicked (seek to position).
 * @param onLinePressed Callback when a line is long-pressed (share/menu).
 * @param modifier The modifier to apply to the layout.
 * @param normalLineTextStyle The style for normal text lines.
 * @param accompanimentLineTextStyle The style for accompaniment/background vocals lines.
 * @param textColor The primary text color.
 * @param breathingDotsDefaults Styling defaults for the breathing dots.
 * @param blendMode The blend mode used for rendering text (e.g., [BlendMode.Plus] for glowing effects).
 * @param useBlurEffect Whether to apply blur effect to non-active lines.
 * @param offset The vertical padding/offset at the start and end of the list.
 * @param showDebugRectangles Debug flag to draw bounding boxes around glyphs.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun KaraokeLyricsView(
    listState: LazyListState,
    lyrics: SyncedLyrics,
    currentPosition: () -> Int,
    renderCurrentPosition: (() -> Int)? = null,
    onLineClicked: (ISyncedLine) -> Unit,
    onLinePressed: (ISyncedLine) -> Unit,
    modifier: Modifier = Modifier,
    normalLineTextStyle: TextStyle = LocalTextStyle.current.copy(
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        textMotion = TextMotion.Animated,
    ),
    accompanimentLineTextStyle: TextStyle = LocalTextStyle.current.copy(
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        textMotion = TextMotion.Animated,
    ),
    textColor: Color = Color.White,
    breathingDotsDefaults: KaraokeBreathingDotsDefaults = KaraokeBreathingDotsDefaults(),
    phoneticTextStyle: TextStyle = normalLineTextStyle.copy(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
    ),
//    TODO: expose it
//    verticalFadeBrush: Brush = Brush.verticalGradient(
//        0f to Color.White.copy(0f),
//        0.05f to Color.White,
//        0.6f to Color.White,
//        1f to Color.White.copy(0f)
//    ),
    blendMode: BlendMode = BlendMode.Plus,
    useBlurEffect: Boolean = true,
    showTranslation: Boolean = true,
    showPhonetic: Boolean = true,
    animateViewportScroll: Boolean = false,
    focusedLineScale: Float = 1f,
    unfocusedLineScale: Float = 0.98f,
    activeLineAlpha: Float = 1f,
    inactiveLineAlpha: Float = 0.4f,
    offset: Dp = 32.dp,
    keepAliveZone: Dp = 100.dp,
    bottomContentInset: Dp = 0.dp,
    blurDelta: Float = 3f,
    topFadeLength: Dp = 20.dp,
    bottomFadeLength: Dp = 100.dp,
    showDebugRectangles: Boolean = false
) {
    val density = LocalDensity.current
    val stableNormalTextStyle = remember(normalLineTextStyle) { normalLineTextStyle }
    val stableAccompanimentTextStyle =
        remember(accompanimentLineTextStyle) { accompanimentLineTextStyle }
    val stablePhoneticTextStyle = remember(phoneticTextStyle) { phoneticTextStyle }
    val stableOffset = remember(offset) { offset }
    val stableOffsetPx =
        remember(stableOffset) { with(density) { stableOffset.toPx().fastRoundToInt() } }
    val keepAliveZonePx = with(density) { keepAliveZone.toPx() }
    val stableBlendMode = remember(blendMode) { blendMode }

    val textMeasurer = rememberTextMeasurer()
    val layoutCache = remember { mutableStateMapOf<Int, List<SyllableLayout>>() }

    LaunchedEffect(
        lyrics,
        stableNormalTextStyle,
        stableAccompanimentTextStyle,
        stablePhoneticTextStyle
    ) {
        layoutCache.clear()
        withContext(Dispatchers.Default) {
            val normalStyle = stableNormalTextStyle.copy(textDirection = TextDirection.Content)
            val accompanimentStyle =
                stableAccompanimentTextStyle.copy(textDirection = TextDirection.Content)
            val phoneticStyle = stablePhoneticTextStyle.copy(textDirection = TextDirection.Content)

            val normalSpaceWidth = textMeasurer.measure(" ", normalStyle).size.width.toFloat()
            val accompanimentSpaceWidth =
                textMeasurer.measure(" ", accompanimentStyle).size.width.toFloat()

            lyrics.lines.forEachIndexed { index, line ->
                if (!isActive) return@forEachIndexed
                if (line is KaraokeLine) {
                    val style =
                        if (line is KaraokeLine.AccompanimentKaraokeLine) accompanimentStyle else normalStyle
                    val spaceWidth =
                        if (line is KaraokeLine.AccompanimentKaraokeLine) accompanimentSpaceWidth else normalSpaceWidth

                    val processedSyllables = if (line.alignment == KaraokeAlignment.End) {
                        line.syllables.dropLastWhile { it.content.isBlank() }
                    } else {
                        line.syllables
                    }

                    val layout = measureSyllablesAndDetermineAnimation(
                        syllables = processedSyllables,
                        textMeasurer = textMeasurer,
                        style = style,
                        phoneticStyle = phoneticStyle,
                        isAccompanimentLine = line is KaraokeLine.AccompanimentKaraokeLine,
                        spaceWidth = spaceWidth
                    )

                    withContext(Dispatchers.Main) {
                        layoutCache[index] = layout
                    }
                }
            }
        }
    }

    val currentPositionState = rememberUpdatedState(currentPosition)
    val timeProvider: () -> Int = { currentPositionState.value() }
    val renderCurrentPositionState = rememberUpdatedState(renderCurrentPosition)
    val renderTimeProvider: () -> Int = {
        renderCurrentPositionState.value?.invoke() ?: currentPositionState.value()
    }

    val accompanimentToMainMap = remember(lyrics.lines) {
        val map = mutableMapOf<Int, Int>()
        val mainLinesIndices = lyrics.lines.indices.filter { index ->
            val line = lyrics.lines[index]
            line !is KaraokeLine || line !is KaraokeLine.AccompanimentKaraokeLine
        }
        if (mainLinesIndices.isNotEmpty()) {
            lyrics.lines.forEachIndexed { index, line ->
                if (line is KaraokeLine && line is KaraokeLine.AccompanimentKaraokeLine) {
                    // Find the main line that is closest in time (either the one just before or just after)
                    val beforeIdx = mainLinesIndices.findLast { it <= index }
                    val afterIdx = mainLinesIndices.find { it >= index }

                    val anchorIndex = when {
                        beforeIdx != null && afterIdx != null -> {
                            val distBefore =
                                (line.start - lyrics.lines[beforeIdx].start).absoluteValue
                            val distAfter =
                                (lyrics.lines[afterIdx].start - line.start).absoluteValue
                            if (distBefore <= distAfter) beforeIdx else afterIdx
                        }

                        beforeIdx != null -> beforeIdx
                        afterIdx != null -> afterIdx
                        else -> mainLinesIndices.first()
                    }
                    map[index] = anchorIndex
                }
            }
        }
        map
    }
    val effectiveEndTimes = remember(lyrics.lines) {
        IntArray(lyrics.lines.size) { index ->
            val line = lyrics.lines[index]
            var maxEnd = line.end

            if (line is KaraokeLine.MainKaraokeLine) {
                line.accompanimentLines?.forEach { acc ->
                    if (acc.end > maxEnd) maxEnd = acc.end
                }
            }
            maxEnd
        }
    }

    val firstLine = lyrics.lines.firstOrNull()

    val haveDotsIntro by remember(firstLine) {
        derivedStateOf {
            if (firstLine == null) false
            else (firstLine.start > 5000)
        }
    }

    val currentTimeMs = currentPositionState.value()
    val lyricsFocusState = remember(
        lyrics,
        effectiveEndTimes,
        accompanimentToMainMap,
        haveDotsIntro,
        currentTimeMs
    ) {
        val activeIndex = lyrics.lines.indices.find { idx ->
            currentTimeMs >= lyrics.lines[idx].start && currentTimeMs < effectiveEndTimes[idx]
        }

        val first = if (activeIndex != null) {
            activeIndex
        } else {
            val nextIdx = lyrics.lines.indexOfFirst { it.start > currentTimeMs }
            if (nextIdx != -1) nextIdx else lyrics.lines.lastIndex
        }

        val base = lyrics.lines.indices.filter { index ->
            currentTimeMs >= lyrics.lines[index].start && currentTimeMs < effectiveEndTimes[index]
        }
        val result = base.toMutableSet()
        base.forEach { index ->
            val line = lyrics.lines.getOrNull(index)
            if (line is KaraokeLine && line is KaraokeLine.AccompanimentKaraokeLine) {
                accompanimentToMainMap[index]?.let { result.add(it) }
            }
        }

        val activeInterludeIndex = lyrics.lines.indices.find { index ->
            val line = lyrics.lines[index]
            val previousLine = lyrics.lines.getOrNull(index - 1)
            previousLine != null &&
                (line.start - previousLine.end > 5000) &&
                currentTimeMs in previousLine.end..line.start
        }
        val activeIntro = haveDotsIntro && currentTimeMs in 0 until (firstLine?.start ?: 0)

        FocusState(first, result.toList().sorted(), activeInterludeIndex, activeIntro)
    }

    val scrollInCode = remember { mutableStateOf(false) }
    val lastAutoScrollIndex = remember(lyrics) { mutableStateOf<Int?>(null) }
    val suppressPlacementAnimation = remember(lyrics) { mutableStateOf(true) }
    val manualViewportRecenterPending = remember(lyrics) { mutableStateOf(false) }
    val manualViewportRecenterTriggerIndex = remember(lyrics) { mutableStateOf<Int?>(null) }
    val wasManualScrolling = remember(lyrics) { mutableStateOf(false) }

    val isManualScrolling by remember {
        derivedStateOf {
            listState.isScrollInProgress && !scrollInCode.value
        }
    }

    LaunchedEffect(isManualScrolling, lyricsFocusState.firstIndex) {
        if (isManualScrolling) {
            manualViewportRecenterPending.value = true
        }
        if (wasManualScrolling.value && !isManualScrolling && manualViewportRecenterPending.value) {
            manualViewportRecenterTriggerIndex.value = lyricsFocusState.firstIndex
        }
        wasManualScrolling.value = isManualScrolling
    }

    LaunchedEffect(
        lyricsFocusState.firstIndex,
        stableOffsetPx,
        keepAliveZonePx,
        layoutCache.size,
        isManualScrolling
    ) {
        val firstIndex = lyricsFocusState.firstIndex
        if (!scrollInCode.value && !isManualScrolling && firstIndex in lyrics.lines.indices) {
            if (shouldWaitForManualViewportRecenter(
                    manualViewportRecenterPending = manualViewportRecenterPending.value,
                    manualViewportRecenterTriggerIndex = manualViewportRecenterTriggerIndex.value,
                    animateViewportScroll = animateViewportScroll,
                    currentFocusIndex = firstIndex
                )
            ) {
                return@LaunchedEffect
            }
            val items = listState.layoutInfo.visibleItemsInfo
            val targetItem = items.firstOrNull { it.index == firstIndex }
            val previousAutoScrollIndex = lastAutoScrollIndex.value
            val scrollMode = resolveFocusedLineScrollMode(
                previousAutoScrollIndex = previousAutoScrollIndex,
                targetIndex = firstIndex
            )
            val placementSuppressionMs = resolveFocusedLinePlacementSuppressionMs(
                previousAutoScrollIndex = previousAutoScrollIndex,
                targetIndex = firstIndex
            )
            val shouldAnimateVisibleScroll = animateViewportScroll ||
                shouldAnimateVisibleFocusedLineScroll(
                previousAutoScrollIndex = previousAutoScrollIndex,
                targetIndex = firstIndex
            )
            val useManualViewportRecenter = shouldStartManualViewportRecenter(
                manualViewportRecenterPending = manualViewportRecenterPending.value,
                manualViewportRecenterTriggerIndex = manualViewportRecenterTriggerIndex.value,
                animateViewportScroll = animateViewportScroll,
                currentFocusIndex = firstIndex
            )
            val shouldSuppressPlacement = shouldSuppressFocusedLinePlacementAnimation(
                useManualViewportRecenter = useManualViewportRecenter,
                animateViewportScroll = animateViewportScroll,
                placementSuppressionMs = placementSuppressionMs,
                targetItemVisible = targetItem != null,
                scrollMode = scrollMode
            )
            val placementSuppressionTailMs = when {
                useManualViewportRecenter -> ManualViewportRecenterPlacementSuppressionMs
                !animateViewportScroll &&
                    targetItem == null &&
                    scrollMode == FocusedLineScrollMode.Animate -> {
                    ManualViewportRecenterPlacementSuppressionMs
                }

                !animateViewportScroll -> placementSuppressionMs
                else -> 0L
            }
            val targetScrollOffset = (-stableOffsetPx - keepAliveZonePx).toInt()
            val scrollOffset =
                targetItem?.let {
                    resolveFocusedLineViewportDelta(
                        itemOffset = it.offset,
                        viewportStartOffset = listState.layoutInfo.viewportStartOffset,
                        stableOffsetPx = stableOffsetPx,
                        keepAliveZonePx = keepAliveZonePx
                    )
                }
            try {
                scrollInCode.value = true
                suppressPlacementAnimation.value = shouldSuppressPlacement
                if (useManualViewportRecenter) {
                    listState.animateFocusedLineManualRecenter(
                        targetIndex = firstIndex,
                        targetScrollOffset = targetScrollOffset,
                        stableOffsetPx = stableOffsetPx,
                        keepAliveZonePx = keepAliveZonePx
                    )
                } else if (scrollOffset != null) {
                    if (scrollOffset != 0f) {
                        if (shouldAnimateVisibleScroll) {
                            // seek/大跳转时，即便目标行还在可见区，也要保留用户手动滚动般的平滑过渡
                            listState.animateScrollBy(scrollOffset)
                        } else {
                            // 逐句播放保留原本的直接滚动，让 placement spring 负责 Apple Music 风格弹性
                            listState.scrollBy(scrollOffset)
                        }
                    }
                } else {
                    when {
                        animateViewportScroll -> {
                            listState.animateScrollToItem(firstIndex, targetScrollOffset)
                        }
                        scrollMode == FocusedLineScrollMode.Snap -> {
                            listState.scrollToItem(firstIndex, targetScrollOffset)
                        }
                        else -> {
                            listState.animateScrollToItem(firstIndex, targetScrollOffset)
                        }
                    }
                }
                lastAutoScrollIndex.value = firstIndex
                if (!animateViewportScroll &&
                    shouldRealignFocusedLineAfterLayout(previousAutoScrollIndex, firstIndex)
                ) {
                    listState.realignFocusedLineAfterLayout(
                        targetIndex = firstIndex,
                        stableOffsetPx = stableOffsetPx,
                        keepAliveZonePx = keepAliveZonePx
                    )
                }
                if (placementSuppressionTailMs > 0L) {
                    delay(placementSuppressionTailMs)
                }
                if (useManualViewportRecenter) {
                    manualViewportRecenterPending.value = false
                    manualViewportRecenterTriggerIndex.value = null
                }
            } catch (cancelException: CancellationException) {
                throw cancelException
            } catch (_: Exception) {
            } finally {
                scrollInCode.value = false
                suppressPlacementAnimation.value = false
            }
        }
    }
    LookaheadScope {
        Box(modifier = modifier.clipToBounds()) {
            LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithCache {
                            onDrawWithContent {
                                drawContent()
                                val topFade = (topFadeLength.toPx() / size.height).coerceIn(0f, 0.48f)
                                val bottomFade = (bottomFadeLength.toPx() / size.height).coerceIn(0f, 0.48f)
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        topFade to Color.Black,
                                        (1f - bottomFade).coerceIn(0f, 1f) to Color.Black,
                                        1f to Color.Transparent
                                    ),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                        }
                        .layout { measurable, constraints ->
                            val extraHeightPx = (keepAliveZone * 2).roundToPx()

                            val placeable = measurable.measure(
                                constraints.copy(
                                    maxHeight = constraints.maxHeight + extraHeightPx
                                )
                            )

                            layout(constraints.maxWidth, constraints.maxHeight) {
                                placeable.place(0, -(keepAliveZone.roundToPx()))
                            }
                        },
                    contentPadding = PaddingValues(
                        top = stableOffset + keepAliveZone,
                        bottom = stableOffset + keepAliveZone + bottomContentInset
                    )
                ) {
                    itemsIndexed(
                        items = lyrics.lines,
                        key = { index, line -> "${line.start}-${line.end}-$index" }
                    ) { index, line ->
                        val isCurrentFocusLine = index in lyricsFocusState.allIndices
                        val isLineRtl =
                            when (line) {
                                is KaraokeLine -> {
                                    remember(line.syllables) { line.syllables.any { it.content.isRtl() } }
                                }

                                else -> false
                            }
                        val isLineRightAligned = when (line) {
                            is KaraokeLine -> {
                                remember { line.alignment == KaraokeAlignment.End }
                            }

                            else -> false
                        }
                        val isVisualRightAligned = remember(isLineRightAligned, isLineRtl) {
                            if (isLineRightAligned) !isLineRtl
                            else isLineRtl
                        }

                        val distanceWeightState = remember(useBlurEffect, lyricsFocusState) {
                            derivedStateOf {
                                val start = lyricsFocusState.allIndices.firstOrNull() ?: lyricsFocusState.firstIndex
                                val end = lyricsFocusState.allIndices.lastOrNull() ?: lyricsFocusState.firstIndex
                                maxOf(0, start - index, index - end)
                            }
                        }

                        val dynamicStiffness by remember(distanceWeightState.value) {
                            derivedStateOf {
                                (120f - (distanceWeightState.value * 20f)).coerceAtLeast(20f)
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .springPlacement(
                                    this@LookaheadScope,
                                    "${line.start}-${line.end}-$index",
                                    shouldSuppressLinePlacementAnimation(
                                        isManualScrolling = isManualScrolling,
                                        suppressPlacementAnimation = suppressPlacementAnimation.value
                                    ),
                                    stiffness = dynamicStiffness
                                ),
                            horizontalAlignment = if (isVisualRightAligned) Alignment.End else Alignment.Start
                        ) {
                            val animDuration = 600

                            val previousLine = lyrics.lines.getOrNull(index - 1)

                            val showDotsInterlude = lyricsFocusState.activeInterludeIndex == index
                            val showDotsIntro = lyricsFocusState.activeIntro && index == 0
                            val lineRenderTimeProvider =
                                if (isCurrentFocusLine || showDotsInterlude || showDotsIntro) {
                                    renderTimeProvider
                                } else {
                                    timeProvider
                                }

                            AnimatedVisibility(showDotsInterlude || showDotsIntro) {
                                KaraokeBreathingDots(
                                    alignment = when (val line = previousLine ?: firstLine) {
                                        is KaraokeLine -> line.alignment
                                        is SyncedLine -> if (line.content.isRtl()) KaraokeAlignment.End else KaraokeAlignment.Start
                                        else -> KaraokeAlignment.Start
                                    },
                                    startTimeMs = previousLine?.end ?: 0,
                                    endTimeMs = if (showDotsIntro) firstLine!!.start else line.start,
                                    currentTimeProvider = lineRenderTimeProvider,
                                    defaults = breathingDotsDefaults,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }


                            val blurRadiusState = animateFloatAsState(
                                targetValue = (
                                        if (!useBlurEffect) 0f
                                        else if (distanceWeightState.value > 0 && (!listState.isScrollInProgress || scrollInCode.value)) {
                                            distanceWeightState.value * blurDelta
                                        } else 0f),
                                animationSpec = tween(300),
                            )

                            when (line) {
                                is KaraokeLine -> {
                                    if (line is KaraokeLine.MainKaraokeLine) {
                                        LyricsLineItem(
                                            isFocused = isCurrentFocusLine,
                                            isRightAligned = isVisualRightAligned,
                                            onLineClicked = { onLineClicked(line) },
                                            onLinePressed = { onLinePressed(line) },
                                            blurRadius = { blurRadiusState.value },
                                            focusedScale = focusedLineScale,
                                            unfocusedScale = unfocusedLineScale,
                                            activeAlpha = activeLineAlpha,
                                            inactiveAlpha = inactiveLineAlpha,
                                            blendMode = stableBlendMode,
                                        ) {
                                            KaraokeLineText(
                                                line = line,
                                                currentTimeProvider = timeProvider,
                                                renderTimeProvider = lineRenderTimeProvider,
                                                normalLineTextStyle = stableNormalTextStyle,
                                                accompanimentLineTextStyle = stableAccompanimentTextStyle,
                                                phoneticTextStyle = stablePhoneticTextStyle,
                                                activeColor = textColor,
                                                blendMode = stableBlendMode,
                                                showDebugRectangles = showDebugRectangles,
                                                showTranslation = showTranslation,
                                                showPhonetic = showPhonetic,
                                                precalculatedLayouts = layoutCache[index]
                                            )
                                        }
                                    }
                                }

                                is SyncedLine -> {
                                    val isLineRtl = remember(line.content) { line.content.isRtl() }
                                    LyricsLineItem(
                                        isFocused = isCurrentFocusLine,
                                        isRightAligned = isLineRtl,
                                        onLineClicked = { onLineClicked(line) },
                                        onLinePressed = { onLinePressed(line) },
                                        blurRadius = { blurRadiusState.value },
                                        focusedScale = focusedLineScale,
                                        unfocusedScale = unfocusedLineScale,
                                        activeAlpha = activeLineAlpha,
                                        inactiveAlpha = inactiveLineAlpha,
                                        blendMode = stableBlendMode,
                                    ) {
                                        SyncedLineText(
                                            line = line,
                                            isLineRtl = isLineRtl,
                                            textStyle = stableNormalTextStyle.copy(lineHeight = 1.2.em),
                                            textColor = textColor,
                                            showTranslation = showTranslation,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item("BottomSpacing") {
                        Spacer(
                            modifier = Modifier.fillMaxWidth().height(2000.dp)
                        )
                    }
                }
            }
    }
}
