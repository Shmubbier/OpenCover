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
                CompositionLocalProvider(LocalDensity provides Density(d.density * DP_SCALE, d.fontScale * SP_SCALE / DP_SCALE)) {
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
