package dev.opencover.lock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings

/**
 * Samsung's power saving turns the always on display off, OpenCover's with it. OpenCover notices it coming on and says
 * so, with a button to turn it off; it switches through the "low_power" setting, which the system follows and
 * OpenCover may write (secure settings).
 */
object PowerSaving {
    const val TURN_OFF = "dev.opencover.POWER_SAVING_OFF"
    private const val CHANNEL = "power_saving"
    private const val ID = 4

    fun on(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isPowerSaveMode

    /** False without the secure settings permission. */
    fun set(ctx: Context, on: Boolean) = runCatching { Settings.Global.putInt(ctx.contentResolver, "low_power", if (on) 1 else 0) }.isSuccess

    /** Power saving changed: warn while it's on and OpenCover's AOD is wanted, clear the warning once it's off. */
    fun changed(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!on(ctx) || !LockPrefs.aod) return nm.cancel(ID)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Power saving", NotificationManager.IMPORTANCE_DEFAULT))
        val off = PendingIntent.getBroadcast(ctx, 0, Intent(TURN_OFF).setPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle("Power saving is on")
            .setContentText("It turns off the always on display, OpenCover's included.")
            .addAction(Notification.Action.Builder(null, "Turn off", off).build())
            .setAutoCancel(true)
            .build())
    }
}
