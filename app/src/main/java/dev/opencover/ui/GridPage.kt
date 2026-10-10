package dev.opencover.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.opencover.grid.GRID
import dev.opencover.layout.AppItem
import dev.opencover.layout.Layout
import dev.opencover.layout.WidgetItem
import dev.opencover.layout.moveItem
import dev.opencover.layout.resizeItem
import kotlin.math.roundToInt

@Composable
fun GridPage(
    layout: Layout,
    pageIndex: Int,
    editing: Boolean,
    labels: Map<String, String>,
    a: LauncherActions,
    onLongPress: () -> Unit,
) {
    val page = layout.pages.getOrNull(pageIndex) ?: return
    BoxWithConstraints(
        Modifier.fillMaxSize().combinedClickable(onClick = {}, onLongClick = onLongPress)
    ) {
        val cw = maxWidth / GRID
        val ch = maxHeight / GRID
        page.items.forEach { item ->
            key(item.id) {
                val c = item.cell
                var drag by remember { mutableStateOf(Offset.Zero) }
                val cwPx = with(LocalDensity.current) { cw.toPx() }
                val chPx = with(LocalDensity.current) { ch.toPx() }
                Box(
                    Modifier
                        .offset(cw * c.x, ch * c.y)
                        .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
                        .size(cw * c.w, ch * c.h)
                ) {
                    when (item) {
                        is AppItem -> AppIcon(
                            labels[item.key] ?: item.pkg,
                            a.repo.icon(item.pkg, item.activity, item.user),
                            Modifier.fillMaxSize().combinedClickable(
                                enabled = !editing,
                                onClick = { a.launch(item.pkg, item.activity, item.user) },
                                onLongClick = onLongPress,
                            ),
                        )
                        is WidgetItem -> WidgetCell(item, a, cw * c.w, ch * c.h)
                    }
                    if (editing) {
                        // Transparent layer on top so drags reach us, not the app icon or widget.
                        Box(
                            Modifier.fillMaxSize()
                                .border(1.dp, Color.White.copy(alpha = 0.5f))
                                .pointerInput(item.id, c) {
                                    detectDragGestures(
                                        onDragEnd = {
                                            val nx = c.x + (drag.x / cwPx).roundToInt()
                                            val ny = c.y + (drag.y / chPx).roundToInt()
                                            layout.moveItem(pageIndex, item.id, nx, ny)?.let(a.change)
                                            drag = Offset.Zero // rejected drops spring back
                                        },
                                        onDragCancel = { drag = Offset.Zero },
                                    ) { change, delta ->
                                        change.consume()
                                        drag += delta
                                    }
                                }
                        )
                        Text(
                            "✕", color = Color.White, fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.TopStart)
                                .background(Color(0xCCB00020), CircleShape)
                                .clickable { a.remove(item) }
                                .padding(horizontal = 6.dp),
                        )
                        if (item is WidgetItem) {
                            var grow by remember { mutableStateOf(Offset.Zero) }
                            Box(
                                Modifier.align(Alignment.BottomEnd).size(28.dp)
                                    .offset { IntOffset(grow.x.roundToInt(), grow.y.roundToInt()) }
                                    .background(Color(0xCC2060C0), CircleShape)
                                    .pointerInput(item.id, c) {
                                        detectDragGestures(
                                            onDragEnd = {
                                                val nw = c.w + (grow.x / cwPx).roundToInt()
                                                val nh = c.h + (grow.y / chPx).roundToInt()
                                                layout.resizeItem(pageIndex, item.id, nw, nh)?.let(a.change)
                                                grow = Offset.Zero
                                            },
                                            onDragCancel = { grow = Offset.Zero },
                                        ) { change, delta ->
                                            change.consume()
                                            grow += delta
                                        }
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetCell(item: WidgetItem, a: LauncherActions, width: Dp, height: Dp) {
    val view = remember(item.widgetId) { a.widgets.view(item.widgetId) }
    if (view == null) {
        Box(Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
            Text("Widget unavailable", color = Color.White, fontSize = 11.sp)
        }
        return
    }
    AndroidView(
        factory = { view },
        modifier = Modifier.fillMaxSize(),
        update = { a.widgets.resize(it, width.value, height.value) },
    )
}
