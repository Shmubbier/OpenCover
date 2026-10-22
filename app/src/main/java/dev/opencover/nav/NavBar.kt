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
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.ui.graphics.asAndroidBitmap
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import dev.opencover.COVER_DISPLAY
import dev.opencover.apps.AppLauncher
import dev.opencover.quick.ShizukuShell
import dev.opencover.ui.CoverActivity
import kotlin.math.abs

enum class NavButton { HOME, BACK, RECENTS }

/** OpenCover's bar shows only over Samsung's (apps that hide it keep none) and never on the lock screen. */
fun navBarShown(enabled: Boolean, samsungBar: Boolean, locked: Boolean): Boolean = enabled && samsungBar && !locked

// Recents | Home | Back in three equal slots left of the camera lip (the cutout starts at x 379 of the 748 px cover).
private const val RECENTS_L = 4f / 748; private const val RECENTS_R = 118f / 748
private const val HOME_L = 128f / 748; private const val HOME_R = 242f / 748
private const val BACK_L = 252f / 748; private const val BACK_R = 366f / 748
private const val LIP_CENTER = 185f / 748 // middle of the part left of the lip, for the gesture pill

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

/**
 * The bar is always drawn and read in the cover's natural (upright) frame, so it looks and works the same whatever way
 * the cover is turned. [rotation] is the cover's (Surface.ROTATION_*, 0..3). A move of [dx],[dy] on screen, in the
 * natural frame (dy < 0 is towards the cover's top).
 */
fun toNatural(dx: Float, dy: Float, rotation: Int): Pair<Float, Float> = when (rotation and 3) {
    1 -> -dy to dx
    2 -> -dx to -dy
    3 -> dy to -dx
    else -> dx to dy
}

/** A point at [x],[y] in a bar view [w]x[h] as (along the bar from the cover's left, across it from its inner edge). */
fun barPoint(x: Float, y: Float, w: Int, h: Int, rotation: Int): Pair<Float, Float> = when (rotation and 3) {
    1 -> h - y to x
    2 -> w - x to h - y
    3 -> y to w - x
    else -> x to y
}

/** A rectangle in the natural frame of a [natW]x[natH] cover, as left, top, right, bottom on screen at [rotation]. */
fun naturalToScreen(l: Int, t: Int, r: Int, b: Int, natW: Int, natH: Int, rotation: Int): IntArray {
    fun map(x: Int, y: Int): Pair<Int, Int> = when (rotation and 3) {
        1 -> y to natW - x
        2 -> natW - x to natH - y
        3 -> natH - y to x
        else -> x to y
    }
    val (x1, y1) = map(l, t); val (x2, y2) = map(r, b)
    return intArrayOf(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
}

/** The edge a bar at [r] lies on, on a [w]x[h] screen, as quarter turns from the bottom: 0 bottom, 1 right, 2 top, 3 left. */
fun barSide(r: Rect, w: Int, h: Int): Int = barSide(r.left, r.top, r.right, r.bottom, w, h)

fun barSide(l: Int, t: Int, rt: Int, b: Int, w: Int, h: Int): Int = when {
    rt - l >= b - t -> if (b >= h) 0 else 2
    else -> if (rt >= w) 1 else 3
}

enum class NavStyle { BUTTONS, GESTURES }

private const val RECENTS_PACKAGE = "com.sec.android.app.launcher" // on the cover it only ever shows recents
private const val RECENTS_COMPONENT = "$RECENTS_PACKAGE/com.android.quickstep.RecentsActivity"

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
    private var shownFor: Pair<Int, Boolean>? = null // rotation, keyboard up

    private var lastBar: Rect? = null

    /**
     * Where OpenCover's bar goes: over Samsung's bar, or, while One UI's recents is up (Samsung hides its bar there and
     * leaves only its own way home, through Samsung's cover home), where Samsung's bar was. Null: no bar.
     */
    fun samsungBar(service: AccessibilityService): Rect? = runCatching {
        val windows = service.windowsOnAllDisplays[COVER_DISPLAY].orEmpty()
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.title?.toString() == "Navigation bar" }
            ?.let { Rect().also(it::getBoundsInScreen) }?.also { lastBar = it }
            // With Samsung's swipe gestures the bar can't be touched, so it isn't listed: use where the system says it is.
            ?: insetBar(service)?.also { lastBar = it }
            ?: lastBar?.takeIf {
                windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root?.packageName == RECENTS_PACKAGE
            }
    }.getOrNull()

    private var metrics: Context? = null

    /** The cover's navigation bar from its insets (the strip they reserve), or null when there is none. */
    private fun insetBar(service: AccessibilityService): Rect? {
        val ctx = metrics ?: run {
            val cover = service.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(COVER_DISPLAY) ?: return null
            service.createDisplayContext(cover).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null).also { metrics = it }
        }
        val m = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics
        val i = m.windowInsets.getInsets(android.view.WindowInsets.Type.navigationBars())
        val w = m.bounds.width(); val h = m.bounds.height()
        return when {
            i.bottom > 0 -> Rect(0, h - i.bottom, w, h)
            i.right > 0 -> Rect(w - i.right, 0, w, h)
            i.left > 0 -> Rect(0, 0, i.left, h)
            i.top > 0 -> Rect(0, 0, w, i.top)
            else -> null
        }
    }

    /**
     * Shows the bar over [over], redraws it if Samsung's moved, the style or rotation changed or a keyboard came or went
     * ([keyboard]: it then offers a hide-keyboard button), or removes it when [over] is null.
     */
    fun update(service: AccessibilityService, over: Rect?, keyboard: Boolean = false) {
        if (over == null || over.isEmpty) return hide()
        val s = NavPrefs.style(service)
        val cover = service.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(COVER_DISPLAY) ?: return
        val size = android.graphics.Point().also { @Suppress("DEPRECATION") cover.getRealSize(it) }
        // Which screen edge the bar is on, as a turn from the bottom: everything is drawn and read as if it were at the
        // bottom. Samsung's buttons sit on the cover's physical bottom (so they turn with it); its gesture bar stays
        // on the screen's bottom.
        var rot = barSide(over, size.x, size.y)
        var over = over
        // Turned, with the system bar not on the camera-lip edge (Samsung's gestures): a black spacer along that edge
        // instead, holding the bar where it is upright, so nothing important sits behind the lip.
        val spacer = cover.rotation != 0 && rot != cover.rotation
        if (spacer) {
            val (natW, natH) = if (cover.rotation % 2 == 1) size.y to size.x else size.x to size.y
            val thick = 66 // the camera lip's height on the 720 px cover (Samsung's upright bar is the same)
            val (l, t, r, b) = naturalToScreen(0, natH - thick, natW, natH, natW, natH, cover.rotation)
            over = Rect(l, t, r, b)
            rot = cover.rotation
        }
        if (views.isNotEmpty() && over == bounds && s == style && shownFor == (rot to keyboard)) return
        hide()
        val wm = service.createDisplayContext(cover)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
        // On the physical bottom the camera lip takes the right part, so the pill sits left of it; elsewhere, centred.
        add(wm, BarView(service, service, s, rot, keyboard, lip = rot == cover.rotation, spacer), over.left, over.top, over.width(), over.height())
        if (s == NavStyle.GESTURES) {
            // Back strips on the two edges beside the bar, between the top quarter (Samsung's panel pull-down) and the
            // bar, worked out as if the bar were at the bottom and then turned.
            val (natW, natH) = if (rot % 2 == 1) size.y to size.x else size.x to size.y
            val barTop = natH - minOf(over.width(), over.height())
            val edge = 30 // px on the 748 px cover, about 14 dp
            val top = barTop / 4
            for (left in listOf(true, false)) {
                val (l, t, r, b) = naturalToScreen(if (left) 0 else natW - edge, top, if (left) edge else natW, barTop, natW, natH, rot)
                add(wm, EdgeView(service, service, fromLeft = left, rot), l, t, r - l, b - t)
            }
        }
        bounds = Rect(over)
        style = s
        shownFor = rot to keyboard
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
        shownFor = null
    }

    /** Samsung's own swipe gestures are on (Settings > Display > Navigation bar). */
    private fun View.samsungGestures(): Boolean =
        android.provider.Settings.Global.getInt(context.contentResolver, "navigation_bar_gesture_while_hidden", 0) == 1

    private var curtain: View? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val dropLater = Runnable { dropCurtain() }

    /** Covers the cover screen with the Home wallpaper until OpenCover's Home is in front ([dropCurtainSoon]). */
    private fun raiseCurtain(service: AccessibilityService) {
        if (curtain != null) return
        val cover = service.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(COVER_DISPLAY) ?: return
        val wm = service.createDisplayContext(cover).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
        val v = CurtainView(service)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (runCatching { wm.addView(v, lp) }.isSuccess) curtain = v
        main.removeCallbacks(dropLater)
        main.postDelayed(dropLater, 2000) // never left up, whatever happens
    }

    /** A curtain is up: Home skips its own fade-in, since the wallpaper already shows. */
    val curtainUp get() = curtain != null

    /**
     * The cover's windows changed: once OpenCover is the app on top (Samsung's cover home is gone), fade the curtain
     * out. Resuming isn't enough: Samsung's home stays on screen for a moment after ours resumes.
     */
    fun dropCurtainIfHome(service: AccessibilityService) {
        if (curtain == null) return
        val top = runCatching {
            service.windowsOnAllDisplays[COVER_DISPLAY].orEmpty()
                .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.title?.toString()
        }.getOrNull()
        if (top == "OpenCover" && CoverActivity.current != null) dropCurtain(fade = true)
    }

    private fun dropCurtain(fade: Boolean = false) {
        val v = curtain ?: return
        curtain = null
        main.removeCallbacks(dropLater)
        val remove = { runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }; Unit }
        if (fade) v.animate().alpha(0f).setDuration(150).withEndAction(remove).start() else remove()
    }

    /** The Home wallpaper's still, centre-cropped, as Home shows it. */
    @SuppressLint("ViewConstructor")
    private class CurtainView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        override fun onDraw(c: Canvas) {
            c.drawColor(Color.BLACK)
            val bmp = dev.opencover.home.Wallpaper.homeStill?.asAndroidBitmap() ?: return
            val s = maxOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
            c.translate((width - bmp.width * s) / 2, (height - bmp.height * s) / 2)
            c.scale(s, s)
            c.drawBitmap(bmp, 0f, 0f, paint)
        }
    }

    private fun hideKeyboard(service: AccessibilityService) {
        (service as? dev.opencover.watcher.CoverAccessibility)?.hideKeyboard() ?: service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun act(service: AccessibilityService, b: NavButton) {
        when (b) {
            NavButton.HOME -> {
                val intent = Intent(service, CoverActivity::class.java)
                // Already on Home: back to its home page. Otherwise no system animation; Home plays its own entrance.
                if (CoverActivity.current == null && !curtainUp) intent.putExtra(CoverActivity.EXTRA_UNLOCKED, true)
                AppLauncher.start(service, intent, animate = false)
            }
            NavButton.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            // One UI's own recents runs on the cover, but Samsung answers an app's launch with "Please open phone";
            // the same launch from the shell works (FreeFlip, Cover Recents). Without Shizuku: Android's recents action.
            NavButton.RECENTS -> Thread {
                // Without "recent.displayId 0" Samsung lists only the cover's own tasks ("No recently used apps").
                val ok = ShizukuShell.run(
                    "am", "start", "--display", "$COVER_DISPLAY", "-f", "0x10008000", "-n", RECENTS_COMPONENT,
                    "--ei", "com.samsung.android.activity.displayid", "0",
                    "--ei", "com.samsung.android.activity.recent.displayId", "0",
                    "--ez", "com.android.systemui.recents.extra.RESET_RECENTS", "true",
                    "--ez", "com.android.launcher3.intent.extra.FORCE_OVERVIEW", "true",
                )
                // Without Shizuku: the same start from the accessibility service (Samsung's launcher is on the cover list).
                if (!ok) service.mainExecutor.execute {
                    runCatching {
                        AppLauncher.startApp(service, Intent().setComponent(android.content.ComponentName.unflattenFromString(RECENTS_COMPONENT))
                            .putExtra("com.samsung.android.activity.displayid", 0)
                            .putExtra("com.samsung.android.activity.recent.displayId", 0)
                            .putExtra("com.android.systemui.recents.extra.RESET_RECENTS", true)
                            .putExtra("com.android.launcher3.intent.extra.FORCE_OVERVIEW", true)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    }.onFailure { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) }
                }
            }.start()
        }
    }

    /**
     * Samsung's bar, redrawn: buttons (ring, chevron, three bars) at its spots, or a pill for gestures; with a keyboard
     * up, Back (or, with gestures, a button beside the pill) is a down chevron that hides it. Drawn and read upright.
     */
    @SuppressLint("ViewConstructor")
    private class BarView(
        ctx: Context, private val service: AccessibilityService, private val style: NavStyle,
        private val rotation: Int, private val keyboard: Boolean, private val lip: Boolean, private val spacer: Boolean = false,
    ) : View(ctx) {
        /** Where the pill and, with a keyboard up, the hide button sit along the bar (fractions of its length). */
        private val pillCenter = if (lip) LIP_CENTER else 0.5f
        private val chevronAt = if (lip) (BACK_L + BACK_R) / 2 + 0.03f else 0.5f + 0.2f
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.rgb(0x44, 0x44, 0x46); strokeCap = Paint.Cap.ROUND
        }
        private var down: NavButton? = null
        private var startX = 0f
        private var startY = 0f
        private var topY = 0f
        private var stillSince = 0L
        private var fired = false

        private val pressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x40, 0, 0, 0) }

        /** The bar's length along the cover's bottom edge and its thickness, whatever the rotation. */
        private val long get() = if (rotation % 2 == 1) height else width
        private val thick get() = if (rotation % 2 == 1) width else height

        override fun onDraw(c: Canvas) {
            // Buttons sit on Samsung's grey bar; the gesture pill floats over the app with no background.
            if (spacer) c.drawColor(Color.BLACK) // blends with the lip and the cover's black frame
            else if (style == NavStyle.BUTTONS) c.drawColor(Color.rgb(0xC1, 0xC1, 0xC2))
            // Turn the canvas so everything below is drawn upright, as at rotation 0.
            when (rotation and 3) {
                1 -> { c.translate(0f, height.toFloat()); c.rotate(-90f) }
                2 -> { c.translate(width.toFloat(), height.toFloat()); c.rotate(180f) }
                3 -> { c.translate(width.toFloat(), 0f); c.rotate(90f) }
            }
            val width = long
            val height = thick
            val cy = height / 2f
            val r = height * 0.22f
            // The button under the finger darkens, like Samsung's ripple.
            down?.let { b ->
                val (l, rr) = when (b) { NavButton.RECENTS -> RECENTS_L to RECENTS_R; NavButton.HOME -> HOME_L to HOME_R; NavButton.BACK -> BACK_L to BACK_R }
                c.drawCircle(width * (l + rr) / 2, cy, height * 0.45f, pressed)
            }
            val bx = width * (BACK_L + BACK_R) / 2
            if (style == NavStyle.GESTURES) {
                // A light pill with a faint dark edge, so it shows on light and dark apps alike.
                val half = width * 0.12f
                val px = width * pillCenter
                // Where Samsung draws its own gesture handle (about 10 px from the outer edge), so this one covers it.
                val cy = maxOf(height / 2f, height - 10f)
                c.drawLine(px - half, cy, px + half, cy, pill.apply { strokeWidth = 12f; color = Color.argb(0x50, 0, 0, 0) })
                c.drawLine(px - half, cy, px + half, cy, pill.apply { strokeWidth = 8f; color = Color.argb(0xF0, 0xE6, 0xE6, 0xE8) })
                if (keyboard) {
                    val x = width * chevronAt
                    icon.color = Color.argb(0x50, 0, 0, 0); icon.strokeWidth = 8f; hideChevron(c, x, cy, r)
                    icon.color = Color.argb(0xF0, 0xE6, 0xE6, 0xE8); icon.strokeWidth = 4f; hideChevron(c, x, cy, r)
                    icon.color = Color.rgb(0x44, 0x44, 0x46)
                }
                return
            }
            val hx = width * (HOME_L + HOME_R) / 2
            c.drawRoundRect(hx - r, cy - r, hx + r, cy + r, r * 0.8f, r * 0.8f, icon)
            if (keyboard) hideChevron(c, bx, cy, r) else {
                c.drawLine(bx + r * 0.5f, cy - r, bx - r * 0.5f, cy, icon)
                c.drawLine(bx - r * 0.5f, cy, bx + r * 0.5f, cy + r, icon)
            }
            val rx = width * (RECENTS_L + RECENTS_R) / 2
            for (i in -1..1) c.drawLine(rx + i * r * 0.7f, cy - r * 0.8f, rx + i * r * 0.7f, cy + r * 0.8f, icon)
        }

        /** A down chevron: "hide the keyboard". */
        private fun hideChevron(c: Canvas, x: Float, cy: Float, r: Float) {
            c.drawLine(x - r, cy - r * 0.4f, x, cy + r * 0.4f, icon)
            c.drawLine(x, cy + r * 0.4f, x + r, cy - r * 0.4f, icon)
        }

        private fun fire(b: NavButton) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (b == NavButton.BACK && keyboard) hideKeyboard(service) else act(service, b)
        }

        /** The button under a touch, read upright. */
        private fun buttonUnder(e: MotionEvent): NavButton? = buttonAt(barPoint(e.x, e.y, width, height, rotation).first, long)

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (style == NavStyle.BUTTONS) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> down = buttonUnder(e)
                    MotionEvent.ACTION_UP -> { buttonUnder(e)?.takeIf { it == down }?.let(::fire); down = null }
                    MotionEvent.ACTION_CANCEL -> down = null
                }
                invalidate() // show or clear the pressed highlight
                return true
            }
            // Gestures: raw coordinates, since the finger leaves this short window while swiping up; turned upright.
            val (dx, dy) = toNatural(e.rawX - startX, e.rawY - startY, rotation)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY; topY = 0f; stillSince = SystemClock.uptimeMillis(); fired = false
                    onChevron = abs(barPoint(e.x, e.y, width, height, rotation).first / long - chevronAt) < 0.08f
                    // Long-press for Recents: with Samsung's swipe gestures on, the system takes any swipe up from
                    // the bottom edge before it gets here, but a finger held still stays ours.
                    if (!onChevron || !keyboard) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(dx) > 20 || abs(dy) > 20) removeCallbacks(longPress)
                    // Samsung's swipe gestures take this swipe in a moment and briefly show Samsung's cover home on the
                    // way: go Home now, behind a curtain of the wallpaper that hides Samsung's home until ours is up.
                    if (!fired && CoverActivity.current == null && samsungGestures() && -dy > 16 && abs(dx) < -dy) {
                        fired = true
                        removeCallbacks(longPress)
                        raiseCurtain(service)
                        fire(NavButton.HOME)
                    }
                    if (dy < topY - 8) { topY = dy; stillSince = SystemClock.uptimeMillis() } // still climbing
                    val held = SystemClock.uptimeMillis() - stillSince
                    if (!fired && barGesture(dy, dx, held) == NavButton.RECENTS) { fired = true; fire(NavButton.RECENTS) }
                }
                MotionEvent.ACTION_UP -> if (!fired) {
                    val g = barGesture(dy, dx, 0)
                    // A tap on the chevron beside the pill hides the keyboard.
                    if (g == null && keyboard && onChevron && abs(dx) < 20 && abs(dy) < 20) fire(NavButton.BACK)
                    else g?.let(::fire)
                }
            }
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) removeCallbacks(longPress)
            return true
        }

        private val longPress = Runnable { if (!fired) { fired = true; fire(NavButton.RECENTS) } }
        private var onChevron = false
    }

    /** A thin strip on a screen edge: swipe inward far enough for Back. */
    @SuppressLint("ViewConstructor")
    private class EdgeView(ctx: Context, private val service: AccessibilityService, private val fromLeft: Boolean, private val rotation: Int) : View(ctx) {
        private var startX = 0f
        private var startY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = e.rawX; startY = e.rawY }
                MotionEvent.ACTION_UP -> {
                    val dx = toNatural(e.rawX - startX, e.rawY - startY, rotation).first // towards the cover's right
                    val inward = if (fromLeft) dx else -dx
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
