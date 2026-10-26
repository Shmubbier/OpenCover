package dev.opencover.desk

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import dev.opencover.quick.ShizukuShell

/**
 * Gaming mode: a game app (a Steam-on-Android launcher) opened filling the monitor, with the desk kept lean while it
 * plays: no animated background (one less pass on the GPU), the cover resting at once, and the phone running off the
 * charger instead of charging (Samsung's "Pause USB Power Delivery", less heat). Closing the game ends it.
 */
object DeskGame {
    private const val TAG = "OpenCoverDesk"

    /** The game apps gaming mode knows, by package. */
    val KNOWN = listOf("com.droiddeck.launcher" to "DroidDeck", "app.gamenative" to "GameNative", "com.xiaoji.egggame" to "GameHub")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("desk", Context.MODE_PRIVATE)

    fun installed(ctx: Context) = KNOWN.filter { (pkg, _) -> ctx.packageManager.getLaunchIntentForPackage(pkg) != null }

    /** The game app chosen, if it is still installed. */
    fun app(ctx: Context): String? = prefs(ctx).getString("game_app", null)?.takeIf { pkg -> installed(ctx).any { it.first == pkg } }
    fun setApp(ctx: Context, pkg: String) = prefs(ctx).edit().putString("game_app", pkg).apply()

    /** Open the game app when the desk starts (the monitor is plugged in). */
    fun auto(ctx: Context) = prefs(ctx).getBoolean("game_auto", false)
    fun setAuto(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("game_auto", on).apply()

    /** The game app playing, while gaming mode is on. */
    @Volatile var playing: String? = null; private set
    private var passThroughBefore: String? = null

    /** Opens the chosen game app on the monitor and turns gaming mode on; says what happened. Main thread. */
    fun play(ctx: Context): String {
        if (!Desk.running || Desk.displayId < 0) return "Plug in a monitor first (with the desktop on)"
        val pkg = app(ctx) ?: return "Pick a game app first"
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return "Couldn't open it"
        // Samsung pulls an app on its cover list back to the cover while the cover has focus: give it to the desk first.
        Desk.focusDesk()
        val (w, h) = Desk.deskSize
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(Desk.displayId).setLaunchBounds(Rect(0, 0, w, h))
        val started = runCatching { ctx.startActivity(intent, options.toBundle()) }.onFailure { Log.w(TAG, "couldn't open $pkg", it) }.isSuccess
        if (!started) return "Couldn't open it"
        enter(ctx, pkg)
        return "Gaming mode: ${KNOWN.first { it.first == pkg }.second} on the monitor"
    }

    private fun enter(ctx: Context, pkg: String) {
        if (playing != null) return
        playing = pkg
        Log.i(TAG, "gaming mode on: $pkg")
        Desk.backgroundChanged() // drops the animated background while playing
        Desk.restCover()
        Thread {
            passThroughBefore = ShizukuShell.output("settings", "get", "system", "pass_through")?.trim()
            ShizukuShell.run("settings", "put", "system", "pass_through", "1")
        }.start()
    }

    /** The desk's windows (the taskbar's poll): gaming mode ends once the game app has no window left. */
    fun windowsChanged(pkgs: Set<String>) {
        val pkg = playing ?: return
        if (pkg !in pkgs) Desk.post { leave() }
    }

    /** Gaming mode off: the animated background back ([background]: not when the desk is stopping), charging as before. */
    fun leave(background: Boolean = true) {
        if (playing == null) return
        playing = null
        Log.i(TAG, "gaming mode off")
        if (background) Desk.backgroundChanged()
        val before = passThroughBefore?.takeIf { it == "0" || it == "1" } ?: "0"
        passThroughBefore = null
        Thread { ShizukuShell.run("settings", "put", "system", "pass_through", before) }.start()
    }
}
