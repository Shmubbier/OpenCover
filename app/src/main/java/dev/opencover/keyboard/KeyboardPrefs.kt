package dev.opencover.keyboard

import android.content.Context

/** Settings > Experimental. The keyboard is off by default (new key, so earlier "on" choices reset). */
object KeyboardPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("keyboard", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("experimental", false)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("experimental", on).apply()
    fun suggestions(ctx: Context) = prefs(ctx).getBoolean("suggestions", true)
    fun setSuggestions(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("suggestions", on).apply()
}
