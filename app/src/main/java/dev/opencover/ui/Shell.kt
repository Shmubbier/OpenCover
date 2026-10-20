package dev.opencover.ui

import android.appwidget.AppWidgetProviderInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.util.VelocityTracker
import dev.opencover.look.opensOnRelease
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
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
import dev.opencover.notify.NotificationStore
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
    val bankingApps: () -> Set<String>,
    val setBankingApps: (Set<String>) -> Unit,
    val topEdge: () -> Boolean,
    val setTopEdge: (Boolean) -> Unit,
    val usageGranted: () -> Boolean,
    val grantUsage: () -> Unit,
    val popups: () -> Boolean,
    val setPopups: (Boolean) -> Unit,
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
    data object Banking : Overlay
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
    quickSettingsRequests: Int,
) {
    var editing by remember { mutableStateOf(false) }
    var homeEditing by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var slotMenu by remember { mutableStateOf<Int?>(null) }
    val reveal = remember { Animatable(0f) } // 0 = panel hidden, 1 = fully shown
    val scope = rememberCoroutineScope()
    val pageCount by rememberUpdatedState(pagerPageCount(layout.pages.size))
    val pager = rememberPagerState(initialPage = HOME_PAGE) { pageCount }
    val labels = remember(apps) { apps.associate { it.key to it.label } }
    var keepAwake by remember { mutableStateOf(KeepAwake.isOn) }
    var topEdge by remember { mutableStateOf(a.topEdge()) }
    var popups by remember { mutableStateOf(a.popups()) }

    fun closePanel() {
        scope.launch { reveal.animateTo(0f); panel = null }
    }

    // Home gesture, unlock or fold: back to a clean Home.
    LaunchedEffect(homeRequests) {
        editing = false
        homeEditing = false
        overlay = null
        slotMenu = null
        panel = null
        reveal.snapTo(0f)
        pager.scrollToPage(HOME_PAGE)
    }
    // Top-edge priority: Samsung's panel was just closed, open ours in its place.
    LaunchedEffect(quickSettingsRequests) {
        if (quickSettingsRequests == 0) return@LaunchedEffect
        overlay = null
        slotMenu = null
        panel = Panel.QUICK_SETTINGS
        reveal.animateTo(1f)
    }
    // Edit mode belongs to the apps pages; swiping to Home or Notifications ends it.
    LaunchedEffect(pager.settledPage) { if (pager.settledPage < FIRST_APPS_PAGE) editing = false }
    BackHandler(enabled = editing) { editing = false }
    BackHandler(enabled = panel != null) { closePanel() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val heightPx = constraints.maxHeight.toFloat()
        // How far a page or panel covers Home, 0..1: the wallpaper blurs by as much as it is covered.
        val pageAway = minOf(1f, kotlin.math.abs(pager.currentPage + pager.currentPageOffsetFraction - HOME_PAGE))
        val panelOver = if (panel != null && panel != Panel.MEDIA) reveal.value else 0f
        val covered = maxOf(pageAway, panelOver)
        // One wallpaper for every page: it plays on Home and holds its frame elsewhere.
        Background(
            wallpaper, dev.opencover.home.Wallpaper.homeAnim, dev.opencover.look.Playheads.home,
            playing = covered == 0f, // a video can't be blurred: it pauses (on a captured frame) as soon as anything covers Home
            crop = dev.opencover.home.Wallpaper.homeCrop, video = dev.opencover.home.Wallpaper.homeVideo,
            modifier = Modifier.blur((dev.opencover.look.LookPrefs.current.blur * covered).dp, BlurredEdgeTreatment.Rectangle),
        )
        // A panel slides in over the wallpaper, not over the page under it: fade the page out as it comes.
        Column(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - panelOver }) {
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
                userScrollEnabled = !homeEditing, // dragging Home's items must not turn the page
                modifier = Modifier.weight(1f).pointerInput(editing, homeEditing) {
                    if (editing || homeEditing) return@pointerInput
                    var total = 0f
                    val velocity = VelocityTracker()
                    // Pull a panel in: fully shown after PULL of the screen height. It snaps open past 15%; the app
                    // drawer opens on a short pull or a quick flick up.
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f; velocity.resetTracking() },
                        onDragEnd = {
                            val flingUp = velocity.calculateVelocity().y < -800f // px/s
                            if (opensOnRelease(reveal.value, flingUp, drawer = panel == Panel.DRAWER)) scope.launch { reveal.animateTo(1f) }
                            else closePanel()
                        },
                        onDragCancel = { closePanel() },
                    ) { change, dy ->
                        total += dy
                        velocity.addPosition(change.uptimeMillis, change.position)
                        val p = panel ?: panelFor(pager.currentPage, up = total < 0)?.also { panel = it }
                            ?: return@detectVerticalDragGestures
                        change.consume()
                        val shown = if (p.edge == Edge.BOTTOM) -total else total
                        scope.launch { reveal.snapTo((shown / (heightPx * PULL)).coerceIn(0f, 1f)) }
                    }
                },
            ) { i ->
                when (i) {
                    NOTIFICATIONS_PAGE -> PanelBackdrop { NotificationsPage(NotificationStore.notes) }
                    HOME_PAGE -> HomeScreen(layout.home, a, homeEditing, { homeEditing = it }) { slot ->
                        if (layout.home.quick[slot] == null) overlay = Overlay.PickSlot(slot) else slotMenu = slot
                    }
                    else -> PanelBackdrop { GridPage(layout, layoutPageFor(i)!!, editing, labels, a, onLongPress = { editing = true }) }
                }
            }
        }

        panel?.let { p ->
            val sign = if (p.edge == Edge.BOTTOM) 1 else -1
            Box(
                Modifier.fillMaxSize()
                    .offset { IntOffset(0, ((1f - reveal.value) * heightPx * sign).roundToInt()) }
                    // Drag back toward the edge to close, wherever the content doesn't scroll itself.
                    .pointerInput(p) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = { if (reveal.value < 0.85f) closePanel() else scope.launch { reveal.animateTo(1f) } },
                            onDragCancel = { scope.launch { reveal.animateTo(1f) } },
                        ) { change, dy ->
                            total += dy
                            val hiding = if (p.edge == Edge.BOTTOM) total else -total
                            if (hiding <= 0f) return@detectVerticalDragGestures
                            change.consume()
                            scope.launch { reveal.snapTo((1f - hiding / (heightPx * PULL)).coerceIn(0f, 1f)) }
                        }
                    }
            ) {
                if (p == Panel.MEDIA) MediaPanel() // album art is its own backdrop; close by swiping down or Back
                else PanelBackdrop { when (p) {
                    Panel.DRAWER -> AppDrawer(
                        apps = apps,
                        icon = { a.repo.icon(it.pkg, it.activity, it.user) },
                        onLaunch = { closePanel(); a.launch(it.pkg, it.activity, it.user) },
                        onAddToHome = { closePanel(); a.addApp(it, layoutPageFor(pager.currentPage) ?: 0) },
                        onClose = { closePanel() },
                        hint = "Long-press an app to add it to this page",
                    )
                    Panel.QUICK_SETTINGS -> QuickPanel() // close by swiping up or Back
                    Panel.MEDIA -> {}
                } }
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
                popups = popups,
                onPopups = { popups = it; a.setPopups(it) },
                onKeepAwake = { keepAwake = it; a.setKeepAwake(it) },
                onSamsungWidgets = { overlay = null; editing = false; a.samsungWidgets() },
                onSetup = { overlay = null; editing = false; a.openSetup() },
                onWallpaper = { overlay = null; a.pickWallpaper() },
                onBanking = { overlay = Overlay.Banking },
                topEdge = topEdge,
                onTopEdge = { topEdge = it; a.setTopEdge(it) },
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
            Overlay.Banking -> {
                var selected by remember { mutableStateOf(a.bankingApps()) }
                BankingPicker(
                    apps = apps,
                    selected = selected,
                    onChange = { selected = it; a.setBankingApps(it) },
                    onClose = { overlay = Overlay.Settings },
                    needsUsageAccess = selected.isNotEmpty() && !a.usageGranted(),
                    onGrantUsage = a.grantUsage,
                )
            }
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

/** Fraction of the screen height a finger travels to fully open or close a panel. */
private const val PULL = 0.4f
