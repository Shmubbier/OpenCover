package dev.opencover.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.apps.AppEntry
import dev.opencover.apps.Sort
import dev.opencover.apps.filterApps
import dev.opencover.apps.letterIndex
import dev.opencover.grid.GRID
import kotlinx.coroutines.launch

@Composable
fun AppDrawer(
    apps: List<AppEntry>,
    icon: (AppEntry) -> ImageBitmap?,
    onLaunch: (AppEntry) -> Unit,
    onAddToHome: (AppEntry) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(Sort.AZ) }
    val shown = remember(apps, query, sort) { filterApps(apps, query, sort) }
    val index = remember(shown, sort) { if (sort == Sort.AZ) letterIndex(shown) else emptyMap() }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().background(Color(0xF0101010))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search") }, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { sort = if (sort == Sort.AZ) Sort.RECENT else Sort.AZ }) {
                Text(if (sort == Sort.AZ) "A–Z" else "Recent")
            }
        }
        Row(Modifier.weight(1f)) {
            LazyVerticalGrid(GridCells.Fixed(GRID), state = grid, modifier = Modifier.weight(1f)) {
                items(shown, key = { it.key }) { e ->
                    AppIcon(e.label, icon(e), Modifier.combinedClickable(
                        onClick = { onLaunch(e) },
                        onLongClick = { onAddToHome(e) },
                    ))
                }
            }
            if (index.isNotEmpty()) {
                Column(Modifier.fillMaxHeight().width(20.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                    index.forEach { (letter, i) ->
                        Text("$letter", color = Color.White, fontSize = 10.sp,
                            modifier = Modifier.clickable { scope.launch { grid.scrollToItem(i) } })
                    }
                }
            }
        }
    }
}
