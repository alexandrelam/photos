package io.github.alexandrelam.photos.photos

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.alexandrelam.photos.shared.ui.Darkroom
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val ThumbHeight = 44.dp
private val YearLabelSpacing = 22.dp

/**
 * The handle on the right edge of the timeline. It shows while scrolling; dragging it
 * scrolls fast with a month bubble, and year labels along the edge jump to that year.
 *
 * [labelAt] gives the bubble text for a grid index; [years] maps each year to the grid
 * index where it starts.
 */
@Composable
internal fun FastScroller(
    grid: LazyGridState,
    labelAt: (Int) -> String?,
    years: List<Pair<Int, Int>>,
    onYear: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Darkroom.colors
    val total by remember { derivedStateOf { grid.layoutInfo.totalItemsCount } }
    if (total < 60) return

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(false) }
    val scrolling = grid.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) visible = true
        else {
            delay(1_500)
            visible = false
        }
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "fastScroller")
    if (alpha == 0f) return

    val scrollFraction by remember {
        derivedStateOf {
            val info = grid.layoutInfo
            val shown = info.visibleItemsInfo.size
            val range = (info.totalItemsCount - shown).coerceAtLeast(1)
            (grid.firstVisibleItemIndex.toFloat() / range).coerceIn(0f, 1f)
        }
    }
    val fraction = if (dragging) dragFraction else scrollFraction
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxHeight().alpha(alpha)) {
        val track = with(density) { (maxHeight - ThumbHeight).toPx() }.coerceAtLeast(1f)
        val thumbY = (fraction * track).roundToInt()

        val currentTotal by rememberUpdatedState(total)
        val currentTrack by rememberUpdatedState(track)
        fun indexAt(f: Float) = (f * (currentTotal - 1)).roundToInt().coerceIn(0, currentTotal - 1)

        // Year labels, skipping any that would overlap the one above.
        val spacing = with(density) { YearLabelSpacing.toPx() }
        var lastY = Float.NEGATIVE_INFINITY
        years.forEach { (year, index) ->
            val y = index.toFloat() / (total - 1).coerceAtLeast(1) * track
            if (y - lastY < spacing) return@forEach
            lastY = y
            Text(
                year.toString(),
                style = Darkroom.type.caption.copy(fontWeight = Darkroom.type.body.fontWeight),
                color = colors.text,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, y.roundToInt()) }
                    .padding(end = 44.dp, top = 12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.surfaceRaised.copy(alpha = 0.92f))
                    .clickable { onYear(year) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        Row(
            Modifier.align(Alignment.TopEnd).offset { IntOffset(0, thumbY) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dragging) {
                labelAt(indexAt(fraction))?.let { label ->
                    Text(
                        label,
                        style = Darkroom.type.body,
                        color = colors.background,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.text)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            Box(
                Modifier
                    .size(width = 36.dp, height = ThumbHeight)
                    .clip(RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp))
                    .background(colors.accent)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragFraction = scrollFraction
                                dragging = true
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, dy ->
                            change.consume()
                            dragFraction = (dragFraction + dy / currentTrack).coerceIn(0f, 1f)
                            grid.requestScrollToItem(indexAt(dragFraction))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.UnfoldMore, contentDescription = "Fast scroll", tint = colors.onAccent, modifier = Modifier.size(20.dp))
            }
        }
    }
}
