package dev.opencover.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.opencover.lock.Spot
import dev.opencover.lock.clamp
import dev.opencover.lock.dropped
import dev.opencover.lock.resized

/**
 * Items placed freely on a screen (the lock screen's modules, Home's clock and apps), each at its [Spot]. With [onMove]
 * set it is an editor: drag to move, pinch to resize, the [selected] one outlined, switched-off ones shown faint.
 * [centered] items sit in the middle across, whatever their x. [onAlpha] is how opaque a switched-on item is.
 */
@Composable
fun <K> BoxWithConstraintsScope.Placed(
    layout: Map<K, Spot>,
    onMove: ((K, Spot) -> Unit)? = null,
    selected: K? = null,
    onSelect: (K) -> Unit = {},
    centered: Set<K> = emptySet(),
    onAlpha: Float = 1f,
    content: @Composable (K) -> Unit,
) {
    val sizes = remember { mutableStateMapOf<K, IntSize>() }
    val wPx = constraints.maxWidth.toFloat()
    val hPx = constraints.maxHeight.toFloat()
    val density = LocalDensity.current
    layout.forEach { (key, spot) ->
        if (!spot.on && onMove == null) return@forEach
        // Clamped as drawn too: an item can grow after it was placed (another clock style, more icons).
        val size = sizes[key] ?: IntSize.Zero
        val w = size.width * spot.scale / wPx
        val h = size.height * spot.scale / hPx
        val shown = clamp(if (key in centered) spot.copy(x = (1f - w) / 2) else spot, w, h)
        val current by rememberUpdatedState(shown)
        var m = Modifier
            .offset(with(density) { (shown.x * wPx).toDp() }, with(density) { (shown.y * hPx).toDp() })
            .onSizeChanged { sizes[key] = it }
            .graphicsLayer {
                scaleX = spot.scale; scaleY = spot.scale
                transformOrigin = TransformOrigin(0f, 0f)
                alpha = if (!spot.on) 0.3f else onAlpha
            }
        if (onMove != null) m = m
            // The selected item is outlined until another one is touched.
            .border(2.dp, if (selected == key) Color(0xFF8AB4F8) else Color.Transparent, RoundedCornerShape(6.dp))
            .pointerInput(key) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    onSelect(key)
                }
            }
            // Drag to move, pinch to resize; each step goes to the draft at once.
            .pointerInput(key) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val s = sizes[key] ?: IntSize.Zero
                    val next = resized(current, zoom)
                    onMove(key, dropped(next, pan.x, pan.y, wPx, hPx, s.width * next.scale / wPx, s.height * next.scale / hPx))
                }
            }
        Box(m) { content(key) }
    }
}

/**
 * An editor's toolbar: a switch per item, then [doneLabel] to save. It covers whatever is under it, so it moves to
 * the other edge on request, letting an item there be dragged out.
 */
@Composable
fun <K> BoxWithConstraintsScope.PlaceBar(
    draft: Map<K, Spot>,
    names: Map<K, String>,
    onToggle: (K) -> Unit,
    doneLabel: String,
    onDone: () -> Unit,
) {
    var atBottom by remember { mutableStateOf(false) }
    Row(
        Modifier.align(if (atBottom) Alignment.BottomCenter else Alignment.TopCenter)
            .fillMaxWidth().background(Color(0xCC000000)).let { if (atBottom) it.navigationBarsPadding() else it }
            .horizontalScroll(rememberScrollState()).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TextButton(onClick = { atBottom = !atBottom }) { Text(if (atBottom) "Bar to top" else "Bar to bottom") }
        draft.forEach { (k, s) ->
            FilterChip(selected = s.on, onClick = { onToggle(k) }, label = { Text(names.getValue(k)) })
        }
        Button(onClick = onDone) { Text(doneLabel) }
    }
}
