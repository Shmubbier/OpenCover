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

    // Hidden API (as Z Flip Cover Power uses it): without it the launch keeps the system's default windowing mode.
    private val setWindowingMode by lazy {
        runCatching { ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType) }.getOrNull()
    }

    /** Options for an app opened on the cover without Shizuku: fullscreen there, allowed to start from the background. */
    private fun appOptions(): Bundle = ActivityOptions.makeBasic().setLaunchDisplayId(COVER_DISPLAY).apply {
        runCatching { setWindowingMode?.invoke(this, 1) } // WINDOWING_MODE_FULLSCREEN
        setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
    }.toBundle()

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
     * Moves [pkg] to the cover, its task as it is (`am start` as the shell). Needs Shizuku: without it Samsung only lets
     * apps on Good Lock MultiStar's cover list open there, and it keeps those on the cover itself. Off the main thread.
     */
    fun moveToCover(ctx: Context, pkg: String): Boolean {
        val component = ctx.packageManager.getLaunchIntentForPackage(pkg)?.component ?: return false
        return ShizukuShell.allowed() && ShizukuShell.run(
            "am", "start", "--display", "$COVER_DISPLAY", "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER,
            "-f", "$LAUNCHER_FLAGS", "-n", component.flattenToShortString(),
        )
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
                    val service = CoverAccessibility.instance
                    if (service != null && handle == android.os.Process.myUserHandle()) {
                        // No Shizuku: start it from the accessibility service, which Samsung lets open apps on the
                        // cover (a plain launcher start gets "Open phone to continue").
                        service.startActivity(
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(pkg, activity))
                                .addFlags(LAUNCHER_FLAGS),
                            appOptions(),
                        )
                    } else {
                        ctx.getSystemService(LauncherApps::class.java)
                            .startMainActivity(ComponentName(pkg, activity), handle, null, appOptions())
                    }
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
