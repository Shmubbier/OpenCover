package dev.opencover.watcher

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import dev.opencover.COVER_DISPLAY

/** Keeps the cover screen on in any app via an invisible 1x1 overlay with FLAG_KEEP_SCREEN_ON. */
object KeepAwake {
    private var view: View? = null
    val isOn get() = view != null

    fun set(ctx: Context, on: Boolean) {
        if (on == isOn) return
        if (!on) {
            view?.let { it.context.getSystemService(WindowManager::class.java).removeView(it) }
            view = null
            return
        }
        val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(COVER_DISPLAY) ?: return
        val wctx = ctx.applicationContext.createDisplayContext(display)
            .createWindowContext(LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val v = View(wctx)
        val params = LayoutParams(
            1, 1, LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSPARENT,
        )
        wctx.getSystemService(WindowManager::class.java).addView(v, params)
        view = v
    }
}
