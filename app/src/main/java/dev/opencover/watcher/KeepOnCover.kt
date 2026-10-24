package dev.opencover.watcher

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import dev.opencover.COVER_DISPLAY
import dev.opencover.apps.AppLauncher
import dev.opencover.mirror.MirrorService
import dev.opencover.quick.ShizukuShell

/**
 * While the phone is folded, an app that opens itself on the inner screen (a splash or login screen starting its main
 * screen there, a notification tapped) is moved to the cover, through Shizuku. Without Shizuku there is nothing to do:
 * Samsung then only lets apps on Good Lock MultiStar's cover list open on the cover, and keeps those there itself.
 */
object KeepOnCover {
    private const val TAG = "KeepOnCover"
    private var lastPkg: String? = null
    private var lastAt = 0L

    /** Apps that belong on the inner screen or aren't apps: never moved. */
    private val SKIP = setOf("com.android.systemui", "android", "com.sec.android.app.launcher", "com.samsung.android.sm")

    fun enabled(ctx: Context) = ctx.getSharedPreferences("phone", Context.MODE_PRIVATE).getBoolean("keep_on_cover", true)
    fun setEnabled(ctx: Context, on: Boolean) = ctx.getSharedPreferences("phone", Context.MODE_PRIVATE).edit().putBoolean("keep_on_cover", on).apply()

    /** Folded: the cover is on and the inner screen off, and nothing (mirroring) holds the phone open on purpose. */
    private fun folded(ctx: Context): Boolean {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        return MirrorService.instance == null &&
            dm.getDisplay(COVER_DISPLAY)?.state == Display.STATE_ON && dm.getDisplay(Display.DEFAULT_DISPLAY)?.state != Display.STATE_ON
    }

    /** A window came up. Returns true if it was handled here. */
    fun onWindow(service: AccessibilityService, displayId: Int, pkg: String, cls: String): Boolean {
        if (!enabled(service) || !folded(service)) return false
        if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        if (displayId != Display.DEFAULT_DISPLAY || pkg in SKIP || pkg == service.packageName) return false
        if (listOf("dialog", "popup", "bottomsheet", "menu").any { cls.contains(it, ignoreCase = true) }) return false
        if (!ShizukuShell.allowed()) return false
        Log.i(TAG, "$pkg opened on the inner screen while folded: moving it to the cover")
        move(service, pkg)
        return true
    }

    private fun move(ctx: Context, pkg: String) {
        // At most once every 3 s per app, so an app that insists on the inner screen can't loop.
        val now = SystemClock.uptimeMillis()
        if (pkg == lastPkg && now - lastAt < 3_000) return
        lastPkg = pkg; lastAt = now
        Thread { AppLauncher.moveToCover(ctx, pkg) }.start()
    }
}
