package dev.opencover.watcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import android.app.KeyguardManager
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.opencover.COVER_DISPLAY
import dev.opencover.SAMSUNG_COVER_HOME
import dev.opencover.apps.AppLauncher
import dev.opencover.ui.CoverActivity

/**
 * Brings the launcher back when Samsung's cover home appears (the user swiped Home).
 * Being a system-bound accessibility service is also what exempts the app from Android 16's
 * background-activity-start block. Only window-state events from Samsung's system UI and the
 * user's chosen banking apps are received; screen contents are never read.
 */
class CoverAccessibility : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        refreshScope()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Default: listen to Samsung's system UI plus the banking apps only.
     * With top-edge priority on: also watch the window list (all apps) to catch Samsung's panel.
     */
    fun refreshScope() {
        val topEdge = TopEdge.enabled(this)
        serviceInfo = serviceInfo.apply {
            packageNames = if (topEdge) null
            else (setOf("com.android.systemui") + BankingMode.apps(this@CoverAccessibility)).toTypedArray()
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                (if (topEdge) AccessibilityEvent.TYPE_WINDOWS_CHANGED else 0)
            flags = if (topEdge) flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            else flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS.inv()
        }
    }

    /** Samsung's panel opened over OpenCover: close it and open ours instead. */
    private fun takeTopEdge() {
        val activity = CoverActivity.current ?: return
        val cover = windowsOnAllDisplays[COVER_DISPLAY] ?: return
        // The panel is a large system window; the status bar itself is a thin one.
        val panelOpen = cover.any { w ->
            w.type == AccessibilityWindowInfo.TYPE_SYSTEM && Rect().also(w::getBoundsInScreen).height() > COVER_HEIGHT_PX / 2
        }
        if (!panelOpen) return
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        activity.requestQuickSettings()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (TopEdge.enabled(this)) takeTopEdge()
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
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

    companion object {
        private const val TAG = "CoverWatcher"
        private const val COVER_HEIGHT_PX = 720
        var instance: CoverAccessibility? = null
            private set
    }
}
