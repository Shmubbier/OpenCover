package dev.opencover.media

import android.content.Context

enum class UpAction { SWITCH, SHUFFLE, REPEAT }

/** The player after [currentId], wrapping around; null when there's only one. */
fun nextPlayer(players: List<Player>, currentId: String): Player? {
    if (players.size < 2) return null
    val i = players.indexOfFirst { it.id == currentId }
    return players[(i + 1) % players.size]
}

/**
 * Graded swipe up (style B): [fraction] of the screen height picks switch app, shuffle, then repeat.
 * An unsupported step falls back to the nearest supported one below it.
 */
fun gradedAction(fraction: Float, canSwitch: Boolean, canShuffle: Boolean, canRepeat: Boolean): UpAction? = when {
    fraction >= 0.5f && canRepeat -> UpAction.REPEAT
    fraction >= 0.3f && canShuffle -> UpAction.SHUFFLE
    fraction >= 0.12f && canSwitch -> UpAction.SWITCH
    else -> null
}

/**
 * Settings > Media player. [classic]: buttons and app pill instead of gestures.
 * [graded] (gesture layout only): graded swipe up instead of corner shuffle/repeat buttons.
 */
object MediaPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("media", Context.MODE_PRIVATE)
    fun classic(ctx: Context) = prefs(ctx).getBoolean("classic", false)
    fun setClassic(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("classic", on).apply()
    fun graded(ctx: Context) = prefs(ctx).getBoolean("graded", false)
    fun setGraded(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("graded", on).apply()
}
