package dev.opencover.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.quick.COLS
import dev.opencover.quick.Need
import dev.opencover.quick.PAGE_SIZE
import dev.opencover.quick.QuickPrefs
import dev.opencover.quick.QuickState
import dev.opencover.quick.ROWS
import dev.opencover.quick.Ringer
import dev.opencover.quick.Tile
import dev.opencover.quick.dropIndex
import dev.opencover.quick.hiddenTiles
import dev.opencover.quick.moved
import dev.opencover.quick.pageCount
import dev.opencover.quick.usable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Swipe-down quick settings. Long-press a tile to edit; swipe up or Back closes (Shell). */
@Composable
fun QuickPanel() {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        QuickState.start(ctx)
        onDispose { QuickState.stop(ctx) }
    }
    val v = QuickState.version // read so every change recomposes
    var tiles by remember { mutableStateOf(QuickPrefs.tiles(ctx)) }
    var editing by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) { if (hint != null) { delay(2500); hint = null } }
    BackHandler(enabled = editing) { editing = false }
    val scope = rememberCoroutineScope()
    fun save(t: List<Tile>) { tiles = t; QuickPrefs.setTiles(ctx, t) }
    val has = remember(v) { QuickState.granted(ctx) }
    val hidden = hiddenTiles(tiles)

    fun tap(t: Tile) {
        // Shizuku commands block; everything else touches main-thread APIs (window, camera).
        if (t.need == Need.SHIZUKU) scope.launch {
            val h = withContext(Dispatchers.IO) { QuickState.toggle(ctx, t) }
            if (h != null) hint = h
        } else QuickState.toggle(ctx, t)?.let { hint = it }
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xF0101010)).windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        // Header: cover brightness and battery, or Done while editing.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (editing) {
                Text("Edit tiles", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { editing = false }) { Text("Done") }
            } else {
                Icon(Icons.Filled.BrightnessMedium, null, tint = Color.White, modifier = Modifier.size(22.dp))
                // Real cover brightness needs Shizuku; without it the slider dims only OpenCover.
                val cover = Need.SHIZUKU in has
                var level by remember { mutableFloatStateOf(0.5f) }
                LaunchedEffect(cover) { if (cover) withContext(Dispatchers.IO) { QuickState.brightness() }?.let { level = it } }
                Slider(
                    value = level, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    onValueChange = {
                        level = it
                        if (!cover) (ctx as? Activity)?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = it.coerceAtLeast(0.02f) } }
                    },
                    onValueChangeFinished = { if (cover) scope.launch(Dispatchers.IO) { QuickState.setBrightness(level) } },
                )
                Text("${remember(v) { QuickState.battery(ctx) }}%", color = Color.White, fontSize = 14.sp)
            }
        }

        val pages = pageCount(tiles.size, editing, hidden.size)
        val pager = rememberPagerState { pages }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            val addPage = editing && hidden.isNotEmpty() && page == pages - 1
            val list = if (addPage) hidden.take(PAGE_SIZE) else tiles.drop(page * PAGE_SIZE).take(PAGE_SIZE)
            var cell by remember { mutableStateOf(IntSize.Zero) }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                for (row in 0 until ROWS) Row(Modifier.fillMaxWidth()) {
                    for (col in 0 until COLS) Box(
                        Modifier.weight(1f).onSizeChanged { cell = it },
                        contentAlignment = Alignment.Center,
                    ) {
                        val t = list.getOrNull(row * COLS + col) ?: return@Box
                        val index = page * PAGE_SIZE + row * COLS + col
                        when {
                            addPage -> TileButton(t, on = false, enabled = true, badge = "+", onTap = { save(tiles + t) })
                            editing -> {
                                var drag by remember(t) { mutableStateOf(Offset.Zero) }
                                Box(
                                    Modifier.graphicsLayer { translationX = drag.x; translationY = drag.y }
                                        .pointerInput(t, tiles) {
                                            detectDragGesturesAfterLongPress(
                                                onDragEnd = {
                                                    val to = dropIndex(index, (drag.x / cell.width).roundToInt(), (drag.y / cell.height).roundToInt(), tiles.size)
                                                    if (to != index) save(tiles.moved(index, to))
                                                    drag = Offset.Zero
                                                },
                                                onDragCancel = { drag = Offset.Zero },
                                            ) { c, amount -> c.consume(); drag += amount }
                                        },
                                ) { TileButton(t, on = false, enabled = true, badge = "-", onTap = { save(tiles - t) }) }
                            }
                            else -> TileButton(
                                t, on = remember(v) { QuickState.isOn(ctx, t) } == true,
                                enabled = usable(t.need, has),
                                label = if (t == Tile.SOUND) remember(v) { QuickState.ringer(ctx) }.label() else t.label,
                                icon = if (t == Tile.SOUND) remember(v) { QuickState.ringer(ctx) }.icon() else t.icon(),
                                onTap = { tap(t) }, onLongPress = { editing = true },
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(hint ?: if (editing) "Tap - to remove, drag to move, + page to add" else "",
                color = Color.LightGray, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileButton(
    t: Tile,
    on: Boolean,
    enabled: Boolean,
    onTap: () -> Unit,
    label: String = t.label,
    icon: ImageVector = t.icon(),
    badge: String? = null,
    onLongPress: (() -> Unit)? = null, // null in edit mode, so the drag-to-move gesture gets the long press
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                Modifier.size(56.dp).background(if (on) Color.White else Color(0xFF2A2A2A), CircleShape)
                    .then(if (onLongPress != null) Modifier.combinedClickable(onClick = onTap, onLongClick = onLongPress) else Modifier.clickable(onClick = onTap)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, label, tint = when { on -> Color.Black; enabled -> Color.White; else -> Color.Gray })
            }
            if (badge != null) Box(
                Modifier.align(Alignment.TopEnd).size(20.dp).background(Color(0xFF7A1F1F), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(badge, color = Color.White, fontSize = 14.sp) }
        }
        Text(label, color = if (enabled) Color.White else Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun Tile.icon(): ImageVector = when (this) {
    Tile.WIFI -> Icons.Filled.Wifi
    Tile.BLUETOOTH -> Icons.Filled.Bluetooth
    Tile.FLASHLIGHT -> Icons.Filled.FlashlightOn
    Tile.ROTATION -> Icons.Filled.ScreenRotation
    Tile.SOUND -> Icons.AutoMirrored.Filled.VolumeUp
    Tile.DND -> Icons.Filled.DoNotDisturbOn
    Tile.BATTERY_SAVER -> Icons.Filled.BatterySaver
    Tile.LOCATION -> Icons.Filled.LocationOn
    Tile.MOBILE_DATA -> Icons.Filled.SignalCellularAlt
    Tile.AIRPLANE -> Icons.Filled.AirplanemodeActive
    Tile.NFC -> Icons.Filled.Nfc
    Tile.DARK_MODE -> Icons.Filled.DarkMode
    Tile.KEEP_AWAKE -> Icons.Filled.Coffee
}

private fun Ringer.label() = when (this) { Ringer.RING -> "Ring"; Ringer.VIBRATE -> "Vibrate"; Ringer.MUTE -> "Mute" }
private fun Ringer.icon() = when (this) { Ringer.RING -> Icons.AutoMirrored.Filled.VolumeUp; Ringer.VIBRATE -> Icons.Filled.Vibration; Ringer.MUTE -> Icons.AutoMirrored.Filled.VolumeOff }
