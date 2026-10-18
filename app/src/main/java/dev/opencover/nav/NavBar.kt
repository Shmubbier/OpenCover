package dev.opencover.nav

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import dev.opencover.COVER_DISPLAY
import dev.opencover.apps.AppLauncher
import dev.opencover.ui.CoverActivity

enum class NavButton { HOME, BACK }

/** OpenCover's bar shows only over Samsung's (apps that hide it keep none) and never on the lock screen. */
fun navBarShown(enabled: Boolean, samsungBar: Boolean, locked: Boolean): Boolean = enabled && samsungBar && !locked

// Samsung's cover bar, measured on the 748 px wide cover: Home x 64-166, Back x 183-285.
private const val HOME_L = 64f / 748; private const val HOME_R = 166f / 748
private const val BACK_L = 183f / 748; private const val BACK_R = 285f / 748

/** The button under a touch at [x] on a bar [width] px wide, or null. */
fun buttonAt(x: Float, width: Int): NavButton? {
    val f = x / width
    return when {
        f in HOME_L..HOME_R -> NavButton.HOME
        f in BACK_L..BACK_R -> NavButton.BACK
        else -> null
    }
}

object NavPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nav", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("enabled", on).apply()
}

/**
 * OpenCover's navigation bar: an accessibility overlay drawn exactly over Samsung's cover bar, same look, so Home goes
 * straight to OpenCover (Samsung's Home is handled inside System UI and shows its own cover home). Main thread only.
 */
object NavBar {
    private var view: View? = null
    private var bounds: Rect? = null

    /** Samsung's bar on the cover, from the window list, or null when it isn't showing. */
    fun samsungBar(service: AccessibilityService): Rect? = runCatching {
        service.windowsOnAllDisplays[COVER_DISPLAY]?.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.title?.toString() == "Navigation bar"
        }?.let { Rect().also(it::getBoundsInScreen) }
    }.getOrNull()

    /** Shows the bar over [over], moves it if Samsung's moved, or removes it when [over] is null. */
    fun update(service: AccessibilityService, over: Rect?) {
        if (over == null || over.isEmpty) return hide()
        if (view != null && over == bounds) return
        hide()
        val cover = service.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(COVER_DISPLAY) ?: return
        val wm = service.createDisplayContext(cover)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
        val v = BarView(service, service)
        val lp = WindowManager.LayoutParams(
            over.width(), over.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = over.left; y = over.top
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        runCatching { wm.addView(v, lp); view = v; bounds = Rect(over) }
    }

    fun hide() {
        val v = view ?: return
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
        view = null
        bounds = null
    }

    private fun home(service: AccessibilityService) {
        val intent = Intent(service, CoverActivity::class.java)
        // Already on Home: back to its home page. Otherwise no system animation; Home plays its own entrance.
        if (CoverActivity.current == null) intent.putExtra(CoverActivity.EXTRA_UNLOCKED, true)
        AppLauncher.start(service, intent, animate = false)
    }

    /** Samsung's bar, redrawn: light grey, a ring for Home and a chevron for Back at the same spots. */
    @SuppressLint("ViewConstructor")
    private class BarView(ctx: Context, private val service: AccessibilityService) : View(ctx) {
        private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.rgb(0x44, 0x44, 0x46); strokeCap = Paint.Cap.ROUND
        }
        private var down: NavButton? = null

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.rgb(0xC1, 0xC1, 0xC2))
            val cy = height / 2f
            val r = height * 0.22f
            c.drawRoundRect(width * (HOME_L + HOME_R) / 2 - r, cy - r, width * (HOME_L + HOME_R) / 2 + r, cy + r, r * 0.8f, r * 0.8f, icon)
            val bx = width * (BACK_L + BACK_R) / 2
            c.drawLine(bx + r * 0.5f, cy - r, bx - r * 0.5f, cy, icon)
            c.drawLine(bx - r * 0.5f, cy, bx + r * 0.5f, cy + r, icon)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> down = buttonAt(e.x, width)
                MotionEvent.ACTION_UP -> {
                    val b = buttonAt(e.x, width)
                    if (b != null && b == down) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        when (b) {
                            NavButton.HOME -> home(service)
                            NavButton.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        }
                    }
                    down = null
                }
                MotionEvent.ACTION_CANCEL -> down = null
            }
            return true
        }
    }
}
