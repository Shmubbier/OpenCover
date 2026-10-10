package dev.opencover.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import dev.opencover.grid.GRID
import dev.opencover.layout.AppItem
import dev.opencover.layout.Layout
import dev.opencover.layout.WidgetItem

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
                Box(Modifier.offset(cw * c.x, ch * c.y).size(cw * c.w, ch * c.h)) {
                    when (item) {
                        is AppItem -> AppIcon(
                            labels[item.key] ?: item.pkg,
                            a.repo.icon(item.pkg, item.activity, item.user),
                            Modifier.fillMaxSize().combinedClickable(
                                onClick = { a.launch(item.pkg, item.activity, item.user) },
                                onLongClick = onLongPress,
                            ),
                        )
                        is WidgetItem -> {} // Task 9
                    }
                }
            }
        }
    }
}
