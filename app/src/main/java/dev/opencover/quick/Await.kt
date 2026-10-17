package dev.opencover.quick

import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

data class Finished(val exit: Int, val out: String)

/**
 * Waits for a process without hanging: stdout and stderr are drained on their own threads (a full
 * pipe would otherwise block the child forever), the wait is bounded, and the process is always destroyed.
 * Null when it doesn't finish in time.
 */
fun await(p: Process, timeoutSeconds: Long): Finished? {
    val out = AtomicReference("")
    val reader = thread(isDaemon = true) { runCatching { out.set(p.inputStream.bufferedReader().use { it.readText() }) } }
    val drain = thread(isDaemon = true) { runCatching { p.errorStream.use { it.copyTo(OutputStream.nullOutputStream()) } } }
    // Not waitFor(timeout): its default polls exitValue() expecting IllegalThreadStateException,
    // but Shizuku's remote process throws IllegalArgumentException, so it failed instantly.
    val waiter = thread(isDaemon = true) { runCatching { p.waitFor() } }
    return try {
        waiter.join(TimeUnit.SECONDS.toMillis(timeoutSeconds))
        if (waiter.isAlive) null
        else {
            reader.join(TimeUnit.SECONDS.toMillis(timeoutSeconds))
            drain.join(TimeUnit.SECONDS.toMillis(timeoutSeconds))
            Finished(p.exitValue(), out.get())
        }
    } finally {
        p.destroy()
    }
}
