package dev.opencover.desk

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The mouse on the desk (runs in [DeskService], as the shell user). Samsung keeps the mouse pointer on the cover, so
 * the mouse is read straight from its input device and grabbed, which stops the system seeing it, and its moves,
 * buttons and wheel are played on the desk display as mouse events. The cursor is drawn by OpenCover from [cursor].
 */
class DeskMouse(
    private val displayId: Int, private val w: Int, private val h: Int, private val cursor: IDeskCursor,
    /** A button went down: the desk takes keyboard focus, as clicking a window does on a computer. */
    private val onPress: () -> Unit = {},
) {
    @Volatile private var running = true
    /** The phone is locked: the pointer still moves, but nothing on the desk is clicked or scrolled. */
    @Volatile var locked = false
    private var stream: FileInputStream? = null
    private var x = w / 2f
    private var y = h / 2f
    private var buttons = 0
    private var downAt = 0L

    private val im by lazy {
        runCatching { Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null) }
            .getOrElse { Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null) }!!
    }
    private val inject by lazy { im.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType) }
    private val setDisplay by lazy { MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }

    /** Starts reading; false if there's no mouse. A mouse that drops off (unplugged, a hub reset) is taken again when it's back. */
    fun start(): Boolean {
        val first = findMouse() ?: return false
        Thread({
            var path: String? = first
            while (running) {
                if (path != null) run(path)
                if (running) { Thread.sleep(1_000); path = findMouse() }
            }
        }, "desk-mouse").start()
        return true
    }

    fun stop() {
        running = false
        runCatching { stream?.close() } // closing also lets go of the grab
    }

    private fun run(path: String) {
        val input = runCatching { FileInputStream(path) }.getOrElse { Log.w(TAG, "can't open $path", it); return }
        stream = input
        grab(input)
        runCatching { cursor.moved(x, y, false) }
        val buf = ByteArray(EVENT_SIZE)
        var dx = 0; var dy = 0; var wheel = 0f; var pressed = buttons
        while (running) {
            if (runCatching { readFully(input, buf) }.getOrDefault(false) != true) break
            val e = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            val type = e.getShort(16).toInt(); val code = e.getShort(18).toInt(); val value = e.getInt(20)
            when (type) {
                EV_REL -> when (code) { REL_X -> dx += value; REL_Y -> dy += value; REL_WHEEL -> wheel += value }
                EV_KEY -> { val bit = buttonBit(code); if (bit != 0) pressed = if (value != 0) pressed or bit else pressed and bit.inv() }
                EV_SYN -> { frame(dx, dy, wheel, pressed); dx = 0; dy = 0; wheel = 0f }
            }
        }
        runCatching { input.close() }
    }

    /** One report from the mouse: move, then buttons, then wheel. */
    private fun frame(dx: Int, dy: Int, wheel: Float, pressed: Int) {
        if (dx != 0 || dy != 0) {
            x = (x + dx).coerceIn(0f, w - 1f); y = (y + dy).coerceIn(0f, h - 1f)
            runCatching { cursor.moved(x, y, buttons != 0) }
            send(if (buttons != 0) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE)
        }
        if (locked) return
        if (pressed != buttons) {
            val was = buttons
            buttons = pressed
            runCatching { cursor.moved(x, y, pressed != 0) }
            if (was == 0) { onPress(); downAt = SystemClock.uptimeMillis(); send(MotionEvent.ACTION_DOWN) }
            (pressed and was.inv()).takeIf { it != 0 }?.let { send(MotionEvent.ACTION_BUTTON_PRESS, it) }
            (was and pressed.inv()).takeIf { it != 0 }?.let { send(MotionEvent.ACTION_BUTTON_RELEASE, it) }
            if (pressed == 0) send(MotionEvent.ACTION_UP)
        }
        if (wheel != 0f) send(MotionEvent.ACTION_SCROLL, scroll = wheel)
    }

    private fun send(action: Int, actionButton: Int = 0, scroll: Float = 0f) {
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply { this.x = this@DeskMouse.x; this.y = this@DeskMouse.y; pressure = if (buttons != 0) 1f else 0f
            if (scroll != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, scroll) }
        val down = if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_SCROLL) now else downAt
        val ev = MotionEvent.obtain(down, now, action, 1, arrayOf(props), arrayOf(coords), 0, buttons, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0)
        if (actionButton != 0) runCatching { MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType).invoke(ev, actionButton) }
        runCatching { setDisplay.invoke(ev, displayId); inject.invoke(im, ev, 0) }.onFailure { Log.w(TAG, "inject failed", it) }
        ev.recycle()
    }

    private fun readFully(input: FileInputStream, buf: ByteArray): Boolean {
        var n = 0
        while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) return false; n += r }
        return true
    }

    /** EVIOCGRAB: the system stops getting this device's events until it's closed. */
    private fun grab(input: FileInputStream) = runCatching {
        val os = Class.forName("libcore.io.Libcore").getField("os").get(null)!!
        os.javaClass.getMethod("ioctlInt", java.io.FileDescriptor::class.java, Int::class.javaPrimitiveType).invoke(os, input.fd, EVIOCGRAB)
    }.onFailure { Log.w(TAG, "couldn't grab the mouse; the system sees it too", it) }

    private fun buttonBit(code: Int) = when (code) {
        BTN_LEFT -> MotionEvent.BUTTON_PRIMARY
        BTN_RIGHT -> MotionEvent.BUTTON_SECONDARY
        BTN_MIDDLE -> MotionEvent.BUTTON_TERTIARY
        else -> 0
    }

    private companion object {
        const val TAG = "OpenCoverDesk"
        const val EVENT_SIZE = 24 // struct input_event on a 64-bit kernel: timeval (16), type, code, value
        const val EV_SYN = 0; const val EV_KEY = 1; const val EV_REL = 2
        const val REL_X = 0; const val REL_Y = 1; const val REL_WHEEL = 8
        const val BTN_LEFT = 0x110; const val BTN_RIGHT = 0x111; const val BTN_MIDDLE = 0x112
        const val EVIOCGRAB = 0x40044590

        /** The first input device that moves along X and Y (a mouse), as /dev/input/eventN. */
        fun findMouse(): String? = File("/sys/class/input").listFiles { f -> f.name.startsWith("event") }?.sortedBy { it.name }?.firstOrNull { ev ->
            val rel = runCatching { File(ev, "device/capabilities/rel").readText().trim().split(' ').last().toLong(16) }.getOrDefault(0L)
            rel and 3L == 3L
        }?.let { "/dev/input/${it.name}" }
    }
}
