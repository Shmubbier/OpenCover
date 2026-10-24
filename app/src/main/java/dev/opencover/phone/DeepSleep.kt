package dev.opencover.phone

import android.content.Context
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import dev.opencover.quick.ShizukuShell

/**
 * Deep doze when the phone is idle. Android only enters deep doze after its motion sensor says the phone has been
 * still, and this phone's motion sensors are dead, so it never did (0 mAh in deep doze in the battery stats). Shortly
 * after the screen turns off, and while not charging or playing audio, deep doze is forced through Shizuku; any wake
 * ends it. The AOD clock keeps updating in it.
 */
object DeepSleep {
    private const val AFTER_MS = 30_000L
    private val handler = Handler(Looper.getMainLooper())
    private var forced = false

    /** The screen turned off. */
    fun screenOff(ctx: Context) {
        if (!PhonePrefs(ctx).deepSleep) return
        val app = ctx.applicationContext
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ tryIdle(app) }, AFTER_MS)
    }

    private fun tryIdle(app: Context) {
        if (charging(app) || app.getSystemService(android.os.PowerManager::class.java).isInteractive) return
        // Deep doze cuts off music and videos playing with the screen off (their network and wake locks stop): wait
        // until nothing plays, checking again a little later.
        if (app.getSystemService(android.media.AudioManager::class.java).isMusicActive) {
            handler.postDelayed({ tryIdle(app) }, AFTER_MS)
            return
        }
        forced = true
        Thread { ShizukuShell.run("dumpsys", "deviceidle", "force-idle", "deep") }.start()
    }

    /** The screen turned on, or a charger was plugged in: back to normal. */
    fun awake() {
        handler.removeCallbacksAndMessages(null)
        if (!forced) return
        forced = false
        Thread { ShizukuShell.run("dumpsys", "deviceidle", "unforce") }.start()
    }

    private fun charging(ctx: Context) = ctx.getSystemService(BatteryManager::class.java).isCharging
}
