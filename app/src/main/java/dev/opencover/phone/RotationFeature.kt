package dev.opencover.phone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import dev.opencover.COVER_DISPLAY
import dev.opencover.quick.ShizukuShell
import dev.opencover.watcher.CoverAccessibility
import java.util.concurrent.Executors

/**
 * Per-app cover rotation. Android never rotates the cover itself while the phone is folded and this phone has no
 * accelerometer to ask, so each app gets a fixed rotation, set by [flip] and pinned through Shizuku whenever that app
 * comes to the cover. Apps never flipped, OpenCover and Samsung's cover home stay upright.
 */
object RotationFeature {
    private const val RESET_HOLD_MS = 1_500L

    private val handler = Handler(Looper.getMainLooper())
    private val shell = Executors.newSingleThreadExecutor { Thread(it, "rotation-shell").apply { isDaemon = true } }

    /** Last app whose window came to the cover (accessibility thread). */
    @Volatile private var foreground: String? = null

    /** An app's window came to the cover: pin its saved rotation. */
    fun onWindow(ctx: Context, pkg: String) {
        foreground = pkg
        pin(ctx, PhonePrefs(ctx).turnsFor(pkg))
        AppDensity.onWindow(ctx, pkg)
    }

    /** OpenCover or Samsung's cover home is in front: upright. */
    fun upright(ctx: Context) {
        foreground = null
        pin(ctx, 0)
        AppDensity.onWindow(ctx, null)
    }

    /**
     * Turns the app shown on the cover before OpenCover a quarter turn further and remembers it; it shows turned when
     * that app comes back. Blocking (Shizuku). Returns a hint when there is nothing to turn.
     */
    fun flip(ctx: Context): String? {
        val list = ShizukuShell.output("am", "stack", "list") ?: return "Start Shizuku"
        val skip = setOfNotNull(ctx.packageName, homePackage(ctx), "com.android.systemui")
        val pkg = flipTarget(list, skip) ?: return "Open an app on the cover first"
        val prefs = PhonePrefs(ctx)
        prefs.setTurns(pkg, nextTurns(prefs.turnsFor(pkg)))
        handler.post { CoverAccessibility.instance?.refreshScope() } // the first turned app widens the scope
        return null
    }

    /**
     * Puts the cover back at rotation 0, then at what the app on it wants. A mirror session leaves the cover at 180 and
     * nothing turns it back. Stays pending until it has run, so it also happens when the accessibility service only
     * returns after the session ended.
     */
    fun resetToDefault(ctx: Context) {
        val app = ctx.applicationContext
        PhonePrefs(app).rotationResetPending = true
        run(holdRotation(0) + "; " + coverSize(0))
        handler.postDelayed({
            PhonePrefs(app).rotationResetPending = false
            foreground?.let { onWindow(app, it) }
        }, RESET_HOLD_MS)
    }

    private fun pin(ctx: Context, turns: Int) {
        val now = ctx.getSystemService(DisplayManager::class.java).getDisplay(COVER_DISPLAY)?.rotation ?: 0
        if (turns != now) run(holdRotation(turns) + "; " + coverSize(turns))
    }

    /** The default launcher only: other packages (Settings has a fallback home) also answer a HOME query. */
    private fun homePackage(ctx: Context): String? =
        ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName

    private fun run(script: String) { shell.execute { ShizukuShell.run("sh", "-c", script) } }
}
