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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
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
    private var pickingBanking by mutableStateOf(false) // the Banking apps step of the checklist
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
    // Compose's main dispatcher: it carries the frame clock animations need (lifecycleScope's alone doesn't).
    private val UiClock = androidx.compose.ui.platform.AndroidUiDispatcher.Main

    /** Home's motion (see [Entrance]): 1 at rest. */
    private val motion = androidx.compose.animation.core.Animatable(1f)
    private val enter = androidx.compose.animation.core.tween<Float>(320, easing = androidx.compose.animation.core.LinearOutSlowInEasing)

    /** Home settles into place: after unlock, and back from an app. */
    private fun playEntrance() {
        // From a dimmed, slightly zoomed Home, never from black: a black frame reads as a glitch.
        lifecycleScope.launch(UiClock) { motion.snapTo(0.35f); runCatching { motion.animateTo(1f, enter) }.onFailure { motion.snapTo(1f) } }
    }
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
                // A card grows out of where it was tapped (an icon-sized spot around the touch) to fill the screen, with the
                // app's icon, while Home zooms and dims behind it; then the app fades in over the card. Back on Home it
                // settles in again (onResume). If the app didn't open (still here a moment later), the card shrinks away.
                val r = (56 * resources.displayMetrics.density / 2).toInt()
                val (x, y) = lastDown
                val from = android.graphics.Rect(x - r, y - r, x + r, y + r)
                opening = Opening(from, repo.icon(pkg, activity, user))
                lifecycleScope.launch(UiClock) {
                    launch { runCatching { motion.animateTo(0.5f, androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing)) } }
                    runCatching { grow.snapTo(0f); grow.animateTo(1f, androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
                    // Didn't open: we're still in OpenCover, so turn accessibility straight back on.
                    AppLauncher.launch(this@CoverActivity, pkg, activity, user, { BankingMode.resume(this@CoverActivity) }, window.decorView, from)
                    kotlinx.coroutines.delay(1_500)
                    if (current == this@CoverActivity && opening != null) {
                        launch { runCatching { motion.animateTo(1f, enter) }.onFailure { motion.snapTo(1f) } }
                        runCatching { grow.animateTo(0f, androidx.compose.animation.core.tween(200)) }
                        opening = null
                    }
                }
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
                Box(Modifier.fillMaxSize()) {
                Crossfade(locked, animationSpec = tween(260), label = "lock") { isLocked ->
                    when {
                        isLocked && LockPrefs.lockScreen -> LockLayer(this@CoverActivity)
                        isLocked -> Box(Modifier.fillMaxSize().background(Color.Black)) // not shown over the keyguard then
                        required && !showSetup -> Entrance(motion.value) { Shell(layout, apps, actions, prompt, Wallpaper.homeStill, homeRequests, quickSettingsRequests) }
                        pickingBanking -> {
                            var selected by remember { mutableStateOf(BankingMode.apps(this@CoverActivity)) }
                            BankingPicker(
                                apps = apps, selected = selected,
                                onChange = { selected = it; BankingMode.setApps(this@CoverActivity, it) },
                                onClose = { pickingBanking = false },
                                needsUsageAccess = selected.isNotEmpty() && !Permissions.usage(this@CoverActivity),
                                onGrantUsage = { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS, withPackage = true) },
                            )
                        }
                        else -> Checklist(
                            steps = setupSteps(),
                            onAppInfo = { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true) },
                            onDone = if (required) ({ showSetup = false }) else null,
                        )
                    }
                }
                opening?.let { o -> OpeningCard(o.from, o.icon, grow.value) }
                cropping?.let { job ->
                    if (job.desk) CropScreen(job.image, job.start, onDone = { crop -> cropping = null; deskCropped(job, crop) },
                        onCancel = { cropping = null; if (job.picked) job.file.delete() },
                        aspect = dev.opencover.desk.DeskBackground.ASPECT, hint = "Drag and pinch to choose what the monitor shows")
                    else CropScreen(job.image, job.start, onDone = { crop ->
                        cropping = null
                        if (!Wallpaper.save(this@CoverActivity, job.file, job.slot, crop))
                            Toast.makeText(this@CoverActivity, "Couldn't use that image", Toast.LENGTH_SHORT).show()
                    }, onCancel = { cropping = null; if (job.picked) job.file.delete() })
                }
                }
            }
        }
    }

    /**
     * A background being cropped: the file, its picture, where to start; [picked]: just picked (else cropping again);
     * [desk]: the desktop's on a monitor (else the cover's, for [slot]).
     */
    private class CropJob(val slot: dev.opencover.home.Slot, val file: java.io.File, val image: android.graphics.Bitmap,
        val start: dev.opencover.home.Crop?, val picked: Boolean, val desk: Boolean = false)

    private var cropping by mutableStateOf<CropJob?>(null)

    /** Choose again which part of the slot's background shows. False when there's nothing to crop. */
    fun adjustBackground(slot: dev.opencover.home.Slot): Boolean {
        val f = Wallpaper.source(this, slot) ?: return false
        val image = Wallpaper.preview(f) ?: return false
        val start = if (slot == dev.opencover.home.Slot.HOME) Wallpaper.homeCrop else Wallpaper.lockCrop
        cropping = CropJob(slot, f, image, start, picked = false)
        return true
    }

    /** Choose again which part of the desktop's background the monitor shows. False when there's none. */
    fun adjustDeskBackground(): Boolean {
        val f = dev.opencover.desk.DeskBackground.source(this) ?: return false
        val image = dev.opencover.desk.DeskBackground.preview(f) ?: return false
        cropping = CropJob(dev.opencover.home.Slot.HOME, f, image, dev.opencover.desk.DeskBackground.crop(this), picked = false, desk = true)
        return true
    }

    /** Photo picker for the desktop's own background: an image, GIF, WebP or video. */
    fun pickDeskBackground() {
        try {
            startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES), REQ_DESK_WALLPAPER, AppLauncher.options())
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open the photo picker", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deskCropped(job: CropJob, crop: dev.opencover.home.Crop) {
        val app = applicationContext
        if (job.picked) {
            if (!dev.opencover.desk.DeskBackground.saveOwn(app, job.file, crop)) {
                Toast.makeText(this, "Couldn't use that file", Toast.LENGTH_SHORT).show()
                return
            }
        } else dev.opencover.desk.DeskBackground.setCrop(app, crop)
        // An animation's video takes a few seconds to make; the monitor shows the new background once it's ready.
        Thread {
            dev.opencover.desk.DeskBackground.makeVideo(app)
            android.os.Handler(android.os.Looper.getMainLooper()).post { dev.opencover.desk.Desk.backgroundChanged() }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun setupSteps() = listOfNotNull(
        SetupStep("Accessibility (required)", "Brings OpenCover back when you swipe Home, and opens it on unlock",
            Permissions.accessibility(this)) { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
        SetupStep("Display over other apps (required)", "Keeps the cover screen awake when you ask",
            Permissions.overlay(this)) { openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPackage = true) },
        // Off on a fresh install (the AOD keeps the cover dimly lit); one tap turns both on, then Samsung's step shows.
        SetupStep("OpenCover AOD and lock screen (recommended)", "OpenCover's clock while the phone sleeps, and its lock screen. Tap to turn both on; change them in Settings > Lock screen & AOD",
            LockPrefs.aod && LockPrefs.lockScreen) {
            LockPrefs.setAod(this, true)
            LockPrefs.setLockScreen(this, true)
            dev.opencover.watcher.CoverAccessibility.instance?.refreshScope()
            applyLockScreen()
        },
        if (!LockPrefs.aod) null else SetupStep("Samsung AOD set to Always (for OpenCover AOD)",
            "Always On Display: Always (not Tap to show). OpenCover draws over it. Tap to choose Always.", aodReady(this)) {
            // Samsung's "When to show" page, on the cover (its app is on the cover list, see CoverAllowList). Apps can't
            // write these settings themselves, even with secure settings.
            listOf("com.samsung.android.app.aodservice.settings.AODDisplayMode",
                "com.samsung.android.app.aodservice.settings.AODSettingsMain", Settings.ACTION_DISPLAY_SETTINGS)
                .firstOrNull { runCatching { AppLauncher.startApp(this, Intent(it)) }.isSuccess }
        },
        SetupStep("Ignore battery optimization (recommended)", "Stops Android from putting OpenCover to sleep",
            Permissions.battery(this)) { openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPackage = true) },
        SetupStep("Usage access (optional)", "Sorts the app drawer by recently used",
            Permissions.usage(this)) { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS, withPackage = true) },
        SetupStep("Notification access (recommended)", "Cover notifications, pop-ups, media player and edge lighting",
            Permissions.notificationAccess(this)) {
            AppLauncher.start(this, Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(this, NotificationAccess::class.java).flattenToString(),
            ))
        },
        SetupStep("Banking apps (recommended)", "Banking apps and other apps that refuse to run while accessibility is on: pick them and OpenCover steps aside while they're open",
            BankingMode.apps(this).isNotEmpty()) { pickingBanking = true },
        SetupStep("Secure settings (recommended)",
            "Lets every app open on the cover without Shizuku, and banking apps resume. Run once on a computer: adb shell pm grant dev.opencover android.permission.WRITE_SECURE_SETTINGS",
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

    /** When Home went out of sight (onStop), 0 while it's shown. */
    private var hiddenAt = 0L

    override fun onStop() {
        hiddenAt = android.os.SystemClock.uptimeMillis()
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
        // Settle in only when Home was really out of sight for a moment (an app, the lock screen): a Home swipe on Home
        // only passes through Samsung's cover home for an instant, and replaying would flash.
        if (hiddenAt != 0L && android.os.SystemClock.uptimeMillis() - hiddenAt > 400) playEntrance()
        else lifecycleScope.launch(UiClock) { motion.snapTo(1f) }
        hiddenAt = 0L
        opening = null; lifecycleScope.launch(UiClock) { grow.snapTo(0f) }
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
        if (requestCode == REQ_DESK_WALLPAPER) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                val f = runCatching {
                    java.io.File(cacheDir, "desk.pick").also { f -> contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } } }
                }.getOrNull()
                val image = f?.let { dev.opencover.desk.DeskBackground.preview(it) }
                if (f == null || image == null) Toast.makeText(this, "Couldn't use that file", Toast.LENGTH_SHORT).show()
                else cropping = CropJob(dev.opencover.home.Slot.HOME, f, image, null, picked = true, desk = true)
            }
            return
        }
        if (requestCode == REQ_WALLPAPER) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                val f = Wallpaper.import(this, uri, pickingSlot)
                val image = f?.let { Wallpaper.preview(it) }
                if (f == null || image == null) Toast.makeText(this, "Couldn't use that image", Toast.LENGTH_SHORT).show()
                else cropping = CropJob(pickingSlot, f, image, null, picked = true)
            }
            return
        }
        if (!widgets.onResult(requestCode, resultCode)) super.onActivityResult(requestCode, resultCode, data)
    }

    // Every launch through AppLauncher.start (Home gesture, unlock, fold) lands here: go to Home.
    // A touch on Home or the lock screen during edge lighting keeps the cover awake afterwards.
    /** An app being opened: the card growing out of [from] (window pixels) with its [icon]. */
    private class Opening(val from: android.graphics.Rect, val icon: androidx.compose.ui.graphics.ImageBitmap?)
    private var opening by mutableStateOf<Opening?>(null)
    private val grow = androidx.compose.animation.core.Animatable(0f)

    /** Where the last touch went down, in this window: an app opened from it grows out of that spot. */
    private var lastDown = 0 to 0

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        dev.opencover.edge.EdgeLight.lastTouch = android.os.SystemClock.uptimeMillis()
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) lastDown = ev.x.toInt() to ev.y.toInt()
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
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
        private const val REQ_DESK_WALLPAPER = 22
    }
}

/**
 * Home's own motion (the system's app animations are off on the cover). [shown] 1 is Home at rest; towards 0 it grows
 * and fades, as if you were diving into the app: opening an app plays 1 to 0, and coming back (or unlocking) 0 to 1,
 * so Home settles back into place as the app shrinks away.
 */
@androidx.compose.runtime.Composable
private fun Entrance(shown: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
    // Fades through black, not to transparent: the window behind Home is Samsung's cover home.
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(Color.Black)) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.graphicsLayer {
            alpha = shown
            scaleX = 1f + 0.08f * (1f - shown)
            scaleY = scaleX
        }) { content() }
    }
}

/** The card an app opens from: grows from [from] (window pixels) to the whole screen as [t] goes 0 to 1. */
@androidx.compose.runtime.Composable
private fun OpeningCard(from: android.graphics.Rect, icon: androidx.compose.ui.graphics.ImageBitmap?, t: Float) {
    if (t <= 0f) return
    androidx.compose.foundation.layout.BoxWithConstraints(androidx.compose.ui.Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        fun lerp(a: Float, b: Float) = a + (b - a) * t
        val left = lerp(from.left.toFloat(), 0f); val top = lerp(from.top.toFloat(), 0f)
        val right = lerp(from.right.toFloat(), w); val bottom = lerp(from.bottom.toFloat(), h)
        val d = androidx.compose.ui.platform.LocalDensity.current
        val corner = with(d) { lerp(from.width() / 2f, 28.dp.toPx()).toDp() }
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier
                .offset { androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()) }
                .size(with(d) { (right - left).toDp() }, with(d) { (bottom - top).toDp() })
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(corner))
                .background(Color(0xFF16161A).copy(alpha = (t * 3f).coerceAtMost(1f))),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            if (icon != null) androidx.compose.foundation.Image(icon, null,
                androidx.compose.ui.Modifier.size(with(d) { (from.width() * (1f + 0.4f * t)).toDp() }))
        }
    }
}
