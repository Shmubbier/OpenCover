package dev.opencover.watcher

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import dev.opencover.mirror.MirrorService

/**
 * Turns OpenCover's accessibility service off while a chosen banking app is in front (some refuse
 * to run while any third-party service is on), and back on when you leave it.
 */
object BankingMode {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("banking", Context.MODE_PRIVATE)

    fun apps(ctx: Context): Set<String> = prefs(ctx).getStringSet("apps", emptySet())!!.toSet()

    fun setApps(ctx: Context, apps: Set<String>) {
        prefs(ctx).edit().putStringSet("apps", apps).apply()
        CoverAccessibility.instance?.refreshScope()
    }

    fun pausedFor(ctx: Context): String? = prefs(ctx).getString("paused", null)

    /** Turning the service back on by ourselves needs WRITE_SECURE_SETTINGS, granted once over adb. */
    fun canResume(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun pause(ctx: Context, pkg: String) {
        if (pausedFor(ctx) != null) return
        prefs(ctx).edit().putString("paused", pkg).commit()
        if (canResume(ctx)) setService(ctx, on = false) else CoverAccessibility.instance?.disableSelf()
        CoverWatcher.instance?.watchForResume()
    }

    fun resume(ctx: Context) {
        if (pausedFor(ctx) == null) return
        if (MirrorService.instance != null) return // mirroring owns the switch; unlocking after it ends resumes
        prefs(ctx).edit().remove("paused").commit()
        if (canResume(ctx)) setService(ctx, on = true) else askToReEnable(ctx)
    }

    /** Without WRITE_SECURE_SETTINGS we can't turn ourselves back on: say so, with a tap to Settings. */
    private fun askToReEnable(ctx: Context) {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("banking", "Banking mode", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(2, Notification.Builder(ctx, "banking")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Turn OpenCover back on")
            .setContentText("Accessibility was paused for a banking app. Tap to turn it back on.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    private fun setService(ctx: Context, on: Boolean) {
        val cr = ctx.contentResolver
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val me = ComponentName(ctx, CoverAccessibility::class.java).flattenToString()
        Settings.Secure.putString(cr, key, withService(Settings.Secure.getString(cr, key), me, on))
        if (on) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }
}
