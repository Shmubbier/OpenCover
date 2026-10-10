package dev.opencover.watcher

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import dev.opencover.COVER_DISPLAY

/**
 * An invisible 1x1 overlay on the cover display, attached while CoverWatcher runs.
 * - Its presence is what lets CoverWatcher reopen the launcher from the background: since
 *   Android 15 the overlay-permission exemption only applies while the app has a visible
 *   overlay window (logcat: BAL_BLOCK, callingUidHasNonAppVisibleWindow=false).
 * - Keep-awake just adds FLAG_KEEP_SCREEN_ON to it.
 */
object KeepAwake {
    private var view: View? = null
    var isOn = false
        private set

    /** No-op until "Display over other apps" is granted; callers retry on later events. */
    fun attach(ctx: Context) {
        if (view != null || !Settings.canDrawOverlays(ctx)) return
        val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(COVER_DISPLAY) ?: return
        val wctx = ctx.applicationContext.createDisplayContext(display)
            .createWindowContext(LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val v = View(wctx)
        wctx.getSystemService(WindowManager::class.java).addView(v, params())
        view = v
    }

    fun set(ctx: Context, on: Boolean) {
        if (on == isOn) return
        isOn = on
        attach(ctx)
        view?.let { it.context.getSystemService(WindowManager::class.java).updateViewLayout(it, params()) }
    }

    private fun params() = LayoutParams(
        1, 1, LayoutParams.TYPE_APPLICATION_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or
            (if (isOn) LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
        PixelFormat.TRANSPARENT,
    )
}
