package dev.opencover.watcher

import android.accessibilityservice.AccessibilityService
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

    /** Listen to Samsung's system UI plus the banking apps, so we can step aside for them. */
    fun refreshScope() {
        serviceInfo = serviceInfo.apply {
            packageNames = (setOf("com.android.systemui") + BankingMode.apps(this@CoverAccessibility)).toTypedArray()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
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
        var instance: CoverAccessibility? = null
            private set
    }
}
