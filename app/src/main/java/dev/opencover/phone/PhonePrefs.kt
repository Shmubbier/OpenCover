package dev.opencover.phone

import android.content.Context

/** Settings and session state for rotation, keep-cover-on, mirroring and the heat guard. */
class PhonePrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("phone", Context.MODE_PRIVATE)

    /** Fixed cover rotation per app, in clockwise quarter turns; apps not listed stay upright. */
    fun turnsFor(pkg: String): Int = parseRotations(sp.getString("app_rotations", null))[pkg] ?: 0

    fun setTurns(pkg: String, turns: Int) {
        val all = parseRotations(sp.getString("app_rotations", null)).toMutableMap()
        if (turns % 4 == 0) all.remove(pkg) else all[pkg] = turns % 4
        sp.edit().putString("app_rotations", encodeRotations(all)).commit()
    }

    /** Every turned app and its quarter turns. */
    val rotations: Map<String, Int> get() = parseRotations(sp.getString("app_rotations", null))

    /** At least one app is turned: the accessibility service then has to see every app's windows. */
    val hasRotations: Boolean get() = parseRotations(sp.getString("app_rotations", null)).isNotEmpty()

    /** Clockwise quarter turns applied to the mirror view, 0..3. */
    var mirrorTurns: Int
        get() = sp.getInt("mirror_turns", 0).coerceIn(0, 3)
        set(v) = sp.edit().putInt("mirror_turns", v.mod(4)).apply()

    /** "developerOptions,unknownSources" as they were before mirroring hid them; null when nothing to restore. */
    var restoreSettings: String?
        get() = sp.getString("restore_settings", null)
        set(v) { sp.edit().putString("restore_settings", v).commit() }

    /** Mirroring switched OpenCover's accessibility service off and has to switch it back on. */
    var restoreAccessibility: Boolean
        get() = sp.getBoolean("restore_accessibility", false)
        set(v) { sp.edit().putBoolean("restore_accessibility", v).commit() }

    /** The device state still has to be put back after a mirror session. */
    var resetPending: Boolean
        get() = sp.getBoolean("reset_pending", false)
        set(v) { sp.edit().putBoolean("reset_pending", v).commit() }

    /** A mirror session ended and the cover's rotation still has to be put back to normal. */
    var rotationResetPending: Boolean
        get() = sp.getBoolean("rotation_reset_pending", false)
        set(v) = sp.edit().putBoolean("rotation_reset_pending", v).apply()

    /** Hold the device state at TENT so the cover stays on when the phone is opened (see Fold). */
    var keepCoverOn: Boolean
        get() = sp.getBoolean("keep_cover_on", false)
        set(v) = sp.edit().putBoolean("keep_cover_on", v).apply()

    /** Warn and cut load when the phone gets hot while the hotspot or mirroring is on (see HeatGuard). */
    var heatGuard: Boolean
        get() = sp.getBoolean("heat_guard", true)
        set(v) = sp.edit().putBoolean("heat_guard", v).apply()

    /** Before mirroring, set the inner screen to [mirrorSize] at [mirrorDensity]; reset when mirroring ends. */
    var mirrorResize: Boolean
        get() = sp.getBoolean("mirror_resize", false)
        set(v) = sp.edit().putBoolean("mirror_resize", v).apply()

    var mirrorSize: String
        get() = sp.getString("mirror_size", null) ?: "1080x1600"
        set(v) = sp.edit().putString("mirror_size", v).apply()

    var mirrorDensity: Int
        get() = sp.getInt("mirror_density", 400)
        set(v) = sp.edit().putInt("mirror_density", v).apply()

    /** The inner screen was resized for mirroring and still has to be put back. */
    var innerResetPending: Boolean
        get() = sp.getBoolean("inner_reset_pending", false)
        set(v) { sp.edit().putBoolean("inner_reset_pending", v).commit() }

    /** Force deep doze shortly after the screen turns off (see DeepSleep). */
    var deepSleep: Boolean
        get() = sp.getBoolean("deep_sleep", true)
        set(v) = sp.edit().putBoolean("deep_sleep", v).apply()
}
