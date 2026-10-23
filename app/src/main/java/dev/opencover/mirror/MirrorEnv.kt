package dev.opencover.mirror

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import java.util.concurrent.Executors
import dev.opencover.watcher.CoverAccessibility
import dev.opencover.watcher.withService
import dev.opencover.phone.INNER_SCREEN_RESET
import dev.opencover.phone.foldStateArgs
import dev.opencover.phone.innerScreenScript
import dev.opencover.phone.PhonePrefs
import dev.opencover.phone.RotationFeature
import dev.opencover.quick.ShizukuShell

/**
 * What the phone must look like while mirroring a folded phone:
 *  - device state 4 (both displays render), otherwise the inner screen draws nothing;
 *  - with Shizuku, developer options and unknown sources off, because banking apps refuse to run with them on and
 *    OpenCover's accessibility (which would notice a banking app and hide them then) is off for the session. Without
 *    Shizuku they stay on (adb keeps working); banking mode hides them only while a banking app is open.
 * Everything is put back when mirroring stops (see [resetState] for the lock that costs).
 */
object MirrorEnv {
    // One thread, so a session's changes and their undoing always run in the order they were asked for.
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "mirror-env").apply { isDaemon = true } }

    fun later(block: () -> Unit) = worker.execute(block)

    private const val UNFOLDED_STATE = "4"
    private const val SECURE_NON_MARKET = "install_non_market_apps"
    private const val A11Y = "enabled_accessibility_services"

    /** Blocking; call off the main thread. Returns the shell output, null if Shizuku isn't ready. */
    fun enter(ctx: Context): String? {
        if (MirrorService.instance == null) return null // the session already ended
        val prefs = PhonePrefs(ctx)
        prefs.resetPending = true // until leave() finishes the job; if we die mid-session, recover() resets
        val hideDev = ShizukuShell.allowed()
        // A crashed earlier run may have left our zeros behind; its saved originals win.
        if (hideDev && prefs.restoreSettings == null) {
            val r = ctx.contentResolver
            prefs.restoreSettings = "${Settings.Global.getInt(r, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)}," +
                Settings.Secure.getInt(r, SECURE_NON_MARKET, 0)
        }
        // Optional: a shorter inner screen fits the near-square cover better. Before the state change, so the capture
        // size read once the display settles already has it.
        // wm size needs the shell user: only with Shizuku.
        val resize = if (prefs.mirrorResize && ShizukuShell.allowed()) innerScreenScript(prefs.mirrorSize, prefs.mirrorDensity) else null
        if (resize != null) prefs.innerResetPending = true
        // OpenCover holds WRITE_SECURE_SETTINGS, so it hides these itself.
        if (hideDev) runCatching {
            Settings.Global.putInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
            Settings.Secure.putInt(ctx.contentResolver, SECURE_NON_MARKET, 0)
        }
        val out = ShizukuShell.output("sh", "-c", "${resize.orEmpty()}cmd device_state state $UNFOLDED_STATE; cmd device_state print-state")
        showPhoneHome(ctx)
        return out
    }

    /**
     * Opening moves OpenCover's Home from the cover to the inner screen; put the phone's own home in front of it there.
     * Started from the accessibility service (an `am start` without Shizuku runs as OpenCover and is ignored).
     */
    private fun showPhoneHome(ctx: Context) {
        val home = ctx.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.takeIf { it.packageName != "android" } ?: return // "android": no default home, it would ask
        Thread.sleep(500) // let the inner screen come on first
        Handler(Looper.getMainLooper()).post {
            val starter: Context = CoverAccessibility.instance ?: ctx
            runCatching {
                starter.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setComponent(android.content.ComponentName(home.packageName, home.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle(),
                )
            }
        }
    }

    /**
     * Banking apps such as GCash refuse to run next to any accessibility service they don't know, OpenCover's included,
     * so it is switched off for the session (touch goes through [MirrorInput] instead). Blocking; call off the main thread.
     * Call only once the mirror view is up: launching it uses the service.
     */
    fun hideAccessibility(ctx: Context) {
        if (MirrorService.instance == null) return // the session already ended; leave() has restored it
        // Without Shizuku, touch on the mirror goes through this service, so it has to stay on (banking apps that
        // reject it then won't run while mirroring).
        if (!ShizukuShell.allowed()) return
        val now = Settings.Secure.getString(ctx.contentResolver, A11Y)
        val me = me(ctx)
        if (me !in now.orEmpty().split(":")) return // wasn't on (banking mode may have it off)
        PhonePrefs(ctx).restoreAccessibility = true
        ShizukuShell.run("settings", "put", "secure", A11Y, withService(now, me, on = false))
    }

    private fun me(ctx: Context) = ComponentName(ctx, CoverAccessibility::class.java).flattenToString()

    fun leave(ctx: Context) {
        val app = ctx.applicationContext
        android.util.Log.i("OpenCoverMirror", "session ends", Throwable("who ended it"))
        PhonePrefs(app).resetPending = true
        later { restoreSettings(app); restoreAccessibility(app); restoreInnerScreen(app) }
        resetState(app, wake = true)
    }

    /** Finish anything an earlier run left undone (process died, Shizuku was down). Call from Application.onCreate. */
    fun recover(app: Context) {
        val prefs = PhonePrefs(app)
        if (prefs.restoreSettings != null || prefs.restoreAccessibility || prefs.innerResetPending) {
            later { restoreSettings(app); restoreAccessibility(app); restoreInnerScreen(app) }
        }
        // Left forced open the cover is black with nothing on it, so put the state back now, as a session end does
        // (the phone locks), and wake the cover if it was on.
        if (prefs.resetPending && MirrorService.instance == null) {
            resetState(app, wake = app.getSystemService(PowerManager::class.java).isInteractive)
        }
    }

    private fun restoreAccessibility(app: Context) {
        val prefs = PhonePrefs(app)
        val list = restoredAccessibility(Settings.Secure.getString(app.contentResolver, A11Y), me(app), prefs.restoreAccessibility) ?: return
        val ok = ShizukuShell.run("sh", "-c", "settings put secure $A11Y '$list'; settings put secure accessibility_enabled 1")
        android.util.Log.i("OpenCoverMirror", "restore accessibility: ${if (ok) "done" else "failed, will retry on unlock"}")
        if (ok) prefs.restoreAccessibility = false
    }

    private fun restoreInnerScreen(app: Context) {
        val prefs = PhonePrefs(app)
        if (!prefs.innerResetPending) return
        // Resized only with Shizuku; without it there is nothing to put back (and no way to).
        if (!ShizukuShell.allowed() || ShizukuShell.run("sh", "-c", INNER_SCREEN_RESET)) prefs.innerResetPending = false
    }

    private fun restoreSettings(app: Context) {
        val prefs = PhonePrefs(app)
        val (dev, nm) = prefs.restoreSettings?.split(",")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 } ?: return
        runCatching {
            Settings.Global.putInt(app.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, dev)
            Settings.Secure.putInt(app.contentResolver, SECURE_NON_MARKET, nm)
            prefs.restoreSettings = null
        }
    }

    /**
     * Left forced, the cover stays black with no navigation, so the state goes back as soon as a session ends.
     * Resetting while awake counts as a fold: the phone sleeps and locks. [wake] turns the screen back on afterwards,
     * so the cover shows the lock screen ready for the PIN; it is off when the screen was already off.
     */
    private fun resetState(app: Context, wake: Boolean) {
        val prefs = PhonePrefs(app)
        later {
            if (ShizukuShell.run(*foldStateArgs(prefs.keepCoverOn).toTypedArray())) {
                prefs.resetPending = false
                // The forced state leaves the cover rotated 180; put it back once things have settled.
                prefs.rotationResetPending = true
                Thread.sleep(1500)
                if (wake) ShizukuShell.run("input", "keyevent", "KEYCODE_WAKEUP")
                Handler(Looper.getMainLooper()).post { RotationFeature.resetToDefault(app) }
            }
        }
    }
}
