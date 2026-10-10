package dev.opencover.watcher

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Top-edge priority: while OpenCover is in front, close Samsung's pull-down panel as soon as it
 * opens and show OpenCover's quick settings instead. Needs the accessibility service to read the
 * window list, so it's a setting: on by default in debug builds, off in release builds.
 */
object TopEdge {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("top_edge", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean {
        val debug = ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return prefs(ctx).getBoolean("enabled", debug)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", on).apply()
        CoverAccessibility.instance?.refreshScope()
    }
}
