package dev.opencover.lock

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.opencover.home.ClockStyle

/** AOD and lock screen settings (Compose state), SharedPreferences "lock". */
object LockPrefs {
    var aod by mutableStateOf(false)
        private set
    var lockScreen by mutableStateOf(false)
        private set
    var clock by mutableStateOf(ClockStyle.CURRENT)
        private set
    var layout by mutableStateOf(DEFAULT_LAYOUT)
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("lock", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        aod = p.getBoolean("aod", false)
        lockScreen = p.getBoolean("lock_screen", false)
        clock = ClockStyle.entries.find { it.name == p.getString("clock", null) } ?: ClockStyle.CURRENT
        layout = parseLayout(p.getString("layout", null))
    }

    fun setAod(ctx: Context, on: Boolean) { aod = on; prefs(ctx).edit().putBoolean("aod", on).apply() }
    fun setLockScreen(ctx: Context, on: Boolean) { lockScreen = on; prefs(ctx).edit().putBoolean("lock_screen", on).apply() }
    fun setClock(ctx: Context, s: ClockStyle) { clock = s; prefs(ctx).edit().putString("clock", s.name).apply() }
    fun setLayout(ctx: Context, m: Map<Module, Spot>) { layout = m; prefs(ctx).edit().putString("layout", formatLayout(m)).apply() }
}
