package dev.opencover.watcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityWindowInfo
import android.app.KeyguardManager
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import dev.opencover.COVER_DISPLAY
import dev.opencover.SAMSUNG_COVER_HOME
import dev.opencover.apps.AppLauncher
import dev.opencover.keyboard.KeyboardPrefs
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

    override fun onServiceConnected() {
        instance = this
        refreshScope()
        if (PhonePrefs(this).rotationResetPending) RotationFeature.resetToDefault(this)
    }

    override fun onDestroy() {
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
        val typing = KeyboardPrefs.enabled(this)
        if (typing && keyboard == null) keyboard = OverlayKeyboard(this)
        if (!typing) keyboard?.hide()
        serviceInfo = serviceInfo.apply {
            // A turned app needs its windows seen from every app; screen content is still never read.
            packageNames = if (topEdge || PhonePrefs(this@CoverAccessibility).hasRotations) null
            else (setOf("com.android.systemui") + BankingMode.apps(this@CoverAccessibility)).toTypedArray()
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                (if (topEdge) AccessibilityEvent.TYPE_WINDOWS_CHANGED else 0)
            flags = (if (topEdge) flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
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
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        if (event.displayId == COVER_DISPLAY && pkg != null) when (pkg) {
            packageName -> {} // OpenCover's overlays; CoverActivity uprights itself
            "com.android.systemui" -> if (event.className?.toString() == SAMSUNG_COVER_HOME) RotationFeature.upright(this)
            else -> RotationFeature.onWindow(this, pkg)
        }
        if (pkg != null && pkg in BankingMode.apps(this)) {
            BankingMode.pause(this, pkg) // opened some other way than from OpenCover
            return
        }
        if (event.displayId != COVER_DISPLAY || CoverWatcher.paused) return
        if (event.className?.toString() != SAMSUNG_COVER_HOME) return
        // Samsung's cover home also sits behind the lock screen; redirecting there fights the
        // PIN/face unlock UI (flicker). CoverWatcher opens the launcher on unlock instead.
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        Log.i(TAG, "open launcher (home)")
        AppLauncher.start(this, Intent(this, CoverActivity::class.java))
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
