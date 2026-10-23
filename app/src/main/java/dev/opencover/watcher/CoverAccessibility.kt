package dev.opencover.watcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityWindowInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import dev.opencover.COVER_DISPLAY
import dev.opencover.SAMSUNG_COVER_HOME
import dev.opencover.apps.AppLauncher
import dev.opencover.keyboard.KeyboardPrefs
import dev.opencover.call.CallStore
import dev.opencover.nav.NavBar
import dev.opencover.nav.NavPrefs
import dev.opencover.nav.navBarShown
import dev.opencover.lock.AodOverlay
import dev.opencover.lock.LockScreen
import dev.opencover.lock.LockPrefs
import dev.opencover.lock.aodReady
import dev.opencover.lock.dozing
import dev.opencover.phone.Fold
import dev.opencover.phone.PhonePrefs
import dev.opencover.phone.RotationFeature
import dev.opencover.keyboard.OverlayKeyboard
import dev.opencover.ui.CoverActivity

/**
 * Brings the launcher back when Samsung's cover home appears (the user swiped Home).
 * Being a system-bound accessibility service is also what exempts the app from Android 16's
 * background-activity-start block. Only window-state events from Samsung's system UI and the
 * user's chosen banking apps are received; screen contents are never read.
 */
class CoverAccessibility : AccessibilityService() {
    private var keyboard: OverlayKeyboard? = null

    /** Doze start and stop: the AOD overlay comes and goes with them. */
    private val dreams = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_DREAMING_STARTED -> if (LockPrefs.aod && aodReady(c)) {
                    android.util.Log.i("OpenCoverAod", "doze started: showing the AOD")
                    AodOverlay.show(this@CoverAccessibility)
                    dev.opencover.lock.AodTimeout.start(c)
                } else android.util.Log.i("OpenCoverAod", "doze started: no AOD (on=${LockPrefs.aod}, Samsung AOD ready=${aodReady(c)})")
                Intent.ACTION_DREAMING_STOPPED -> AodOverlay.hide()
                // Only a real wake: turning the AOD off itself also ends the doze.
                Intent.ACTION_SCREEN_ON -> dev.opencover.lock.AodTimeout.stop(c)
            }
            // Woken (from doze or fully off): let the windows settle, then maybe replace Samsung's lock screen.
            if (i.action != Intent.ACTION_DREAMING_STARTED && i.action != Intent.ACTION_USER_PRESENT)
                handler.postDelayed({ LockScreen.maybeShow(this@CoverAccessibility) }, 250)
            // The navigation bar hides while locked and is redrawn on window changes, but unlocking can finish after the
            // last one: redraw it once things have settled, so it doesn't stay hidden until the next change.
            if (i.action == Intent.ACTION_USER_PRESENT || i.action == Intent.ACTION_SCREEN_ON)
                listOf(300L, 1_000L).forEach { handler.postDelayed({ updateNavBar() }, it) }
        }
    }

    override fun onServiceConnected() {
        instance = this
        LockPrefs.load(this)
        registerReceiver(dreams, IntentFilter().apply {
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, RECEIVER_NOT_EXPORTED)
        // Restarted while the phone sleeps (update, low memory): the doze began before we were listening.
        val cover = getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(dev.opencover.COVER_DISPLAY)
        if (LockPrefs.aod && aodReady(this) &&
            dozing(getSystemService(android.os.PowerManager::class.java).isInteractive, cover?.state ?: android.view.Display.STATE_OFF)
        ) { AodOverlay.show(this); dev.opencover.lock.AodTimeout.start(this) }
        // Restarted after the AOD timeout turned Samsung's AOD off: turn it back on once the phone is in use.
        if (getSystemService(android.os.PowerManager::class.java).isInteractive) dev.opencover.lock.AodTimeout.stop(this)
        refreshScope()
        dev.opencover.nav.NavPrefs.syncHint(this)
        handler.post { updateNavBar() } // the window list is ready once connected
        // Shizuku state does not survive a reboot, and after one Shizuku starts later than this service: redo it
        // whenever Shizuku comes up (sticky: also right now if it already is).
        rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(shizukuUp, handler)
        getSystemService(android.hardware.display.DisplayManager::class.java).registerDisplayListener(coverTurned, handler)
    }

    /** The cover turned (an app's rotation, or an app switching between landscape and portrait): the bar moves too. */
    private val coverTurned = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayChanged(id: Int) { if (id == COVER_DISPLAY) updateNavBar() }
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
    }

    private val shizukuUp = rikka.shizuku.Shizuku.OnBinderReceivedListener {
        // Only re-hold the fold state: a reset while awake locks the phone.
        if (PhonePrefs(this).keepCoverOn) Fold.apply(this)
        if (PhonePrefs(this).rotationResetPending) RotationFeature.resetToDefault(this)
    }

    override fun onDestroy() {
        rikka.shizuku.Shizuku.removeBinderReceivedListener(shizukuUp)
        getSystemService(android.hardware.display.DisplayManager::class.java).unregisterDisplayListener(coverTurned)
        NavBar.hide()
        runCatching { unregisterReceiver(dreams) }
        AodOverlay.hide()
        keyboard?.hide()
        instance = null
        super.onDestroy()
    }

    /**
     * Default: listen to Samsung's system UI plus the banking apps only.
     * Top-edge priority: also watch the window list (all apps) to catch Samsung's panel.
     * OpenCover keyboard: the input-method flag; it needs no events or screen content.
     */
    fun refreshScope() {
        val topEdge = TopEdge.enabled(this)
        val nav = NavPrefs.enabled(this)
        val typing = KeyboardPrefs.enabled(this)
        if (typing && keyboard == null) keyboard = OverlayKeyboard(this)
        if (!typing) keyboard?.hide()
        serviceInfo = serviceInfo.apply {
            // A turned app needs its windows seen from every app; screen content is still never read.
            // Window-change events (top edge, our navigation bar) only arrive with every app in scope.
            packageNames = if (topEdge || nav || KeepOnCover.enabled(this@CoverAccessibility) || PhonePrefs(this@CoverAccessibility).hasRotations) null
            else (setOf("com.android.systemui") + BankingMode.apps(this@CoverAccessibility)).toTypedArray()
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                (if (topEdge || nav) AccessibilityEvent.TYPE_WINDOWS_CHANGED else 0)
            // The window list: top-edge priority catches Samsung's panel; the lock screen checks nothing (a call, an alarm) is
            // on top; OpenCover's navigation bar follows Samsung's.
            flags = (if (topEdge || LockPrefs.lockScreen || nav) flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            else flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS.inv()).let {
                if (typing) it or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR else it and AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR.inv()
            }
        }
    }

    /** Samsung's panel opened over OpenCover: close it and open ours instead. */
    private val handler = Handler(Looper.getMainLooper())
    private var panelUp = false

    /** Samsung's panel (window "SubScreenQuickPanel") shows up as a full-screen type -1 window on top. */
    private fun samsungPanel(): AccessibilityWindowInfo? {
        val top = windowsOnAllDisplays[COVER_DISPLAY]?.firstOrNull() ?: return null
        val isPanel = isSamsungPanel(top.type, Rect().also(top::getBoundsInScreen).height())
        return top.takeIf { isPanel }
    }

    private fun takeTopEdge() {
        val up = samsungPanel() != null
        // Act once when it appears; its closing animation also reports it "on top".
        if (up && !panelUp) CoverActivity.current?.let {
            Log.i(TAG, "replace Samsung panel")
            closeSamsungPanel(attempt = 0)
            it.requestQuickSettings()
        }
        panelUp = up
    }

    /** Back closes the panel, but it's ignored while the finger is still dragging it: retry while it has focus. */
    private fun closeSamsungPanel(attempt: Int) {
        // Dismiss-shade removes the panel window outright; Back only collapses it, leaving an invisible
        // window that swallows touches for ~2.5 s. Back stays as the fallback.
        performGlobalAction(if (attempt % 2 == 0) GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE else GLOBAL_ACTION_BACK)
        if (attempt < 8) handler.postDelayed({
            if (samsungPanel()?.isFocused == true) closeSamsungPanel(attempt + 1)
        }, 250)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (TopEdge.enabled(this)) takeTopEdge()
            updateNavBar()
            NavBar.dropCurtainIfHome(this)
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        if (pkg != null && KeepOnCover.onWindow(this, event.displayId, pkg, event.className?.toString().orEmpty())) return
        if (event.displayId == COVER_DISPLAY && pkg != null) when (pkg) {
            packageName -> {} // OpenCover's overlays; CoverActivity uprights itself
            "com.android.systemui" -> if (event.className?.toString() == SAMSUNG_COVER_HOME) RotationFeature.upright(this)
            imePackage() -> {} // the keyboard opens over the app; it must not turn the cover back upright
            else -> {
                // An app in the background still reports its window when the cover's density changes: only one in front counts.
                if (dev.opencover.ui.CoverActivity.current == null) RotationFeature.onWindow(this, pkg)
                CallStore.otherWindow(this, pkg)
            }
        }
        if (pkg != null && pkg in BankingMode.apps(this)) {
            BankingMode.pause(this, pkg) // opened some other way than from OpenCover
            return
        }
        if (event.displayId != COVER_DISPLAY || CoverWatcher.paused) return
        if (event.className?.toString() != SAMSUNG_COVER_HOME) return
        // Mirroring brings Samsung's cover home up while it switches the phone open; the mirror goes on top of it.
        if (dev.opencover.mirror.MirrorService.instance != null) return
        // Samsung's cover home also sits behind the lock screen; redirecting there fights the
        // PIN/face unlock UI (flicker). CoverWatcher opens the launcher on unlock instead.
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            handler.postDelayed({ LockScreen.maybeShow(this) }, 250) // Home pressed on our lock screen
            return
        }
        Log.i(TAG, "open launcher (home)")
        AppLauncher.start(this, Intent(this, CoverActivity::class.java))
    }

    /** OpenCover's bar over Samsung's while it's on the cover and the phone is unlocked. */
    fun updateNavBar() {
        val bar = NavBar.samsungBar(this)
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        NavBar.update(this, bar.takeIf { navBarShown(NavPrefs.enabled(this), bar != null, locked) }, keyboardUp())
    }

    /** The package of the keyboard in use ("pkg/.Service" in settings). */
    private fun imePackage(): String? =
        android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')

    /** A keyboard is up on the cover: Samsung's (an input-method window) or OpenCover's own. */
    private fun keyboardUp(): Boolean = keyboard?.showing == true || runCatching {
        windowsOnAllDisplays[dev.opencover.COVER_DISPLAY].orEmpty()
            .any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrDefault(false)

    /** Hides whichever keyboard is up: OpenCover's own, or the input method (Back closes it). */
    /**
     * The inner screen is broken, so Android's "Allow USB debugging?" (or wireless debugging) prompt, which shows only
     * there, can't be answered. On the user's tap, find that prompt on the inner screen, tick "Always allow" and tap
     * Allow. Returns what happened, for a toast.
     */
    fun approveInnerPrompt(): String {
        val info = serviceInfo
        val had = info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0
        if (!had) serviceInfo = info.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        try {
            val roots = windowsOnAllDisplays[android.view.Display.DEFAULT_DISPLAY].orEmpty()
                .filter { it.root?.packageName == "com.android.systemui" }.mapNotNull { it.root }
            for (root in roots) {
                val allow = (root.findAccessibilityNodeInfosByViewId("android:id/button1") +
                    root.findAccessibilityNodeInfosByText("Allow")).firstOrNull { n ->
                        n.isClickable && n.text?.toString()?.trim()?.equals("Allow", ignoreCase = true) == true
                    } ?: continue
                root.findAccessibilityNodeInfosByText("Always allow").firstOrNull { it.isCheckable }?.let { box ->
                    if (!box.isChecked) box.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
                return if (allow.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) "Allowed" else "Couldn't tap Allow"
            }
            return "No debugging prompt on the inner screen"
        } finally {
            if (!had) refreshScope()
        }
    }

    fun hideKeyboard() {
        if (keyboard?.showing == true) keyboard?.hide() else performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ updateNavBar() }, 300) // drop the button once the keyboard has gone
    }

    override fun onInterrupt() {}

    /** Android 13+ accessibility input method: OpenCover's keyboard types through it. */
    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) { keyboard?.startInput(attribute) }
        override fun onFinishInput() { keyboard?.finishInput() }
        override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
            keyboard?.selectionChanged()
        }
    }

    companion object {
        private const val TAG = "CoverWatcher"
        var instance: CoverAccessibility? = null
            private set
    }
}
