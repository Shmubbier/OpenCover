package dev.opencover.lock

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.opencover.COVER_DISPLAY
import dev.opencover.ui.LockFace
import kotlinx.coroutines.delay

/** Samsung's AOD keeps the cover in doze (so there is something to draw on) only when "Always" without tap to show. */
fun aodReady(aodMode: Int, tapToShow: Int): Boolean = aodMode == 1 && tapToShow == 0

fun aodReady(ctx: Context): Boolean = aodReady(
    Settings.System.getInt(ctx.contentResolver, "aod_mode", 0),
    Settings.System.getInt(ctx.contentResolver, "aod_tap_to_show_mode", 1),
)

/** The phone is asleep with the cover showing Samsung's AOD (so OpenCover's can be drawn over it). */
fun dozing(interactive: Boolean, coverState: Int): Boolean =
    !interactive && (coverState == android.view.Display.STATE_DOZE || coverState == android.view.Display.STATE_DOZE_SUSPEND)

/** Offset (dp) for minute [minute]: a small square walk, so the AOD never sits still long enough to burn in. */
fun burnIn(minute: Long): Pair<Int, Int> = when ((minute % 4).toInt()) {
    0 -> 0 to 0
    1 -> 4 to 0
    2 -> 4 to 4
    else -> 0 to 4
}

private const val DP_SCALE = 0.8f // icons and spacing
private const val SP_SCALE = 340f / 480f // text

/**
 * The density and font scale OpenCover's cover activity (Home, the lock screen) actually draws at. Samsung runs cover
 * activities in a compat config that differs from this overlay's, and both move when the cover density is changed, so
 * the activity records its own and the AOD draws with exactly those: its modules then match the lock screen's.
 */
object CoverScale {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("lock", Context.MODE_PRIVATE)

    /**
     * Samsung draws cover activities in a bigger space (the inner screen's density, 1282x1234 px) and shrinks the
     * window onto the 748x720 cover; the density as seen on the cover is the activity's times that shrink.
     */
    fun record(ctx: android.app.Activity) {
        val m = ctx.resources.displayMetrics
        val mode = ctx.display?.mode ?: return
        val shrink = maxOf(mode.physicalWidth, mode.physicalHeight).toFloat() / maxOf(m.widthPixels, m.heightPixels)
        prefs(ctx).edit()
            .putFloat("cover_density", m.density * shrink.coerceAtMost(1f))
            .putFloat("cover_font_scale", ctx.resources.configuration.fontScale).apply()
    }

    /** null until the activity has run once. */
    fun density(ctx: Context): Density? {
        val p = prefs(ctx)
        val d = p.getFloat("cover_density", 0f).takeIf { it > 0f } ?: return null
        return Density(d, p.getFloat("cover_font_scale", 1f))
    }
}

/** The AOD: OpenCover's face in an accessibility overlay on the cover while the phone dozes. Main thread only. */
object AodOverlay {
    private var view: View? = null
    private var owner: Owner? = null

    fun show(service: AccessibilityService) {
        if (view != null) return
        val cover = service.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(COVER_DISPLAY) ?: return
        // The cover's own context: its density (340 dpi), not the inner screen's, so the face matches the lock screen.
        val wctx = service.createDisplayContext(cover).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val wm = wctx.getSystemService(WindowManager::class.java)
        val o = Owner().also { owner = it }
        val v = ComposeView(wctx).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                var minute by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000) }
                LaunchedEffect(Unit) { while (true) { delay(60_000L - System.currentTimeMillis() % 60_000L); minute++ } }
                val (dx, dy) = burnIn(minute)
                // ponytail: activities on the cover (Home, the lock screen) run in Samsung's compat config and come out smaller
                // than this native 340 dpi window: 0.8x for dp, 0.71x for text (measured on the Flip 5). Recheck on other covers.
                val d = LocalDensity.current
                val same = CoverScale.density(service) ?: Density(d.density * DP_SCALE, d.fontScale * SP_SCALE / DP_SCALE)
                CompositionLocalProvider(LocalDensity provides same) {
                    LockFace(LockPrefs.layout, dim = true, locked = false, modifier = Modifier.offset(dx.dp, dy.dp))
                }
            }
        }
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
        runCatching { wm.addView(v, lp); view = v; o.resume() }.onFailure { owner = null }
    }

    fun hide() {
        val v = view ?: return
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
        owner?.destroy()
        view = null
        owner = null
    }

    /** Compose in a service window needs a lifecycle and saved-state owner of its own. */
    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this).apply { performRestore(null as Bundle?) }
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }
}

/**
 * AOD timeout: after [LockPrefs.aodMinutes] of AOD the cover goes fully dark, by turning Samsung's AOD off through
 * Shizuku; the next wake turns it back on. Saves the ambient display's power (about 14 mAh an hour on this phone).
 */
object AodTimeout {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** The AOD came on. */
    fun start(ctx: Context) {
        handler.removeCallbacksAndMessages(null)
        val m = LockPrefs.aodMinutes
        if (m <= 0) return
        val app = ctx.applicationContext
        handler.postDelayed({
            if (app.getSystemService(android.os.PowerManager::class.java).isInteractive) return@postDelayed
            LockPrefs.setAodOffByUs(app, true)
            AodOverlay.hide()
            Thread { dev.opencover.quick.ShizukuShell.run("settings", "put", "system", "aod_mode", "0") }.start()
        }, m * 60_000L)
    }

    /** The phone woke: Samsung's AOD back on (for the next sleep), if this turned it off. */
    fun stop(ctx: Context) {
        handler.removeCallbacksAndMessages(null)
        val app = ctx.applicationContext
        if (!LockPrefs.aodOffByUs(app)) return
        Thread {
            if (dev.opencover.quick.ShizukuShell.run("settings", "put", "system", "aod_mode", "1")) LockPrefs.setAodOffByUs(app, false)
        }.start()
    }
}
