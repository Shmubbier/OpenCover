package dev.opencover.watcher

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import dev.opencover.COVER_DISPLAY
import dev.opencover.SAMSUNG_COVER_HOME
import dev.opencover.apps.AppLauncher
import dev.opencover.ui.CoverActivity

/**
 * Opens the launcher when the cover screen becomes usable, and brings it back whenever
 * Samsung's cover home is resumed (the user swiped Home) unless [paused].
 */
class CoverWatcher : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var displays: DisplayManager
    private lateinit var usage: UsageStatsManager
    private lateinit var keyguard: KeyguardManager
    private var lastSeen = 0L
    private var polling = false
    private var wasActive = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) = onCoverStateMaybeChanged()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_USER_PRESENT -> if (coverActive()) openLauncher("unlock")
                Intent.ACTION_SCREEN_OFF -> paused = false
            }
            onCoverStateMaybeChanged()
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!coverActive()) {
                polling = false
                return
            }
            if (!paused && samsungHomeResumed()) openLauncher("home")
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate() {
        super.onCreate()
        displays = getSystemService(DisplayManager::class.java)
        usage = getSystemService(UsageStatsManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        displays.registerDisplayListener(displayListener, handler)
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        lastSeen = System.currentTimeMillis()
        onCoverStateMaybeChanged()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        displays.unregisterDisplayListener(displayListener)
        unregisterReceiver(receiver)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun coverActive() =
        displays.getDisplay(COVER_DISPLAY)?.state == Display.STATE_ON &&
            displays.getDisplay(Display.DEFAULT_DISPLAY)?.state != Display.STATE_ON

    private fun onCoverStateMaybeChanged() {
        val active = coverActive()
        if (active && !wasActive && !keyguard.isKeyguardLocked) openLauncher("fold")
        if (displays.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON) KeepAwake.set(this, false)
        wasActive = active
        if (active && !polling) {
            polling = true
            lastSeen = System.currentTimeMillis()
            handler.post(poll)
        }
    }

    private fun samsungHomeResumed(): Boolean {
        val events = usage.queryEvents(lastSeen, System.currentTimeMillis())
        val e = UsageEvents.Event()
        var top: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) top = e.className
            lastSeen = maxOf(lastSeen, e.timeStamp + 1)
        }
        if (top != null) Log.d(TAG, "resumed $top")
        return top == SAMSUNG_COVER_HOME
    }

    private fun openLauncher(why: String) {
        Log.i(TAG, "open launcher ($why)")
        AppLauncher.start(this, Intent(this, CoverActivity::class.java))
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Cover launcher", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("OpenCover is running")
            .build()
    }

    companion object {
        /** Set by "Open Samsung widgets"; cleared when the screen turns off. */
        @Volatile var paused = false
        private const val CHANNEL = "watcher"
        private const val TAG = "CoverWatcher"
    }
}
