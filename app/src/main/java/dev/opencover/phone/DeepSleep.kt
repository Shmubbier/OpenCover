package dev.opencover.phone

import android.content.Context
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import dev.opencover.quick.ShizukuShell

/**
 * Deep doze when the phone is idle. Android only enters deep doze after its motion sensor says the phone has been
 * still, and this phone's motion sensors are dead, so it never did (0 mAh in deep doze in the battery stats). Shortly
 * after the screen turns off, and while not charging, deep doze is forced through Shizuku; any wake ends it. The AOD
 * clock keeps updating in it.
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
        handler.postDelayed({
            if (!charging(app) && !app.getSystemService(android.os.PowerManager::class.java).isInteractive) {
                forced = true
                Thread { ShizukuShell.run("dumpsys", "deviceidle", "force-idle", "deep") }.start()
            }
        }, AFTER_MS)
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
