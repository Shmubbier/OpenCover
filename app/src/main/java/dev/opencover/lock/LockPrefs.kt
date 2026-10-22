package dev.opencover.lock

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.opencover.home.ClockStyle

/** AOD and lock screen settings (Compose state), SharedPreferences "lock". */
object LockPrefs {
    var aod by mutableStateOf(true)
        private set
    var lockScreen by mutableStateOf(false)
        private set
    var clock by mutableStateOf(ClockStyle.CURRENT)
        private set
    var layout by mutableStateOf(DEFAULT_LAYOUT)
        private set
    /** AOD on black instead of the dimmed wallpaper: far fewer lit pixels (battery). */
    var aodBlack by mutableStateOf(false)
        private set
    /** Minutes of AOD before the cover goes fully dark; 0 = never. */
    var aodMinutes by mutableStateOf(0)
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("lock", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        aod = p.getBoolean("aod", true)
        lockScreen = p.getBoolean("lock_screen", false)
        clock = ClockStyle.entries.find { it.name == p.getString("clock", null) } ?: ClockStyle.CURRENT
        layout = parseLayout(p.getString("layout", null))
        aodBlack = p.getBoolean("aod_black", false)
        aodMinutes = p.getInt("aod_minutes", 0)
    }

    fun setAodBlack(ctx: Context, on: Boolean) { aodBlack = on; prefs(ctx).edit().putBoolean("aod_black", on).apply() }
    fun setAodMinutes(ctx: Context, m: Int) { aodMinutes = m; prefs(ctx).edit().putInt("aod_minutes", m).apply() }

    /** OpenCover turned Samsung's AOD off (AOD timeout) and must turn it back on at the next wake. */
    fun aodOffByUs(ctx: Context) = prefs(ctx).getBoolean("aod_off_by_us", false)
    fun setAodOffByUs(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("aod_off_by_us", on).commit() }

    fun setAod(ctx: Context, on: Boolean) { aod = on; prefs(ctx).edit().putBoolean("aod", on).apply() }
    fun setLockScreen(ctx: Context, on: Boolean) { lockScreen = on; prefs(ctx).edit().putBoolean("lock_screen", on).apply() }
    fun setClock(ctx: Context, s: ClockStyle) { clock = s; prefs(ctx).edit().putString("clock", s.name).apply() }
    fun setLayout(ctx: Context, m: Map<Module, Spot>) { layout = m; prefs(ctx).edit().putString("layout", formatLayout(m)).apply() }
}
