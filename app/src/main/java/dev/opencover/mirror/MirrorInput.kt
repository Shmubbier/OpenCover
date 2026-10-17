package dev.opencover.mirror

import dev.opencover.quick.ShizukuShell
import java.util.concurrent.Executors

/** Replays touches from the mirror view on the inner display through Shizuku's `input`, so no accessibility service is needed. */
object MirrorInput {
    // One thread keeps strokes in the order they were drawn.
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "mirror-input").apply { isDaemon = true } }

    // ponytail: a stroke is replayed as a straight first-to-last-point swipe after the finger lifts; curves flatten
    // and there is no live drag. Real-time injection needs hidden InputManager APIs.
    fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val args = inputArgs(x1, y1, x2, y2, ms).toTypedArray()
        worker.execute { ShizukuShell.run(*args) }
    }
}
