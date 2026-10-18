package dev.opencover.look

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Where an animated background is. It moves only by the time between drawn frames while [playing], so it holds still
 * when nothing is drawn (screen off, another page) and carries on from the same frame. [position] is Compose state.
 */
class Playhead {
    var playing = false
    var position by mutableStateOf(0L)
        private set

    /** One drawn frame, [dt] ms after the last; a long gap (the screen was off) counts as one short frame. */
    fun frame(dt: Long) { if (playing) position += dt.coerceIn(0, MAX_STEP) }

    /** The time into a GIF [duration] ms long. */
    fun at(duration: Int): Long = if (duration > 0) position % duration else 0L

    private companion object { const val MAX_STEP = 100L }
}

/** One playhead each: the lock screen's is shared with the AOD, which shows the frame it stopped on. */
object Playheads {
    val home = Playhead()
    val lock = Playhead()
}

data class StyleSettings(val opacity: Float, val blur: Float)

/** Panel looks for the notifications page, apps pages, drawer, quick settings and media. Ids are saved: never rename. */
enum class PanelStyle(val label: String, val default: StyleSettings) {
    FROSTED("Frosted glass", StyleSettings(0.45f, 18f)),
    SCRIM("Scrim", StyleSettings(0.78f, 0f)),
    TINT("Material You tint", StyleSettings(0.86f, 6f)),
    CARDS("Floating cards", StyleSettings(0.82f, 10f)),
}

/** "FROSTED=0.45,18;SCRIM=..." Broken or missing entries take the style's defaults. */
fun parseStyleSettings(s: String?): Map<PanelStyle, StyleSettings> {
    val saved = s.orEmpty().split(';').mapNotNull { e ->
        val (id, v) = e.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        val style = PanelStyle.entries.find { it.name == id } ?: return@mapNotNull null
        val (o, b) = v.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 2 } ?: return@mapNotNull null
        style to StyleSettings(o.coerceIn(0f, 1f), b.coerceIn(0f, 40f))
    }.toMap()
    return PanelStyle.entries.associateWith { saved[it] ?: it.default }
}

fun formatStyleSettings(m: Map<PanelStyle, StyleSettings>): String =
    m.entries.joinToString(";") { (k, v) -> "${k.name}=${v.opacity},${v.blur}" }

/** Whether a pulled panel opens on release: the drawer on a short pull or a flick, the others past 15%. */
fun opensOnRelease(reveal: Float, flingUp: Boolean, drawer: Boolean): Boolean =
    if (drawer) reveal > 0.04f || flingUp else reveal > 0.15f

object LookPrefs {
    var style by mutableStateOf(PanelStyle.FROSTED)
        private set
    var settings by mutableStateOf(parseStyleSettings(null))
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("look", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        style = PanelStyle.entries.find { it.name == p.getString("style", null) } ?: PanelStyle.FROSTED
        settings = parseStyleSettings(p.getString("settings", null))
    }

    fun setStyle(ctx: Context, s: PanelStyle) { style = s; prefs(ctx).edit().putString("style", s.name).apply() }

    fun setSettings(ctx: Context, s: PanelStyle, v: StyleSettings) {
        settings = settings + (s to v)
        prefs(ctx).edit().putString("settings", formatStyleSettings(settings)).apply()
    }

    val current get() = settings.getValue(style)
}
