package dev.opencover.watcher

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.pm.LauncherApps
import android.os.Process
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
import dev.opencover.apps.AppLauncher
import dev.opencover.ui.CoverActivity

/**
 * Opens the launcher when the cover screen becomes usable (unlock, or fold while unlocked).
 * The Home redirect lives in [CoverAccessibility], whose presence also exempts these starts
 * from Android 16's background-activity-start block.
 */
class CoverWatcher : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var displays: DisplayManager
    private lateinit var keyguard: KeyguardManager
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

    // Banking mode: while paused, check once a second whether the user has left the banking app.
    private var lastSeen = 0L
    private val bankingPoll = object : Runnable {
        override fun run() {
            val paused = BankingMode.pausedFor(this@CoverWatcher) ?: return
            if (leftBankingApp(paused)) BankingMode.resume(this@CoverWatcher) else handler.postDelayed(this, 1000)
        }
    }

    fun watchForResume() {
        lastSeen = System.currentTimeMillis()
        handler.removeCallbacks(bankingPoll)
        handler.post(bankingPoll)
    }

    private fun leftBankingApp(paused: String): Boolean {
        val events = getSystemService(UsageStatsManager::class.java).queryEvents(lastSeen, System.currentTimeMillis())
        val e = UsageEvents.Event()
        val launcherApps = getSystemService(LauncherApps::class.java)
        var left = false
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                left = shouldResume(paused, e.packageName, e.className, packageName, BankingMode.apps(this)) {
                    launcherApps.getActivityList(it, Process.myUserHandle()).isNotEmpty()
                }
            }
            lastSeen = maxOf(lastSeen, e.timeStamp + 1)
        }
        return left
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (BankingMode.pausedFor(this) != null) watchForResume()
        displays = getSystemService(DisplayManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        displays.registerDisplayListener(displayListener, handler)
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        onCoverStateMaybeChanged()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        displays.unregisterDisplayListener(displayListener)
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun coverActive() =
        displays.getDisplay(COVER_DISPLAY)?.state == Display.STATE_ON &&
            displays.getDisplay(Display.DEFAULT_DISPLAY)?.state != Display.STATE_ON

    private fun onCoverStateMaybeChanged() {
        KeepAwake.attach(this) // retries if the overlay permission arrived after we started
        val active = coverActive()
        if (active && !wasActive && !keyguard.isKeyguardLocked) openLauncher("fold")
        if (displays.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON) KeepAwake.set(this, false)
        wasActive = active
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
        var instance: CoverWatcher? = null
            private set
        private const val CHANNEL = "watcher"
        private const val TAG = "CoverWatcher"
    }
}
