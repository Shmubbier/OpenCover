package dev.opencover.watcher

import android.content.Context

/**
 * Experimental top-edge priority: while OpenCover is in front, close Samsung's pull-down panel as
 * soon as it opens and show OpenCover's quick settings instead. Off by default: Samsung catches the
 * swipe at system level, so its panel still flashes, and its closing window swallows touches for
 * about 2 seconds. Needs the accessibility service to read the window list.
 */
object TopEdge {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("top_edge", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", on).apply()
        CoverAccessibility.instance?.refreshScope()
    }
}
