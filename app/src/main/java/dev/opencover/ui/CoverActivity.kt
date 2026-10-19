package dev.opencover.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.os.UserHandle
import android.provider.MediaStore
import android.provider.Settings
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.opencover.lock.LockLayer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import dev.opencover.home.HomePrefs
import dev.opencover.lock.LockPrefs
import dev.opencover.lock.aodReady
import dev.opencover.phone.RotationFeature
import dev.opencover.home.Weather
import dev.opencover.COVER_DISPLAY
import dev.opencover.apps.AppEntry
import dev.opencover.apps.AppLauncher
import dev.opencover.apps.AppRepository
import dev.opencover.grid.Cell
import dev.opencover.grid.cellSpan
import dev.opencover.home.Wallpaper
import dev.opencover.notify.NotificationStore
import dev.opencover.layout.QuickApp
import dev.opencover.layout.setQuick
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
import dev.opencover.quick.ShizukuShell
import dev.opencover.watcher.BankingMode
import dev.opencover.watcher.CoverWatcher
import dev.opencover.watcher.KeepAwake
import dev.opencover.watcher.NotificationAccess
import dev.opencover.watcher.TopEdge
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
    private var homeRequests by mutableIntStateOf(0)
    /** Keyguard locked: only the lock layer is drawn (the activity shows over the keyguard while OpenCover's lock screen is on). */
    private var locked by mutableStateOf(false)
    // The locked-state listener is system-only; these broadcasts plus onResume cover every change.
    private val lockEvents = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) = refreshLocked()
    }

    fun refreshLocked() { locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked }

    /** Shows over the keyguard only while OpenCover's lock screen is switched on. */
    fun applyLockScreen() = setShowWhenLocked(LockPrefs.lockScreen)

    /** Bumped when Home comes up straight from the lock screen: plays [Entrance]. */
    private var entrances by mutableIntStateOf(0)
    private var quickSettingsRequests by mutableIntStateOf(0)

    /** Called by the accessibility service after it closes Samsung's panel. */
    fun requestQuickSettings() {
        quickSettingsRequests++
    }

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
        if (intent.getBooleanExtra(EXTRA_UNLOCKED, false)) entrances++
        dev.opencover.lock.CoverScale.record(this)
        LockPrefs.load(this)
        applyLockScreen()
        refreshLocked()
        registerReceiver(lockEvents, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, RECEIVER_NOT_EXPORTED)
        // The main screen is broken: if we were opened anywhere else, move to the cover.
        if (display?.displayId != COVER_DISPLAY) {
            AppLauncher.start(this, Intent(this, CoverActivity::class.java))
            finish()
            return
        }
        store = LayoutStore(filesDir)
        repo = AppRepository(this)
        Wallpaper.loadAll(this)
        dev.opencover.look.LookPrefs.load(this)
        widgets = WidgetHost(this) { id, info, page ->
            // Provider sizes are px at the main display's density; convert to dp, then to cells.
            val sys = Resources.getSystem().displayMetrics.density
            val w = cellSpan(info.targetCellWidth, (info.minWidth / sys).toInt(), CELL_W_DP)
            val h = cellSpan(info.targetCellHeight, (info.minHeight / sys).toInt(), CELL_H_DP)
            val provider = info.provider.flattenToString()
            val user = repo.serial(info.profile)
            place(page, w, h, { WidgetItem(newId(), it, id, provider, user) }, onCancel = { widgets.delete(id) })
        }
        startForegroundService(Intent(this, CoverWatcher::class.java))
        repo.register(appsCallback)

        val actions = LauncherActions(
            repo = repo,
            widgets = widgets,
            launch = { pkg, activity, user ->
                // Step aside before a banking app checks for accessibility services.
                if (pkg in BankingMode.apps(this)) BankingMode.pause(this, pkg)
                // Didn't open: we're still in OpenCover, so turn accessibility straight back on.
                AppLauncher.launch(this, pkg, activity, user) { BankingMode.resume(this) }
            },
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
            setQuick = { slot, e -> update(layout.setQuick(slot, e?.let { QuickApp(it.pkg, it.activity, it.user) })) },
            pickWallpaper = ::pickWallpaper,
            bankingApps = { BankingMode.apps(this) },
            setBankingApps = { BankingMode.setApps(this, it) },
            topEdge = { TopEdge.enabled(this) },
            setTopEdge = { TopEdge.setEnabled(this, it) },
            usageGranted = { Permissions.usage(this) },
            grantUsage = { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS, withPackage = true) },
            popups = { NotificationStore.popupsEnabled(this) },
            setPopups = { NotificationStore.setPopups(this, it) },
        )
        HomePrefs.load(this)
        LockPrefs.load(this); Weather.load(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                resumeTick // re-read permissions every time we come back from Settings
                val required = Permissions.required(this)
                // Locked: nothing of Home is composed, only the lock layer; unlocking crossfades into Home.
                Crossfade(locked, animationSpec = tween(260), label = "lock") { isLocked ->
                    when {
                        isLocked && LockPrefs.lockScreen -> LockLayer(this)
                        isLocked -> Box(Modifier.fillMaxSize().background(Color.Black)) // not shown over the keyguard then
                        required && !showSetup -> Entrance(entrances) { Shell(layout, apps, actions, prompt, Wallpaper.homeStill, homeRequests, quickSettingsRequests) }
                        else -> Checklist(
                            steps = setupSteps(),
                            onAppInfo = { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true) },
                            onDone = if (required) ({ showSetup = false }) else null,
                        )
                    }
                }
            }
        }
    }

    private fun setupSteps() = listOfNotNull(
        SetupStep("Accessibility (required)", "Brings OpenCover back when you swipe Home, and opens it on unlock",
            Permissions.accessibility(this)) { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
        SetupStep("Display over other apps (required)", "Keeps the cover screen awake when you ask",
            Permissions.overlay(this)) { openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPackage = true) },
        if (!LockPrefs.aod) null else SetupStep("Samsung AOD set to Always (for OpenCover AOD)",
            "Always On Display: Always, with tap to show off. OpenCover draws over it.", aodReady(this)) {
            val tries = listOf("com.samsung.android.app.aodservice.settings.AODDisplayMode",
                "com.samsung.android.app.aodservice.settings.AODSettingsMain", Settings.ACTION_DISPLAY_SETTINGS)
            tries.firstOrNull { runCatching { AppLauncher.start(this, Intent(it)) }.isSuccess }
        },
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
        SetupStep("Banking apps auto-resume (optional)",
            "Run once on a computer: adb shell pm grant dev.opencover android.permission.WRITE_SECURE_SETTINGS",
            BankingMode.canResume(this)) {},
        SetupStep("Shizuku (optional)",
            if (!ShizukuShell.installed(this)) "Wi-Fi, Bluetooth, data and other tiles. Install Shizuku, see README"
            else if (!ShizukuShell.running()) "Shizuku is installed but not running, see README"
            else "Tap to allow OpenCover in Shizuku",
            ShizukuShell.allowed()) { ShizukuShell.request() },
        SetupStep("Phone calls (optional)", "Lets the cover call screen see ringing calls and answer them",
            listOf(Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE).all {
                checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }) {
            requestPermissions(arrayOf(Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE), 3)
        },
        SetupStep("Microphone (optional)", "Voice replies to notifications",
            Permissions.mic(this)) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2) },
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

    override fun onPause() {
        current = null
        super.onPause()
    }

    // Density and font size changes don't restart this activity (see the manifest): keep the AOD's copy current.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        dev.opencover.lock.CoverScale.record(this)
    }

    override fun onResume() {
        super.onResume()
        refreshLocked()
        if (ShizukuShell.allowed()) RotationFeature.upright(this) // shell call runs on its own thread
        current = this
        // Home resumed: check whether it's on top yet (the curtain hiding Samsung's cover home can go then).
        dev.opencover.watcher.CoverAccessibility.instance?.let { dev.opencover.nav.NavBar.dropCurtainIfHome(it) }
        BankingMode.resume(this) // back in OpenCover, so any banking app was left
        // Full screen: a top-edge swipe only peeks the status bar, so Samsung's quick panel
        // doesn't fight our swipe-down quick settings.
        window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        resumeTick++
        refreshApps()
        if (!loaded) loadLayout()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(lockEvents) }
        if (::repo.isInitialized) repo.unregister(appsCallback)
        super.onDestroy()
    }

    @Deprecated("Widget flows need startActivityForResult with display options")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_WALLPAPER) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                if (!Wallpaper.save(this, uri, pickingSlot)) Toast.makeText(this, "Couldn't use that photo", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (!widgets.onResult(requestCode, resultCode)) super.onActivityResult(requestCode, resultCode, data)
    }

    // Every launch through AppLauncher.start (Home gesture, unlock, fold) lands here: go to Home.
    // A touch on Home or the lock screen during edge lighting keeps the cover awake afterwards.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        dev.opencover.edge.EdgeLight.lastTouch = android.os.SystemClock.uptimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_UNLOCKED, false)) entrances++
        homeRequests++
    }

    private fun pickWallpaper() = pickBackground(dev.opencover.home.Slot.HOME)

    private var pickingSlot = dev.opencover.home.Slot.HOME

    /** Photo picker for a still or a GIF, for Home or the lock screen. */
    fun pickBackground(slot: dev.opencover.home.Slot) {
        pickingSlot = slot
        try {
            startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), REQ_WALLPAPER, AppLauncher.options())
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open the photo picker", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshApps() {
        apps = repo.load()
    }

    private fun loadLayout() {
        val (l, freed) = store.load { defaultLayout(apps) }
            .pruneMissing(
                apps.map { it.key }.toSet(),
                userReady = { serial -> repo.userReady(serial) && apps.any { it.user == serial } },
                widgetExists = widgets::exists,
            )
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
        const val EXTRA_UNLOCKED = "unlocked"
        /** The launcher while it's in front (resumed), else null. */
        var current: CoverActivity? = null
            private set

        // 748×720 px at 340 dpi ≈ 352×339 dp, split 4×4.
        private const val CELL_W_DP = 88
        private const val CELL_H_DP = 84
        private const val REQ_WALLPAPER = 21
    }
}

/** Home's own entrance after unlock (the system's app-open animation is off): a quick fade and settle. */
@androidx.compose.runtime.Composable
private fun Entrance(key: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
    val t = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(key) {
        if (key == 0) return@LaunchedEffect
        t.snapTo(0f)
        t.animateTo(1f, androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.graphicsLayer {
        alpha = t.value
        scaleX = 0.96f + 0.04f * t.value
        scaleY = scaleX
    }) { content() }
}
