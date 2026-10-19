package dev.opencover.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.opencover.lock.Spot
import dev.opencover.lock.formatLayout
import dev.opencover.lock.parseSpots

enum class ClockStyle { CURRENT, BIG, STACKED, ANALOG, GLANCE }

/** Declaration order is the default order; names are saved, so never rename one. */
enum class HomeWidget { BATTERY, ALARM, WEATHER }

/** What Home shows and can be moved in its editor. Ids are saved, so never rename one. */
enum class HomeItem { CLOCK, APPS }

/** Where Home's items go until moved: the clock top left, the quick apps along the bottom (clear of the camera). */
val DEFAULT_HOME: Map<HomeItem, Spot> = mapOf(
    HomeItem.CLOCK to Spot(0f, 0f, true),
    HomeItem.APPS to Spot(0f, 1f, true),
)

/** Saved order: unknown and repeated ids dropped, widgets added in later versions appended. */
fun parseOrder(saved: String?): List<HomeWidget> {
    val known = saved?.split(',')?.mapNotNull { id -> HomeWidget.entries.find { it.name == id } }?.distinct().orEmpty()
    return known + (HomeWidget.entries - known.toSet())
}

/** [w] moved [by] places, clamped to the list. */
fun List<HomeWidget>.movedBy(w: HomeWidget, by: Int): List<HomeWidget> {
    val from = indexOf(w)
    val to = (from + by).coerceIn(0, size - 1)
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** Settings > Home, as Compose state so Home redraws on change. */
object HomePrefs {
    var clock by mutableStateOf(ClockStyle.CURRENT)
        private set
    var order by mutableStateOf(HomeWidget.entries.toList())
        private set
    var off by mutableStateOf(setOf(HomeWidget.WEATHER))
        private set
    var fahrenheit by mutableStateOf(false)
        private set
    /** Home's items; null until the user moves something (the big and analog clocks then stay centred). */
    var layout by mutableStateOf<Map<HomeItem, Spot>?>(null)
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        clock = ClockStyle.entries.find { it.name == p.getString("clock", null) } ?: ClockStyle.CURRENT
        order = parseOrder(p.getString("widgets", null))
        off = p.getString("off", null)?.let { s -> s.split(',').mapNotNull { id -> HomeWidget.entries.find { it.name == id } }.toSet() }
            ?: setOf(HomeWidget.WEATHER) // weather is opt-in
        fahrenheit = p.getBoolean("fahrenheit", false)
        layout = p.getString("layout", null)?.let { parseSpots(it, HomeItem.entries, DEFAULT_HOME) }
        // Location revoked since: weather switches itself off rather than showing an old reading.
        if (HomeWidget.WEATHER !in off &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) setShown(ctx, HomeWidget.WEATHER, false)
    }

    fun shown() = order.filter { it !in off }

    fun setClock(ctx: Context, s: ClockStyle) { clock = s; prefs(ctx).edit().putString("clock", s.name).apply() }
    fun setOrder(ctx: Context, l: List<HomeWidget>) { order = l; prefs(ctx).edit().putString("widgets", l.joinToString(",") { it.name }).apply() }
    fun setShown(ctx: Context, w: HomeWidget, on: Boolean) {
        off = if (on) off - w else off + w
        prefs(ctx).edit().putString("off", off.joinToString(",") { it.name }).apply()
    }
    fun setLayout(ctx: Context, m: Map<HomeItem, Spot>) { layout = m; prefs(ctx).edit().putString("layout", formatLayout(m)).apply() }
    fun setFahrenheit(ctx: Context, f: Boolean) { fahrenheit = f; prefs(ctx).edit().putBoolean("fahrenheit", f).apply() }
}
