package dev.opencover.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import dev.opencover.mirror.MirrorService
import dev.opencover.quick.ShizukuShell

/**
 * Watches Android's thermal status and the battery temperature. While the hotspot or mirroring is on, warm means a
 * warning; hot also turns the hotspot off and ends mirroring. Runs in CoverWatcher, so it keeps working while
 * banking mode or mirroring has turned the accessibility service off. Main thread only.
 */
object HeatGuard {
    private const val CHANNEL = "heat"
    private const val NOTIFICATION_ID = 3
    private const val AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private var app: Context? = null
    private var status = 0
    private var tenths = 0
    private var hotspot = false
    private var level = Heat.OK // follows the temperature whether or not anything is shown
    private var told = Heat.OK // what the notification says

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, tenths)
                AP_STATE_CHANGED -> hotspot = intent.getIntExtra("wifi_state", 0) == AP_ENABLED
            }
            evaluate()
        }
    }

    private val thermal = PowerManager.OnThermalStatusChangedListener {
        status = it
        evaluate()
    }

    fun start(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        c.getSystemService(PowerManager::class.java).apply {
            status = currentThermalStatus
            addThermalStatusListener(c.mainExecutor, thermal)
        }
        // Both are sticky: registering hands back the current battery and hotspot state.
        c.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { receiver.onReceive(c, it) }
        c.registerReceiver(receiver, IntentFilter(AP_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED)?.let { receiver.onReceive(c, it) }
    }

    fun stop() {
        app?.let {
            it.getSystemService(PowerManager::class.java).removeThermalStatusListener(thermal)
            runCatching { it.unregisterReceiver(receiver) }
        }
        app = null
    }

    /** Too hot to start mirroring (the guard would end it at once). */
    fun hot(): Boolean = app?.let { PhonePrefs(it).heatGuard && heatLevel(status, tenths, level) == Heat.CUT } == true

    /** Re-checks now; also called when the setting changes. */
    fun evaluate() {
        val s = app ?: return
        val nm = s.getSystemService(NotificationManager::class.java)
        level = heatLevel(status, tenths, level)
        val now = if (PhonePrefs(s).heatGuard) shown(level, hotspot || MirrorService.instance != null) else Heat.OK
        val step = heatStep(now, told)
        told = now
        if (now == Heat.OK) nm.cancel(NOTIFICATION_ID)
        if (step.cut) {
            MirrorService.instance?.stop()
            if (hotspot) Thread { ShizukuShell.run(*STOP_HOTSPOT.toTypedArray()) }.start()
        }
        if (step.notify) notify(s, nm, now)
    }

    private fun notify(s: Context, nm: NotificationManager, now: Heat) {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Heat guard", NotificationManager.IMPORTANCE_HIGH))
        val temp = "%.1f".format(tenths / 10.0)
        val text = when (now) {
            Heat.CUT -> "Phone is hot ($temp °C). Mirroring and the hotspot were stopped. Let it cool."
            else -> "Phone is warm ($temp °C). Consider stopping the hotspot or mirroring."
        }
        val open = PendingIntent.getActivity(
            s, 0, Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIFICATION_ID,
            Notification.Builder(s, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Heat guard").setContentText(text).setContentIntent(open).setAutoCancel(true).build(),
        )
    }
}
