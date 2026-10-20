package dev.opencover.mirror

import android.content.Context
import android.util.Log
import dev.opencover.quick.ShizukuShell
import java.io.Writer
import java.util.concurrent.Executors

/**
 * Touches from the mirror view, played on the inner display as they happen through [TouchServer] (started through
 * Shizuku as the shell user). If it can't start, a stroke is replayed after the finger lifts with `input swipe`.
 */
object MirrorInput {
    // One thread keeps touches in the order they were made.
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "mirror-input").apply { isDaemon = true } }
    private var server: Process? = null
    private var out: Writer? = null

    /** Starts the touch server; call when mirroring starts. */
    fun start(ctx: Context) {
        val apk = ctx.applicationInfo.sourceDir
        worker.execute {
            if (out != null) return@execute
            val p = ShizukuShell.spawn("sh", "-c", "CLASSPATH='$apk' exec app_process / ${TouchServer::class.java.name}") ?: return@execute
            val ready = runCatching { p.inputStream.bufferedReader().readLine() }.getOrNull()
            if (ready != "ready") {
                Log.w("OpenCoverMirror", "touch server didn't start: $ready ${runCatching { p.errorStream.bufferedReader().readText() }.getOrNull()}")
                p.destroy(); return@execute
            }
            server = p
            out = p.outputStream.bufferedWriter()
        }
    }

    /** Live touch: a line made by [touchLine] (every finger, in inner-screen pixels). */
    fun touch(line: String) {
        worker.execute {
            val w = out ?: return@execute
            runCatching { w.write(line); w.write("\n"); w.flush() }.onFailure { stopServer() }
        }
    }

    /** Whether touches go live; otherwise use [stroke]. */
    val live get() = out != null

    fun stop() = worker.execute { stopServer() }

    private fun stopServer() {
        runCatching { out?.close() }
        server?.destroy()
        out = null
        server = null
    }

    // Fallback: a stroke replayed as one straight swipe after the finger lifts.
    fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val args = inputArgs(x1, y1, x2, y2, ms).toTypedArray()
        worker.execute { ShizukuShell.run(*args) }
    }
}
