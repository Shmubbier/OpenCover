package dev.opencover.mirror

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 *  - developer options and unknown sources off, because banking apps like GCash refuse to run with them on.
 * Everything is put back when mirroring stops (see [resetState] for the lock that costs).
 */
object MirrorEnv {
    // One thread, so a session's changes and their undoing always run in the order they were asked for.
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "mirror-env").apply { isDaemon = true } }

    fun later(block: () -> Unit) = worker.execute(block)

    private const val UNFOLDED_STATE = "4"
    private const val SECURE_NON_MARKET = "install_non_market_apps"
    private const val A11Y = "enabled_accessibility_services"
    private const val HIDE = "settings put global development_settings_enabled 0; settings put secure $SECURE_NON_MARKET 0"

    /** Blocking; call off the main thread. Returns the shell output, null if Shizuku isn't ready. */
    fun enter(ctx: Context): String? {
        if (MirrorService.instance == null) return null // the session already ended
        val prefs = PhonePrefs(ctx)
        prefs.resetPending = true // until leave() finishes the job; if we die mid-session, recover() resets
        // A crashed earlier run may have left our zeros behind; its saved originals win.
        if (prefs.restoreSettings == null) {
            val r = ctx.contentResolver
            prefs.restoreSettings = "${Settings.Global.getInt(r, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)}," +
                Settings.Secure.getInt(r, SECURE_NON_MARKET, 0)
        }
        // Optional: a shorter inner screen fits the near-square cover better. Before the state change, so the capture
        // size read once the display settles already has it.
        val resize = if (prefs.mirrorResize) innerScreenScript(prefs.mirrorSize, prefs.mirrorDensity) else null
        if (resize != null) prefs.innerResetPending = true
        return ShizukuShell.output("sh", "-c", "${resize.orEmpty()}cmd device_state state $UNFOLDED_STATE; $HIDE; cmd device_state print-state")
    }

    /**
     * Banking apps such as GCash refuse to run next to any accessibility service they don't know, OpenCover's included,
     * so it is switched off for the session (touch goes through [MirrorInput] instead). Blocking; call off the main thread.
     * Call only once the mirror view is up: launching it uses the service.
     */
    fun hideAccessibility(ctx: Context) {
        if (MirrorService.instance == null) return // the session already ended; leave() has restored it
        val now = Settings.Secure.getString(ctx.contentResolver, A11Y)
        val me = me(ctx)
        if (me !in now.orEmpty().split(":")) return // wasn't on (banking mode may have it off)
        PhonePrefs(ctx).restoreAccessibility = true
        ShizukuShell.run("settings", "put", "secure", A11Y, withService(now, me, on = false))
    }

    private fun me(ctx: Context) = ComponentName(ctx, CoverAccessibility::class.java).flattenToString()

    fun leave(ctx: Context) {
        val app = ctx.applicationContext
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
        if (prefs.resetPending) resetWhenAsleep(app)
    }

    private fun restoreAccessibility(app: Context) {
        val prefs = PhonePrefs(app)
        val list = restoredAccessibility(Settings.Secure.getString(app.contentResolver, A11Y), me(app), prefs.restoreAccessibility) ?: return
        if (ShizukuShell.run("sh", "-c", "settings put secure $A11Y '$list'; settings put secure accessibility_enabled 1")) {
            prefs.restoreAccessibility = false
        }
    }

    private fun restoreInnerScreen(app: Context) {
        val prefs = PhonePrefs(app)
        if (prefs.innerResetPending && ShizukuShell.run("sh", "-c", INNER_SCREEN_RESET)) prefs.innerResetPending = false
    }

    private fun restoreSettings(app: Context) {
        val prefs = PhonePrefs(app)
        val (dev, nm) = prefs.restoreSettings?.split(",")?.takeIf { it.size == 2 } ?: return
        if (ShizukuShell.run("sh", "-c", "settings put global development_settings_enabled $dev; settings put secure $SECURE_NON_MARKET $nm")) {
            prefs.restoreSettings = null
        }
    }

    private fun resetWhenAsleep(app: Context) {
        if (!app.getSystemService(PowerManager::class.java).isInteractive) { resetNow(app); return }
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                app.unregisterReceiver(this)
                resetNow(app)
            }
        }, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
    }

    private fun resetNow(app: Context) {
        val prefs = PhonePrefs(app)
        if (!prefs.resetPending) return
        // Screen off while mirroring ends the session (as in Z Cover Power); stop() comes back here with no session.
        MirrorService.instance?.let { it.stop(); return }
        resetState(app, wake = false)
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
