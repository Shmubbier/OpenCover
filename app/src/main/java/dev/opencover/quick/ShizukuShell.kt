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

    /** Blocking; call off the main thread. Null if Shizuku isn't ready or the command can't start. */
    fun output(vararg cmd: String): String? = try {
        if (!allowed()) null
        else (newProcess.invoke(null, arrayOf(*cmd), null, null) as Process).let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }
    } catch (e: Exception) {
        null
    }

    /** Blocking; call off the main thread. False if Shizuku isn't ready or the command fails. */
    fun run(vararg cmd: String): Boolean = try {
        allowed() && (newProcess.invoke(null, arrayOf(*cmd), null, null) as Process).waitFor() == 0
    } catch (e: Exception) {
        false
    }
}
