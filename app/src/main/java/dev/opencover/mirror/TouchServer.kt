package dev.opencover.mirror

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent

/**
 * Runs as the shell user (started by Shizuku with app_process, from this APK), not inside the app: reads one touch per
 * line from stdin ("D x y", "M x y", "U x y", in inner-screen pixels) and injects it on display 0 at once. The shell
 * user may inject input; the app may not. Same idea as scrcpy's server.
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
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { pressure = 1f; size = 1f })
        val display = args.firstOrNull()?.toIntOrNull() ?: 0 // the inner screen unless told otherwise (tests use the cover)
        var downAt = 0L
        println("ready")
        System.out.flush()
        val input = System.`in`.bufferedReader()
        while (true) {
            val line = input.readLine() ?: break // the app closed the pipe: the session is over
            val p = line.split(' ')
            if (p.size != 3) continue
            val x = p[1].toFloatOrNull() ?: continue
            val y = p[2].toFloatOrNull() ?: continue
            val action = when (p[0]) {
                "D" -> MotionEvent.ACTION_DOWN
                "M" -> MotionEvent.ACTION_MOVE
                "U" -> MotionEvent.ACTION_UP
                "C" -> MotionEvent.ACTION_CANCEL
                else -> continue
            }
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downAt = now
            coords[0].x = x; coords[0].y = y
            val ev = MotionEvent.obtain(downAt, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            runCatching {
                setDisplay.invoke(ev, display)
                inject.invoke(im, ev, 0) // 0: don't wait for the app to handle it
            }
            ev.recycle()
        }
    }
}
