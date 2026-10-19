package dev.opencover.apps

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.widget.Toast
import dev.opencover.COVER_DISPLAY
import dev.opencover.quick.ShizukuShell
import dev.opencover.watcher.CoverAccessibility

object AppLauncher {
    fun options(): Bundle =
        ActivityOptions.makeBasic().setLaunchDisplayId(COVER_DISPLAY).toBundle()

    /**
     * Starts one of our own screens (or a Settings page) on the cover display. [animate] false: no system transition
     * (custom ones are ignored when we start from the background), the screen animates itself instead.
     */
    fun start(ctx: Context, intent: Intent, animate: Boolean = true) {
        // The accessibility service is exempt from the background-start block (the mirror view opens from a service).
        if (!animate) intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        (CoverAccessibility.instance ?: ctx).startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options())
    }

    /**
     * Launches an installed app on the cover display as the given user (serial number); [onFail] runs on the main
     * thread if it didn't open. Samsung answers many apps launched by another app with "Open phone to continue", but
     * lets the same launch from the shell through, so Shizuku goes first.
     */
    fun launch(ctx: Context, pkg: String, activity: String, user: Long, onFail: () -> Unit = {}) {
        val handle = ctx.getSystemService(UserManager::class.java).getUserForSerialNumber(user)
        // UserHandle's id is hidden API; its toString is "UserHandle{<id>}".
        val userId = handle?.toString()?.substringAfter('{')?.substringBefore('}')
        val main = Handler(Looper.getMainLooper())
        Thread {
            val shell = userId != null && ShizukuShell.run(
                "am", "start", "--display", "$COVER_DISPLAY", "--user", userId,
                "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER, "-f", "${LAUNCHER_FLAGS}", "-n", "$pkg/$activity",
            )
            if (!shell) main.post {
                try {
                    requireNotNull(handle) { "user $user gone" }
                    ctx.getSystemService(LauncherApps::class.java)
                        .startMainActivity(ComponentName(pkg, activity), handle, null, options())
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Couldn't open $pkg", Toast.LENGTH_SHORT).show()
                    onFail()
                }
            }
        }.start()
    }

    // What a launcher uses: a new task, or the app's existing one brought to the front as it was.
    private const val LAUNCHER_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
}
