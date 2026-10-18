package dev.opencover.nav

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import dev.opencover.COVER_DISPLAY
import dev.opencover.apps.AppLauncher
import dev.opencover.ui.CoverActivity
import kotlin.math.abs

enum class NavButton { HOME, BACK, RECENTS }

/** OpenCover's bar shows only over Samsung's (apps that hide it keep none) and never on the lock screen. */
fun navBarShown(enabled: Boolean, samsungBar: Boolean, locked: Boolean): Boolean = enabled && samsungBar && !locked

// Samsung's cover bar, measured on the 748 px wide cover: Home x 64-166, Back x 183-285. Recents follows at the same pitch.
private const val HOME_L = 64f / 748; private const val HOME_R = 166f / 748
private const val BACK_L = 183f / 748; private const val BACK_R = 285f / 748
private const val RECENTS_L = 302f / 748; private const val RECENTS_R = 404f / 748

/** The button under a touch at [x] on a bar [width] px wide, or null. */
fun buttonAt(x: Float, width: Int): NavButton? {
    val f = x / width
    return when {
        f in HOME_L..HOME_R -> NavButton.HOME
        f in BACK_L..BACK_R -> NavButton.BACK
        f in RECENTS_L..RECENTS_R -> NavButton.RECENTS
        else -> null
    }
}

private const val SWIPE_UP_PX = 40f
private const val HOLD_MS = 300L
private const val EDGE_BACK_PX = 60f

/** A gesture on the bar that moved [dy] up (negative) and [dx] sideways, held still at the top for [heldMs]. */
fun barGesture(dy: Float, dx: Float, heldMs: Long): NavButton? = when {
    -dy < SWIPE_UP_PX || abs(dx) > -dy -> null // too short, or more sideways than up
    heldMs >= HOLD_MS -> NavButton.RECENTS
    else -> NavButton.HOME
}

/** A swipe from a screen edge that came [inward] px in: far enough means Back. */
fun edgeBack(inward: Float): Boolean = inward >= EDGE_BACK_PX

enum class NavStyle { BUTTONS, GESTURES }

object NavPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nav", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("enabled", on).apply()
    fun style(ctx: Context) = NavStyle.entries.find { it.name == prefs(ctx).getString("style", null) } ?: NavStyle.BUTTONS
    fun setStyle(ctx: Context, s: NavStyle) = prefs(ctx).edit().putString("style", s.name).apply()
}

/**
 * OpenCover's navigation: a bar drawn exactly over Samsung's cover bar (Samsung's Home is handled inside System UI and
 * shows its own cover home), as buttons or gestures; with gestures, thin strips on both screen edges for Back.
 * Main thread only.
 */
object NavBar {
    private val views = mutableListOf<View>()
    private var bounds: Rect? = null
    private var style: NavStyle? = null

    /** Samsung's bar on the cover, from the window list, or null when it isn't showing. */
    fun samsungBar(service: AccessibilityService): Rect? = runCatching {
        service.windowsOnAllDisplays[COVER_DISPLAY]?.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.title?.toString() == "Navigation bar"
        }?.let { Rect().also(it::getBoundsInScreen) }
    }.getOrNull()

    /** Shows the bar over [over], redraws it if Samsung's moved or the style changed, or removes it when [over] is null. */
    fun update(service: AccessibilityService, over: Rect?) {
        if (over == null || over.isEmpty) return hide()
        val s = NavPrefs.style(service)
        if (views.isNotEmpty() && over == bounds && s == style) return
        hide()
        val cover = service.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(COVER_DISPLAY) ?: return
        val wm = service.createDisplayContext(cover)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
        add(wm, BarView(service, service, s), over.left, over.top, over.width(), over.height())
        if (s == NavStyle.GESTURES) {
            // Back strips on both edges, between the top quarter (Samsung's panel pull-down) and the bar.
            val edge = 30 // px on the 748 px cover, about 14 dp
            val top = over.top / 4
            add(wm, EdgeView(service, service, fromLeft = true), 0, top, edge, over.top - top)
            add(wm, EdgeView(service, service, fromLeft = false), over.right - edge, top, edge, over.top - top)
        }
        bounds = Rect(over)
        style = s
    }

    private fun add(wm: WindowManager, v: View, x: Int, y: Int, w: Int, h: Int) {
        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x; this.y = y
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        runCatching { wm.addView(v, lp); views += v }
    }

    fun hide() {
        views.forEach { v -> runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) } }
        views.clear()
        bounds = null
        style = null
    }

    private fun act(service: AccessibilityService, b: NavButton) {
        when (b) {
            NavButton.HOME -> {
                val intent = Intent(service, CoverActivity::class.java)
                // Already on Home: back to its home page. Otherwise no system animation; Home plays its own entrance.
                if (CoverActivity.current == null) intent.putExtra(CoverActivity.EXTRA_UNLOCKED, true)
                AppLauncher.start(service, intent, animate = false)
            }
            NavButton.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            // One UI's own recents screen runs on the cover.
            NavButton.RECENTS -> runCatching {
                AppLauncher.start(service, Intent().setComponent(
                    ComponentName("com.sec.android.app.launcher", "com.android.quickstep.RecentsActivity"),
                ))
            }
        }
    }

    /** Samsung's bar, redrawn: buttons (ring, chevron, three bars) at its spots, or a pill for gestures. */
    @SuppressLint("ViewConstructor")
    private class BarView(ctx: Context, private val service: AccessibilityService, private val style: NavStyle) : View(ctx) {
        private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.rgb(0x44, 0x44, 0x46); strokeCap = Paint.Cap.ROUND
        }
        private var down: NavButton? = null
        private var startX = 0f
        private var startY = 0f
        private var topY = 0f
        private var stillSince = 0L
        private var fired = false

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.rgb(0xC1, 0xC1, 0xC2))
            val cy = height / 2f
            val r = height * 0.22f
            if (style == NavStyle.GESTURES) {
                val half = width * 0.12f
                c.drawLine(width / 2f - half, cy, width / 2f + half, cy, icon.apply { strokeWidth = 8f })
                icon.strokeWidth = 4f
                return
            }
            val hx = width * (HOME_L + HOME_R) / 2
            c.drawRoundRect(hx - r, cy - r, hx + r, cy + r, r * 0.8f, r * 0.8f, icon)
            val bx = width * (BACK_L + BACK_R) / 2
            c.drawLine(bx + r * 0.5f, cy - r, bx - r * 0.5f, cy, icon)
            c.drawLine(bx - r * 0.5f, cy, bx + r * 0.5f, cy + r, icon)
            val rx = width * (RECENTS_L + RECENTS_R) / 2
            for (i in -1..1) c.drawLine(rx + i * r * 0.7f, cy - r * 0.8f, rx + i * r * 0.7f, cy + r * 0.8f, icon)
        }

        private fun fire(b: NavButton) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            act(service, b)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (style == NavStyle.BUTTONS) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> down = buttonAt(e.x, width)
                    MotionEvent.ACTION_UP -> { buttonAt(e.x, width)?.takeIf { it == down }?.let(::fire); down = null }
                    MotionEvent.ACTION_CANCEL -> down = null
                }
                return true
            }
            // Gestures: raw coordinates, since the finger leaves this short window while swiping up.
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY; topY = e.rawY; stillSince = SystemClock.uptimeMillis(); fired = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.rawY < topY - 8) { topY = e.rawY; stillSince = SystemClock.uptimeMillis() } // still climbing
                    val held = SystemClock.uptimeMillis() - stillSince
                    if (!fired && barGesture(e.rawY - startY, e.rawX - startX, held) == NavButton.RECENTS) { fired = true; fire(NavButton.RECENTS) }
                }
                MotionEvent.ACTION_UP -> if (!fired) barGesture(e.rawY - startY, e.rawX - startX, 0)?.let(::fire)
            }
            return true
        }
    }

    /** A thin strip on a screen edge: swipe inward far enough for Back. */
    @SuppressLint("ViewConstructor")
    private class EdgeView(ctx: Context, private val service: AccessibilityService, private val fromLeft: Boolean) : View(ctx) {
        private var startX = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> startX = e.rawX
                MotionEvent.ACTION_UP -> {
                    val inward = if (fromLeft) e.rawX - startX else startX - e.rawX
                    if (edgeBack(inward)) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        act(service, NavButton.BACK)
                    }
                }
            }
            return true
        }
    }
}
