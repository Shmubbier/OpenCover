package dev.opencover.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.opencover.COVER_DISPLAY
import dev.opencover.apps.AppEntry
import dev.opencover.apps.AppLauncher
import dev.opencover.apps.AppRepository
import dev.opencover.grid.Cell
import dev.opencover.grid.cellSpan
import dev.opencover.layout.AppItem
import dev.opencover.layout.Item
import dev.opencover.layout.Layout
import dev.opencover.layout.LayoutStore
import dev.opencover.layout.Page
import dev.opencover.layout.WidgetItem
import dev.opencover.layout.addItem
import dev.opencover.layout.addPage
import dev.opencover.layout.defaultLayout
import dev.opencover.layout.newId
import dev.opencover.layout.pruneMissing
import dev.opencover.layout.removeItem
import dev.opencover.layout.removePackage
import dev.opencover.watcher.CoverWatcher
import dev.opencover.watcher.KeepAwake
import dev.opencover.watcher.NotificationAccess
import dev.opencover.widgets.WidgetHost

class CoverActivity : ComponentActivity() {
    private lateinit var store: LayoutStore
    private lateinit var repo: AppRepository
    private lateinit var widgets: WidgetHost
    private var layout by mutableStateOf(Layout(listOf(Page(emptyList()))))
    private var apps by mutableStateOf(emptyList<AppEntry>())
    private var prompt by mutableStateOf<PageFullPrompt?>(null)
    private var loaded = false
    private var resumeTick by mutableIntStateOf(0)
    private var showSetup by mutableStateOf(false)

    private val appsCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(pkg: String, user: UserHandle) {
            val (l, freed) = layout.removePackage(pkg, repo.serial(user))
            freed.forEach(widgets::delete)
            update(l)
            refreshApps()
        }
        override fun onPackageAdded(pkg: String, user: UserHandle) = refreshApps()
        override fun onPackageChanged(pkg: String, user: UserHandle) {
            repo.forget(pkg)
            refreshApps()
        }
        override fun onPackagesAvailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) = refreshApps()
        override fun onPackagesUnavailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) = refreshApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The main screen is broken: if we were opened anywhere else, move to the cover.
        if (display?.displayId != COVER_DISPLAY) {
            AppLauncher.start(this, Intent(this, CoverActivity::class.java))
            finish()
            return
        }
        store = LayoutStore(filesDir)
        repo = AppRepository(this)
        widgets = WidgetHost(this) { id, info, page ->
            // Provider sizes are px at the main display's density; convert to dp, then to cells.
            val sys = Resources.getSystem().displayMetrics.density
            val w = cellSpan(info.targetCellWidth, (info.minWidth / sys).toInt(), CELL_W_DP)
            val h = cellSpan(info.targetCellHeight, (info.minHeight / sys).toInt(), CELL_H_DP)
            val provider = info.provider.flattenToString()
            place(page, w, h, { WidgetItem(newId(), it, id, provider) }, onCancel = { widgets.delete(id) })
        }
        startForegroundService(Intent(this, CoverWatcher::class.java))
        repo.register(appsCallback)

        val actions = LauncherActions(
            repo = repo,
            widgets = widgets,
            launch = { pkg, activity, user -> AppLauncher.launch(this, pkg, activity, user) },
            change = ::update,
            addApp = { e, page -> place(page, 1, 1, { AppItem(newId(), it, e.pkg, e.activity, e.user) }) },
            remove = ::remove,
            addWidget = { info, page -> widgets.begin(info, page) },
            samsungWidgets = {
                CoverWatcher.paused = true // cleared when the screen turns off
                moveTaskToBack(true)
            },
            setKeepAwake = { KeepAwake.set(this, it) },
            openSetup = { showSetup = true },
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                resumeTick // re-read permissions every time we come back from Settings
                val required = Permissions.required(this)
                if (required && !showSetup) LauncherScreen(layout, apps, actions, prompt)
                else Checklist(
                    steps = setupSteps(),
                    onAppInfo = { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true) },
                    onDone = if (required) ({ showSetup = false }) else null,
                )
            }
        }
    }

    private fun setupSteps() = listOf(
        SetupStep("Accessibility (required)", "Brings OpenCover back when you swipe Home, and opens it on unlock",
            Permissions.accessibility(this)) { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
        SetupStep("Display over other apps (required)", "Keeps the cover screen awake when you ask",
            Permissions.overlay(this)) { openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPackage = true) },
        SetupStep("Ignore battery optimization (recommended)", "Stops Android from putting OpenCover to sleep",
            Permissions.battery(this)) { openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPackage = true) },
        SetupStep("Usage access (optional)", "Sorts the app drawer by recently used",
            Permissions.usage(this)) { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS, withPackage = true) },
        SetupStep("Notification access (optional)", "For cover-screen notifications, coming in a later update",
            Permissions.notificationAccess(this)) {
            AppLauncher.start(this, Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(this, NotificationAccess::class.java).flattenToString(),
            ))
        },
        SetupStep("Notifications (optional)", "Shows that OpenCover is running",
            Permissions.notifications(this)) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) },
    )

    private fun openSettings(action: String, withPackage: Boolean = false) {
        val i = Intent(action)
        if (withPackage) i.data = Uri.parse("package:$packageName")
        AppLauncher.start(this, i)
    }

    override fun onStart() {
        super.onStart()
        widgets.startListening()
    }

    override fun onStop() {
        widgets.stopListening()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        refreshApps()
        if (!loaded) loadLayout()
    }

    override fun onDestroy() {
        if (::repo.isInitialized) repo.unregister(appsCallback)
        super.onDestroy()
    }

    @Deprecated("Widget flows need startActivityForResult with display options")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!widgets.onResult(requestCode, resultCode)) super.onActivityResult(requestCode, resultCode, data)
    }

    private fun refreshApps() {
        apps = repo.load()
    }

    private fun loadLayout() {
        val (l, freed) = store.load { defaultLayout(apps) }
            .pruneMissing(apps.map { it.key }.toSet(), widgets::exists)
        freed.forEach(widgets::delete)
        update(l)
        loaded = true
    }

    private fun update(l: Layout) {
        layout = l
        store.save(l)
    }

    private fun remove(item: Item) {
        if (item is WidgetItem) widgets.delete(item.widgetId)
        update(layout.removeItem(item.id))
    }

    /** Adds a w×h item on [page]; if it doesn't fit, asks whether to put it on a new page. */
    private fun place(page: Int, w: Int, h: Int, make: (Cell) -> Item, onCancel: () -> Unit = {}) {
        layout.addItem(page, w, h, make)?.let { update(it); return }
        prompt = PageFullPrompt(
            confirm = {
                val withPage = layout.addPage()
                // A fresh empty page always has room for anything up to GRID×GRID.
                withPage.addItem(withPage.pages.lastIndex, w, h, make)?.let(::update)
                prompt = null
            },
            cancel = {
                onCancel()
                prompt = null
            },
        )
    }

    companion object {
        // 748×720 px at 340 dpi ≈ 352×339 dp, split 4×4.
        private const val CELL_W_DP = 88
        private const val CELL_H_DP = 84
    }
}
