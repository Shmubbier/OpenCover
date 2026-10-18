package dev.opencover.edge

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.core.graphics.drawable.toBitmap
import dev.opencover.COVER_DISPLAY
import dev.opencover.quick.ShizukuShell
import dev.opencover.watcher.CoverAccessibility
import kotlin.math.PI
import kotlin.math.sin

enum class Edge { NONE, GLOW, WAKE_GLOW_SLEEP }

/**
 * What a new notification does: glow on the lock screen, or wake the cover, glow and sleep again; nothing while the
 * phone is in use (cover on and unlocked), open, or in a call.
 */
fun edgeAction(
    enabled: Boolean, wake: Boolean, coverOn: Boolean, innerOn: Boolean, inCall: Boolean, canWake: Boolean, unlocked: Boolean,
): Edge = when {
    !enabled || innerOn || inCall -> Edge.NONE
    coverOn -> if (unlocked) Edge.NONE else Edge.GLOW
    wake && canWake -> Edge.WAKE_GLOW_SLEEP
    else -> Edge.NONE
}

const val GLOW_MS = 3000L

/** Brightness at [t] ms: two smooth pulses, dark at the start and the end. */
fun glowAlpha(t: Long): Float {
    val s = sin(2 * PI * t.coerceIn(0, GLOW_MS) / GLOW_MS).toFloat()
    return s * s
}

/** The average colour of the visible [pixels] (ARGB), or [fallback] when none are. */
fun averageColor(pixels: IntArray, fallback: Int): Int {
    var r = 0L; var g = 0L; var b = 0L; var n = 0L
    for (p in pixels) if (p ushr 24 >= 0x80) { r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF; n++ }
    if (n == 0L) return fallback
    return (0xFF shl 24) or ((((r + n / 2) / n).toInt()) shl 16) or ((((g + n / 2) / n).toInt()) shl 8) or (((b + n / 2) / n).toInt())
}

object EdgePrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("edge", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("enabled", on).apply()
    fun wake(ctx: Context) = prefs(ctx).getBoolean("wake", true)
    fun setWake(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("wake", on).apply()
    /** One glow colour for every app, or null for each app's own icon colour. */
    fun custom(ctx: Context): Int? = prefs(ctx).getString("custom", null)?.let(::parseHex)
    fun setCustom(ctx: Context, c: Int?) = prefs(ctx).edit().putString("custom", c?.let(::formatHex)).apply()
    fun perApp(ctx: Context): Map<String, Int> = parsePerApp(prefs(ctx).getString("per_app", null))
    fun setPerApp(ctx: Context, m: Map<String, Int>) = prefs(ctx).edit().putString("per_app", formatPerApp(m)).apply()
}

/**
 * Edge lighting: the cover's edges glow in the app's colour when a notification arrives. With the cover off or on the
 * AOD it wakes the cover through Shizuku, glows, and puts it back to sleep unless it was touched meanwhile.
 * Main thread only.
 */
object EdgeLight {
    private val handler = Handler(Looper.getMainLooper())
    private const val BLUE = 0xFF8AB4F8.toInt()
    private var view: GlowView? = null

    /** Last touch on OpenCover's screens; a touch during the glow keeps the cover awake. */
    @Volatile var lastTouch = 0L

    fun notified(ctx: Context, pkg: String) {
        val service = CoverAccessibility.instance ?: return // the glow is an accessibility overlay
        val displays = ctx.getSystemService(DisplayManager::class.java)
        val audio = ctx.getSystemService(AudioManager::class.java).mode
        val action = edgeAction(
            EdgePrefs.enabled(ctx), EdgePrefs.wake(ctx),
            coverOn = displays.getDisplay(COVER_DISPLAY)?.state == Display.STATE_ON,
            innerOn = displays.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON,
            inCall = audio == AudioManager.MODE_RINGTONE || audio == AudioManager.MODE_IN_CALL || audio == AudioManager.MODE_IN_COMMUNICATION,
            canWake = ShizukuShell.allowed(),
            unlocked = !ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
        )
        val color = glowColor(pkg, EdgePrefs.perApp(ctx), EdgePrefs.custom(ctx)) { appColor(ctx, pkg) }
        when (action) {
            Edge.NONE -> {}
            Edge.GLOW -> glow(service, color, sleepAfter = false)
            Edge.WAKE_GLOW_SLEEP -> Thread {
                if (ShizukuShell.run("input", "keyevent", "KEYCODE_WAKEUP")) handler.postDelayed({ glow(service, color, sleepAfter = true) }, 350)
            }.start()
        }
    }

    /** Settings: shows a glow in [color] on the cover now. */
    fun preview(color: Int) { CoverAccessibility.instance?.let { glow(it, color, sleepAfter = false) } }

    fun appColor(ctx: Context, pkg: String): Int = runCatching {
        val bmp = ctx.packageManager.getApplicationIcon(pkg).toBitmap(24, 24)
        val avg = IntArray(24 * 24).also { bmp.getPixels(it, 0, 24, 0, 0, 24, 24) }.let { averageColor(it, BLUE) }
        // An average drifts towards grey: keep the hue, make it vivid and bright.
        val hsv = FloatArray(3).also { Color.colorToHSV(avg, it) }
        hsv[1] = hsv[1].coerceAtLeast(0.6f); hsv[2] = 1f
        Color.HSVToColor(hsv)
    }.getOrDefault(BLUE)

    private fun glow(service: AccessibilityService, color: Int, sleepAfter: Boolean) {
        hide()
        val cover = service.getSystemService(DisplayManager::class.java).getDisplay(COVER_DISPLAY) ?: return
        val wm = service.createDisplayContext(cover)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
        val v = GlowView(service, color)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (runCatching { wm.addView(v, lp) }.isFailure) return
        view = v
        val started = SystemClock.uptimeMillis()
        ValueAnimator.ofFloat(0f, GLOW_MS.toFloat()).apply {
            duration = GLOW_MS
            interpolator = LinearInterpolator()
            addUpdateListener { v.level = glowAlpha((it.animatedValue as Float).toLong()); v.invalidate() }
            doOnEnd {
                if (view === v) hide()
                // Back to sleep only if we woke it, it's still locked and nobody touched it meanwhile.
                val locked = service.getSystemService(KeyguardManager::class.java).isKeyguardLocked
                if (sleepAfter && locked && lastTouch < started) Thread { ShizukuShell.run("input", "keyevent", "KEYCODE_SLEEP") }.start()
            }
            start()
        }
    }

    fun hide() {
        val v = view ?: return
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
        view = null
    }

    private fun ValueAnimator.doOnEnd(block: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = block()
    })

    /** A soft glow along the cover's rounded edges. */
    @SuppressLint("ViewConstructor")
    private class GlowView(ctx: Context, color: Int) : View(ctx) {
        var level = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 16f
            this.color = color
            maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
        }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) } // BlurMaskFilter needs a software layer

        override fun onDraw(c: Canvas) {
            paint.alpha = (255 * level).toInt()
            val inset = paint.strokeWidth / 2
            c.drawRoundRect(inset, inset, width - inset, height - inset, 49f, 49f, paint) // the cover's corner radius
        }
    }
}
