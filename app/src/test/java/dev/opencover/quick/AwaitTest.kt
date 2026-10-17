package dev.opencover.quick

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stand-in for a Shizuku remote process. */
private class FakeProcess(
    private val out: ByteArray = ByteArray(0),
    private val err: ByteArray = ByteArray(0),
    private val exits: Boolean = true,
    private val code: Int = 0,
    private val runMs: Long = 0,
) : Process() {
    var destroyed = false
    private val doneAt = System.currentTimeMillis() + runMs
    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = ByteArrayInputStream(out)
    override fun getErrorStream(): InputStream = ByteArrayInputStream(err)
    override fun waitFor(): Int {
        if (!exits) Thread.sleep(Long.MAX_VALUE)
        Thread.sleep(maxOf(0, doneAt - System.currentTimeMillis()))
        return code
    }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exits
    // Like Shizuku's remote process: IllegalArgumentException (not IllegalThreadStateException) while running.
    override fun exitValue(): Int = if (exits && System.currentTimeMillis() >= doneAt) code else throw IllegalArgumentException("process hasn't exited")
    override fun destroy() { destroyed = true }
}

class AwaitTest {
    @Test fun returnsExitCodeAndStdout() {
        val r = await(FakeProcess(out = "hello\n".toByteArray(), code = 3), 1)
        assertEquals(3, r?.exit)
        assertEquals("hello\n", r?.out)
    }

    @Test fun largeStderrDoesNotBlock() {
        val r = await(FakeProcess(err = ByteArray(1 shl 20) { 'x'.code.toByte() }), 1)
        assertEquals(0, r?.exit)
    }

    @Test fun timeoutGivesNullAndKillsProcess() {
        val p = FakeProcess(exits = false)
        assertNull(await(p, 1))
        assertTrue(p.destroyed)
    }

    @Test fun stillRunningShizukuProcessIsWaitedFor() {
        val p = FakeProcess(code = 0, runMs = 300)
        assertEquals(0, await(p, 2)?.exit)
    }

    @Test fun finishedProcessIsAlsoDestroyed() {
        val p = FakeProcess()
        await(p, 1)
        assertTrue(p.destroyed)
    }
}
