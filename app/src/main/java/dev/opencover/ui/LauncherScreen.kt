package dev.opencover.ui

import android.appwidget.AppWidgetProviderInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import dev.opencover.apps.AppEntry
import dev.opencover.apps.AppRepository
import dev.opencover.layout.Item
import dev.opencover.layout.Layout
import dev.opencover.layout.addPage
import dev.opencover.layout.removePage
import dev.opencover.watcher.KeepAwake
import dev.opencover.widgets.WidgetHost

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

private enum class Overlay { NONE, DRAWER, WIDGETS, SETTINGS }

@Composable
fun LauncherScreen(layout: Layout, apps: List<AppEntry>, a: LauncherActions, prompt: PageFullPrompt?) {
    var editing by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(Overlay.NONE) }
    val pageCount by rememberUpdatedState(layout.pages.size)
    val pager = rememberPagerState { pageCount }
    val labels = remember(apps) { apps.associate { it.key to it.label } }
    var dragTotal by remember { mutableFloatStateOf(0f) }
    var keepAwake by remember { mutableStateOf(KeepAwake.isOn) }
    BackHandler(enabled = editing) { editing = false }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            if (editing) {
                val removable = layout.removePage(pager.currentPage)
                EditBar(
                    onAddPage = { a.change(layout.addPage()) },
                    onRemovePage = removable?.let { l -> { a.change(l) } },
                    onDone = { editing = false },
                    extra = {
                        TextButton(onClick = { overlay = Overlay.WIDGETS }) { Text("+ Widget") }
                        TextButton(onClick = { overlay = Overlay.SETTINGS }) { Text("Settings") }
                    },
                )
            }
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).pointerInput(editing) {
                    if (!editing) detectVerticalDragGestures(
                        onDragStart = { dragTotal = 0f },
                        onDragEnd = { if (dragTotal < -80f) overlay = Overlay.DRAWER },
                    ) { _, dy -> dragTotal += dy }
                },
            ) { i ->
                GridPage(layout, i, editing, labels, a, onLongPress = { editing = true })
            }
        }
        when (overlay) {
            Overlay.DRAWER -> AppDrawer(
                apps = apps,
                icon = { a.repo.icon(it.pkg, it.activity, it.user) },
                onLaunch = { overlay = Overlay.NONE; a.launch(it.pkg, it.activity, it.user) },
                onAddToHome = { overlay = Overlay.NONE; a.addApp(it, pager.currentPage) },
                onClose = { overlay = Overlay.NONE },
                hint = "Long-press an app to add it to Home",
            )
            Overlay.WIDGETS -> {
                val pm = LocalContext.current.packageManager
                WidgetPicker(
                    providers = remember { a.widgets.providers() },
                    label = { p ->
                        val app = pm.getApplicationLabel(pm.getApplicationInfo(p.provider.packageName, 0))
                        "$app · ${p.loadLabel(pm)}"
                    },
                    onPick = { overlay = Overlay.NONE; a.addWidget(it, pager.currentPage) },
                    onClose = { overlay = Overlay.NONE },
                )
            }
            Overlay.SETTINGS -> SettingsPanel(
                keepAwake = keepAwake,
                onKeepAwake = { keepAwake = it; a.setKeepAwake(it) },
                onSamsungWidgets = { overlay = Overlay.NONE; editing = false; a.samsungWidgets() },
                onSetup = { overlay = Overlay.NONE; editing = false; a.openSetup() },
                onClose = { overlay = Overlay.NONE },
            )
            Overlay.NONE -> {}
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
