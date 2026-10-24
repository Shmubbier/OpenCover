package dev.opencover.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.view.Display
import dev.opencover.watcher.CoverAccessibility

/**
 * Live touch on the inner screen without Shizuku: one finger streamed as an accessibility gesture that keeps going
 * (stroke continuation), so taps land and drags follow the finger as it moves, instead of being replayed after it
 * lifts. Each piece is sent once the last one has been played. Main thread only. Same idea as Z Flip Cover Power.
 */
object LiveGesture {
    private var stroke: GestureDescription.StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f
    private var lastAt = 0L
    private var pendingX = 0f
    private var pendingY = 0f
    private var up = false
    private var busy = false // a piece is being played

    fun down(x: Float, y: Float) {
        stroke = null; busy = false; up = false
        lastX = x; lastY = y; pendingX = x; pendingY = y
        lastAt = SystemClock.uptimeMillis()
        send(Path().apply { moveTo(x, y) }, 1, willContinue = true, first = true)
    }

    fun move(x: Float, y: Float) { pendingX = x; pendingY = y; if (!busy) next() }

    fun up(x: Float, y: Float) { pendingX = x; pendingY = y; up = true; if (!busy) next() }

    private fun next() {
        if (stroke == null) return
        val moved = pendingX != lastX || pendingY != lastY
        if (!moved && !up) return
        val now = SystemClock.uptimeMillis()
        val path = Path().apply { moveTo(lastX, lastY); lineTo(pendingX, pendingY) }
        val ms = (now - lastAt).coerceIn(1, 100)
        lastX = pendingX; lastY = pendingY; lastAt = now
        send(path, ms, willContinue = !up, first = false)
    }

    private fun send(path: Path, ms: Long, willContinue: Boolean, first: Boolean) {
        val service = CoverAccessibility.instance ?: return
        val s = if (first) GestureDescription.StrokeDescription(path, 0, ms, willContinue)
        else stroke?.continueStroke(path, 0, ms, willContinue) ?: return
        stroke = s
        busy = true
        val g = GestureDescription.Builder().addStroke(s).setDisplayId(Display.DEFAULT_DISPLAY).build()
        service.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription) { busy = false; if (willContinue) next() else stroke = null }
            override fun onCancelled(d: GestureDescription) { busy = false; stroke = null }
        }, null)
    }
}
