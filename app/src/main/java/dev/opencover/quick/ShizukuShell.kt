package dev.opencover.quick

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Runs shell commands through Shizuku (shell-level access, no root). */
object ShizukuShell {
    fun installed(ctx: Context) = try {
        ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun running() = try { Shizuku.pingBinder() } catch (e: Exception) { false }

    // Shizuku can die between calls; its API then throws, so every entry point catches.
    fun allowed() = try {
        running() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    fun request() {
        try { if (running() && !allowed()) Shizuku.requestPermission(1) } catch (e: Exception) {}
    }

    private var listeners: Triple<Shizuku.OnBinderReceivedListener, Shizuku.OnBinderDeadListener, Shizuku.OnRequestPermissionResultListener>? = null

    fun listen(onChange: () -> Unit) {
        unlisten()
        val l = Triple(
            Shizuku.OnBinderReceivedListener { onChange() },
            Shizuku.OnBinderDeadListener { onChange() },
            Shizuku.OnRequestPermissionResultListener { _, _ -> onChange() },
        )
        Shizuku.addBinderReceivedListenerSticky(l.first)
        Shizuku.addBinderDeadListener(l.second)
        Shizuku.addRequestPermissionResultListener(l.third)
        listeners = l
    }

    fun unlisten() {
        listeners?.let {
            Shizuku.removeBinderReceivedListener(it.first)
            Shizuku.removeBinderDeadListener(it.second)
            Shizuku.removeRequestPermissionResultListener(it.third)
        }
        listeners = null
    }

    // ponytail: Shizuku 13 hides newProcess; reflection is the documented stopgap. Move to a UserService if it breaks.
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            .apply { isAccessible = true }
    }

    // ponytail: fixed 30 s ceiling for every command; make it a parameter if a slow command needs more.
    private const val TIMEOUT_SECONDS = 30L

    // Output drained and the wait bounded, so a chatty or stuck command can't hang the caller. Without Shizuku the
    // command runs as OpenCover itself: enough for `settings put` (OpenCover holds WRITE_SECURE_SETTINGS) and for
    // `cmd device_state` while OpenCover is the top app; anything needing the shell user just fails, as before.
    private fun exec(cmd: Array<out String>): Finished? = try {
        val p = if (allowed()) newProcess.invoke(null, arrayOf(*cmd), null, null) as Process else Runtime.getRuntime().exec(cmd)
        await(p, TIMEOUT_SECONDS)
    } catch (e: Exception) {
        null
    }

    /** Blocking; call off the main thread. Null if Shizuku isn't ready, the command can't start or it times out. */
    fun output(vararg cmd: String): String? = exec(cmd)?.out

    /** Blocking; call off the main thread. False if Shizuku isn't ready, the command fails or it times out. */
    fun run(vararg cmd: String): Boolean = exec(cmd)?.exit == 0

    /** Starts a long-running command and hands it back to talk to; null if Shizuku isn't ready. */
    fun spawn(vararg cmd: String): Process? = try {
        if (!allowed()) null else newProcess.invoke(null, arrayOf(*cmd), null, null) as Process
    } catch (e: Exception) {
        null
    }
}
