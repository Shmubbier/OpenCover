package dev.opencover.desk

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import dev.opencover.COVER_DISPLAY

/**
 * The cover goes black while the desk is in use (its pixels off, so it costs next to nothing; the phone itself has to
 * stay awake for the monitor). A tap brings the cover back; it goes black again after [IDLE_MS] without a touch.
 */
class CoverDim(private val service: AccessibilityService) {
    private val ctx = service.createDisplayContext(service.getSystemService(DisplayManager::class.java).getDisplay(COVER_DISPLAY))
        .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val dimRun = Runnable { dim() }

    private val black = View(ctx).apply {
        setBackgroundColor(Color.BLACK)
        setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) wake(); true }
    }

    // Hears every touch on the cover while it's awake (only that one happened, not where), to restart the idle timer.
    private val watcher = View(ctx).apply {
        setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) idleAgain(); false }
    }
    var dark = false; private set
    private var watching = false

    fun start() {
        runCatching {
            wm.addView(watcher, WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT))
            watching = true
        }
        dim()
    }

    fun stop() {
        main.removeCallbacks(dimRun)
        if (dark) runCatching { wm.removeView(black) }
        if (watching) runCatching { wm.removeView(watcher) }
        dark = false; watching = false
        (service as? dev.opencover.watcher.CoverAccessibility)?.updateNavBar()
    }

    private fun dim() {
        if (dark) return
        dark = runCatching {
            wm.addView(black, WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE).apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS })
        }.isSuccess
        (service as? dev.opencover.watcher.CoverAccessibility)?.updateNavBar()
    }

    private fun wake() {
        if (dark) runCatching { wm.removeView(black) }
        dark = false
        (service as? dev.opencover.watcher.CoverAccessibility)?.updateNavBar()
        idleAgain()
    }

    private fun idleAgain() {
        main.removeCallbacks(dimRun)
        main.postDelayed(dimRun, IDLE_MS)
    }

    private companion object {
        const val IDLE_MS = 30_000L
    }
}
