package dev.opencover.ui

import android.appwidget.AppWidgetProviderInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import dev.opencover.apps.AppEntry
import dev.opencover.apps.AppRepository
import dev.opencover.layout.Item
import dev.opencover.layout.Layout
import dev.opencover.layout.addPage
import dev.opencover.layout.removePage
import dev.opencover.watcher.KeepAwake
import dev.opencover.widgets.WidgetHost
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class LauncherActions(
    val repo: AppRepository,
    val widgets: WidgetHost,
    val launch: (pkg: String, activity: String, user: Long) -> Unit,
    val change: (Layout) -> Unit,
    val addApp: (AppEntry, page: Int) -> Unit,
    val remove: (Item) -> Unit,
    val addWidget: (AppWidgetProviderInfo, page: Int) -> Unit,
    val samsungWidgets: () -> Unit,
    val setKeepAwake: (Boolean) -> Unit,
    val openSetup: () -> Unit,
    val setQuick: (slot: Int, AppEntry?) -> Unit,
    val pickWallpaper: () -> Unit,
)

@Composable
fun EditBar(
    onAddPage: () -> Unit,
    onRemovePage: (() -> Unit)?,
    onDone: () -> Unit,
    extra: @Composable RowScope.() -> Unit = {},
) {
    // Done first so it can never be pushed off the narrow cover screen; the rest scrolls if needed.
    Row(Modifier.fillMaxWidth().background(Color(0xCC202020)).horizontalScroll(rememberScrollState())) {
        TextButton(onClick = onDone) { Text("Done") }
        extra()
        TextButton(onClick = onAddPage) { Text("+ Page") }
        TextButton(onClick = { onRemovePage?.invoke() }, enabled = onRemovePage != null) { Text("− Page") }
    }
}

/** Shown when something doesn't fit on the current page. */
class PageFullPrompt(val confirm: () -> Unit, val cancel: () -> Unit)

private sealed interface Overlay {
    data object Widgets : Overlay
    data object Settings : Overlay
    data class PickSlot(val slot: Int) : Overlay
}

@Composable
fun Shell(
    layout: Layout,
    apps: List<AppEntry>,
    a: LauncherActions,
    prompt: PageFullPrompt?,
    wallpaper: ImageBitmap?,
    homeRequests: Int,
) {
    var editing by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var slotMenu by remember { mutableStateOf<Int?>(null) }
    val reveal = remember { Animatable(0f) } // 0 = panel hidden, 1 = fully shown
    val scope = rememberCoroutineScope()
    val pageCount by rememberUpdatedState(pagerPageCount(layout.pages.size))
    val pager = rememberPagerState(initialPage = HOME_PAGE) { pageCount }
    val labels = remember(apps) { apps.associate { it.key to it.label } }
    var keepAwake by remember { mutableStateOf(KeepAwake.isOn) }

    fun closePanel() {
        scope.launch { reveal.animateTo(0f); panel = null }
    }

    // Home gesture, unlock or fold: back to a clean Home.
    LaunchedEffect(homeRequests) {
        editing = false
        overlay = null
        slotMenu = null
        panel = null
        reveal.snapTo(0f)
        pager.scrollToPage(HOME_PAGE)
    }
    BackHandler(enabled = editing) { editing = false }
    BackHandler(enabled = panel != null) { closePanel() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val heightPx = constraints.maxHeight.toFloat()
        Column(Modifier.fillMaxSize()) {
            if (editing) {
                val removed = layoutPageFor(pager.currentPage)?.let { layout.removePage(it) }
                EditBar(
                    onAddPage = { a.change(layout.addPage()) },
                    onRemovePage = removed?.let { l ->
                        {
                            a.change(l)
                            scope.launch { pager.scrollToPage(pageAfterRemoving(pager.currentPage)) }
                        }
                    },
                    onDone = { editing = false },
                    extra = {
                        TextButton(onClick = { overlay = Overlay.Widgets }) { Text("+ Widget") }
                        TextButton(onClick = { overlay = Overlay.Settings }) { Text("Settings") }
                    },
                )
            }
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).pointerInput(editing) {
                    if (editing) return@pointerInput
                    var total = 0f
                    // Pull a panel in from the edge; it follows the finger and snaps at 25%.
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { if (reveal.value > 0.25f) scope.launch { reveal.animateTo(1f) } else closePanel() },
                        onDragCancel = { closePanel() },
                    ) { change, dy ->
                        total += dy
                        val p = panel ?: panelFor(pager.currentPage, up = total < 0)?.also { panel = it }
                            ?: return@detectVerticalDragGestures
                        change.consume()
                        val shown = if (p.edge == Edge.BOTTOM) -total else total
                        scope.launch { reveal.snapTo((shown / heightPx).coerceIn(0f, 1f)) }
                    }
                },
            ) { i ->
                when (i) {
                    NOTIFICATIONS_PAGE -> Placeholder("Notifications")
                    HOME_PAGE -> HomeScreen(layout.home, wallpaper, a) { slot ->
                        if (layout.home.quick[slot] == null) overlay = Overlay.PickSlot(slot) else slotMenu = slot
                    }
                    else -> GridPage(layout, layoutPageFor(i)!!, editing, labels, a, onLongPress = { editing = true })
                }
            }
        }

        panel?.let { p ->
            val sign = if (p.edge == Edge.BOTTOM) 1 else -1
            Box(
                Modifier.fillMaxSize()
                    .offset { IntOffset(0, ((1f - reveal.value) * heightPx * sign).roundToInt()) }
                    .background(Color(0xF0101010))
                    // Drag back toward the edge to close, wherever the content doesn't scroll itself.
                    .pointerInput(p) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = { if (reveal.value < 0.75f) closePanel() else scope.launch { reveal.animateTo(1f) } },
                            onDragCancel = { scope.launch { reveal.animateTo(1f) } },
                        ) { change, dy ->
                            total += dy
                            val hiding = if (p.edge == Edge.BOTTOM) total else -total
                            if (hiding <= 0f) return@detectVerticalDragGestures
                            change.consume()
                            scope.launch { reveal.snapTo((1f - hiding / heightPx).coerceIn(0f, 1f)) }
                        }
                    }
            ) {
                when (p) {
                    Panel.DRAWER -> AppDrawer(
                        apps = apps,
                        icon = { a.repo.icon(it.pkg, it.activity, it.user) },
                        onLaunch = { closePanel(); a.launch(it.pkg, it.activity, it.user) },
                        onAddToHome = { closePanel(); a.addApp(it, layoutPageFor(pager.currentPage) ?: 0) },
                        onClose = { closePanel() },
                        hint = "Long-press an app to add it to this page",
                    )
                    Panel.MEDIA -> Placeholder("Media player")
                    Panel.QUICK_SETTINGS -> Placeholder("Quick settings")
                }
            }
        }

        when (val o = overlay) {
            Overlay.Widgets -> {
                val pm = LocalContext.current.packageManager
                WidgetPicker(
                    providers = remember { a.widgets.providers() },
                    label = { p -> "${p.activityInfo.applicationInfo.loadLabel(pm)} · ${p.loadLabel(pm)}" },
                    onPick = { overlay = null; a.addWidget(it, layoutPageFor(pager.currentPage) ?: 0) },
                    onClose = { overlay = null },
                )
            }
            Overlay.Settings -> SettingsPanel(
                keepAwake = keepAwake,
                onKeepAwake = { keepAwake = it; a.setKeepAwake(it) },
                onSamsungWidgets = { overlay = null; editing = false; a.samsungWidgets() },
                onSetup = { overlay = null; editing = false; a.openSetup() },
                onWallpaper = { overlay = null; a.pickWallpaper() },
                onClose = { overlay = null },
            )
            is Overlay.PickSlot -> AppDrawer(
                apps = apps,
                icon = { a.repo.icon(it.pkg, it.activity, it.user) },
                onLaunch = { overlay = null; a.setQuick(o.slot, it) },
                onAddToHome = { overlay = null; a.setQuick(o.slot, it) },
                onClose = { overlay = null },
                hint = "Tap an app for this shortcut",
            )
            null -> {}
        }

        slotMenu?.let { slot ->
            AlertDialog(
                onDismissRequest = { slotMenu = null },
                text = { Text("Shortcut") },
                confirmButton = { TextButton(onClick = { slotMenu = null; overlay = Overlay.PickSlot(slot) }) { Text("Change") } },
                dismissButton = { TextButton(onClick = { slotMenu = null; a.setQuick(slot, null) }) { Text("Remove") } },
            )
        }
        prompt?.let { p ->
            AlertDialog(
                onDismissRequest = p.cancel,
                text = { Text("This page is full. Add it on a new page?") },
                confirmButton = { TextButton(onClick = p.confirm) { Text("Add page") } },
                dismissButton = { TextButton(onClick = p.cancel) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun Placeholder(title: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color.White, fontSize = 20.sp)
        Text("Coming in a later update", color = Color.Gray, fontSize = 12.sp)
    }
}
