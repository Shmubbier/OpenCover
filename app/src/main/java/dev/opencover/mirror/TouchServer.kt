package dev.opencover.mirror

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent

/**
 * Runs as the shell user (started by Shizuku with app_process, from this APK), not inside the app: reads one touch
 * event per line from stdin and injects it at once. The shell user may inject input; the app may not. Same idea as
 * scrcpy's server. A line is [touchLine]'s output: an action, the index of the finger it is about, then every finger
 * down as id:x:y in the target display's pixels. Argument: the display (0, the inner screen, unless a test says so).
 */
object TouchServer {
    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        // Android 14+ moved injection to InputManagerGlobal; older versions have it on InputManager.
        val im = runCatching { Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null) }
            .getOrElse { Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null) }!!
        val inject = im.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        val setDisplay = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        val display = args.firstOrNull()?.toIntOrNull() ?: 0
        var downAt = 0L
        println("ready")
        System.out.flush()
        val input = System.`in`.bufferedReader()
        while (true) {
            val line = input.readLine() ?: break // the app closed the pipe: the session is over
            val t = parseTouchLine(line) ?: continue
            val now = SystemClock.uptimeMillis()
            if (t.action == MotionEvent.ACTION_DOWN) downAt = now
            val props = Array(t.ids.size) { i -> MotionEvent.PointerProperties().apply { id = t.ids[i]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(t.ids.size) { i -> MotionEvent.PointerCoords().apply { x = t.xs[i]; y = t.ys[i]; pressure = 1f; size = 1f } }
            val ev = MotionEvent.obtain(downAt, now, t.action, t.ids.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            runCatching {
                setDisplay.invoke(ev, display)
                inject.invoke(im, ev, 0) // 0: don't wait for the app to handle it
            }
            ev.recycle()
        }
    }
}

/** One parsed line: the MotionEvent action (with the finger's index for a second finger going down or up) and every finger. */
class TouchLine(val action: Int, val ids: IntArray, val xs: FloatArray, val ys: FloatArray)

private const val ACTIONS = "DMUCPR" // down, move, up, cancel, another finger down, a finger up (others stay)

/** "D 0 0:10.0:20.0" style; null if malformed. */
fun parseTouchLine(line: String): TouchLine? {
    val p = line.trim().split(' ')
    if (p.size < 3) return null
    val kind = p[0].singleOrNull()?.takeIf { it in ACTIONS } ?: return null
    val index = p[1].toIntOrNull() ?: return null
    val fingers = p.drop(2).map { f -> f.split(':').takeIf { it.size == 3 } ?: return null }
    val ids = IntArray(fingers.size) { fingers[it][0].toIntOrNull() ?: return null }
    val xs = FloatArray(fingers.size) { fingers[it][1].toFloatOrNull() ?: return null }
    val ys = FloatArray(fingers.size) { fingers[it][2].toFloatOrNull() ?: return null }
    if (index !in ids.indices) return null
    val shifted = index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
    val action = when (kind) {
        'D' -> MotionEvent.ACTION_DOWN
        'M' -> MotionEvent.ACTION_MOVE
        'U' -> MotionEvent.ACTION_UP
        'C' -> MotionEvent.ACTION_CANCEL
        'P' -> MotionEvent.ACTION_POINTER_DOWN or shifted
        else -> MotionEvent.ACTION_POINTER_UP or shifted
    }
    return TouchLine(action, ids, xs, ys)
}

/** The line for a touch event: [kind] as in [parseTouchLine], [index] the finger it is about, [fingers] (id, x, y). */
fun touchLine(kind: Char, index: Int, fingers: List<Triple<Int, Float, Float>>): String =
    "$kind $index " + fingers.joinToString(" ") { (id, x, y) -> "$id:$x:$y" }
