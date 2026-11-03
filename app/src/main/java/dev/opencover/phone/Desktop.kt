package dev.opencover.phone

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import dev.opencover.COVER_DISPLAY
import dev.opencover.quick.ShizukuShell

/**
 * An external monitor (DeX, Android's desktop mode, or a plain cable/wireless screen). While one is connected the
 * cover keeps its own rotation and density (per-app ones would follow apps onto the monitor), nothing is moved to the
 * cover, and the camera can be turned 180° for a phone standing upside down by the monitor.
 */
object Desktop {
    /** AOSP's developer options for desktop windows: freeform, resizable apps, desktop mode on external displays. */
    private val FORCE_KEYS = listOf("enable_freeform_support", "force_resizable_activities", "force_desktop_mode_on_external_displays")

    /** Cable and wireless monitors are presentation displays; the cover is skipped in case it reports as one. */
    fun connected(ctx: Context): Boolean =
        ctx.getSystemService(DisplayManager::class.java).getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .any { it.displayId != COVER_DISPLAY && it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF }

    private var was = false // main thread

    /** A display came, went or changed (CoverWatcher's listener, main thread). */
    fun onDisplaysChanged(ctx: Context) {
        val now = connected(ctx)
        if (now == was) return
        was = now
        if (!ShizukuShell.allowed()) return
        if (now) RotationFeature.upright(ctx) // the cover back to its own rotation and density
        if (PhonePrefs(ctx).desktopCamera180) applyCamera(ctx)
    }

    /** Turns every app's camera 180° while a monitor is connected, back otherwise (rotate-and-crop override). */
    fun applyCamera(ctx: Context) {
        // 2 = ROTATE_AND_CROP_180, 4 = AUTO (no override). Cameras without rotate-and-crop ignore it.
        val mode = if (connected(ctx) && PhonePrefs(ctx).desktopCamera180) "2" else "4"
        Thread { ShizukuShell.run("cmd", "media.camera", "set-rotate-and-crop", mode) }.start()
    }

    /** Desktop windows forced on for every app; null when Shizuku can't read it. Blocking. */
    fun forced(): Boolean? = ShizukuShell.output("settings", "get", "global", FORCE_KEYS.last())?.trim()?.let { it == "1" }

    /** Off deletes the settings, back to the system's defaults. Blocking. */
    fun setForced(on: Boolean): Boolean =
        ShizukuShell.run("sh", "-c", FORCE_KEYS.joinToString("; ") { if (on) "settings put global $it 1" else "settings delete global $it" })
}
