package dev.opencover.phone

import android.content.Context
import java.util.concurrent.Executors

/**
 * Per-app cover density through Shizuku: when an app with its own density comes to the cover, the cover switches to it,
 * and back to what it was (the user's own density or the cover's) when OpenCover or another app is in front.
 */
object AppDensity {
    /** Choices offered per app, in dpi; the cover's own is 340. Lower fits more. */
    val PRESETS = listOf(300, 260, 220, 400)

    private val shell = Executors.newSingleThreadExecutor { Thread(it, "density-shell").apply { isDaemon = true } }

    /** The density set on the cover by this, so an unchanged app costs no shell call (shell thread only). */
    private var applied: Int? = null

    /** An app's window came to the cover. */
    fun onWindow(ctx: Context, pkg: String?) {
        val prefs = PhonePrefs(ctx.applicationContext)
        val want = pkg?.let { prefs.densityFor(it) }
        if (want == null && prefs.densityBase == null) return // nothing of ours on the cover, no Shizuku call
        shell.execute { apply(prefs, want) }
    }

    private fun apply(prefs: PhonePrefs, want: Int?) {
        if (want != null) {
            if (want == applied) return
            // First switch away from the user's density: remember it (0 = the cover's own) to put back later.
            if (prefs.densityBase == null) prefs.densityBase = DisplayTweaks.coverOverride() ?: 0
            if (DisplayTweaks.setCoverDensity(want)) applied = want
        } else {
            val base = prefs.densityBase ?: return
            if (DisplayTweaks.setCoverDensity(base.takeIf { it > 0 })) { prefs.densityBase = null; applied = null }
        }
    }
}

/** Steps through "Default" then each preset and back. */
fun nextDensity(now: Int?): Int? = when (val i = AppDensity.PRESETS.indexOf(now)) {
    -1 -> AppDensity.PRESETS.first()
    AppDensity.PRESETS.lastIndex -> null
    else -> AppDensity.PRESETS[i + 1]
}
