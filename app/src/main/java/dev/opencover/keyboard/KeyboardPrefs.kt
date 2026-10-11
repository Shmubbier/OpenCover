package dev.opencover.keyboard

import android.content.Context

/** Settings > Keyboard. */
object KeyboardPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("keyboard", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("enabled", on).apply()
    fun suggestions(ctx: Context) = prefs(ctx).getBoolean("suggestions", true)
    fun setSuggestions(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("suggestions", on).apply()
}
