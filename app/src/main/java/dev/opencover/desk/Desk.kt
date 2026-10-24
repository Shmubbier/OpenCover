package dev.opencover.desk

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import dev.opencover.BuildConfig
import dev.opencover.COVER_DISPLAY
import dev.opencover.quick.ShizukuShell
import rikka.shizuku.Shizuku

/**
 * A desktop on an external monitor (proof of concept). Samsung only mirrors the phone on a USB-C monitor, and the
 * monitor can't host apps. OpenCover covers it with its own window, and a trusted display made by [DeskService]
 * (through Shizuku) draws into that window: Samsung starts its DeX desktop there, and apps opened on it run on the
 * monitor while the phone shows something else.
 */
object Desk {
    private const val TAG = "OpenCoverDesk"
    private const val DPI = 160 // a desktop-like density on a 1080p monitor
    private var view: SurfaceView? = null
    private var helper: IDesk? = null
    private var pending: (() -> Unit)? = null // run once the helper is bound
    var displayId = -1; private set
    private var root: android.view.View? = null
    private var cursorView: android.view.View? = null
    private const val POINTER_PX = 36
    private const val HELPER_VERSION = 4
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    // The helper says where the mouse is; the cursor is drawn here, over the desk (the system draws none there).
    private val cursorListener = object : IDeskCursor.Stub() {
        override fun moved(x: Float, y: Float) = main.post {
            val v = cursorView ?: return@post
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: return@post
            lp.x = x.toInt(); lp.y = y.toInt()
            runCatching { v.context.getSystemService(WindowManager::class.java).updateViewLayout(v, lp) }
        }.let { }
    }

    /** The monitor, if one is plugged in: a presentation display that isn't the cover. */
    fun monitor(ctx: Context): Display? = ctx.getSystemService(DisplayManager::class.java)
        .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull { it.displayId != COVER_DISPLAY && it.displayId != Display.DEFAULT_DISPLAY }

    val running get() = view != null

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, DeskService::class.java.name))
            // A running helper of another version is replaced: bump HELPER_VERSION whenever IDesk or DeskService changes.
            .daemon(false).processNameSuffix("desk").debuggable(BuildConfig.DEBUG).version(HELPER_VERSION)
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            helper = IDesk.Stub.asInterface(binder)
            pending?.invoke(); pending = null
        }
        override fun onServiceDisconnected(name: ComponentName?) { helper = null }
    }

    fun wanted(ctx: Context) = ctx.getSharedPreferences("desk", Context.MODE_PRIVATE).getBoolean("on", false)
    fun setWanted(ctx: Context, on: Boolean) = ctx.getSharedPreferences("desk", Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()

    /** The monitor the desk is on, -1 when it isn't running. */
    var monitorId = -1; private set

    /** A display came or went: the desk starts on a monitor plugged in, and stops when it's unplugged. Main thread. */
    fun displaysChanged(service: AccessibilityService) {
        val monitor = monitor(service)
        if (running && (monitor == null || monitor.displayId != monitorId)) stop()
        if (!running && monitor != null && wanted(service) && ShizukuShell.allowed()) Log.i(TAG, start(service))
    }

    /** Covers the monitor and shows the desk there (Samsung's DeX desktop), opening [open] on it if given. */
    fun start(service: AccessibilityService, open: String? = null): String {
        if (view != null) return "Desk already running"
        if (!ShizukuShell.allowed()) return "Start Shizuku first"
        val monitor = monitor(service) ?: return "No external monitor found"
        val mode = monitor.mode
        val w = maxOf(mode.physicalWidth, mode.physicalHeight)
        val h = minOf(mode.physicalWidth, mode.physicalHeight)
        val wctx = service.createDisplayContext(monitor).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val sv = SurfaceView(wctx)
        val frame = android.widget.FrameLayout(wctx)
        frame.addView(sv)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = withHelper { make(holder.surface, w, h, open) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { runCatching { helper?.release() }; displayId = -1 }
        })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE,
        )
        return runCatching {
            val wm = wctx.getSystemService(WindowManager::class.java)
            wm.addView(frame, lp); view = sv; root = frame
            // The cursor in a small window of its own above the desk (the desk's picture would cover a view in its window).
            val pointer = android.widget.ImageView(wctx).apply { setImageDrawable(PointerDrawable()) }
            wm.addView(pointer, WindowManager.LayoutParams(POINTER_PX, POINTER_PX, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START; x = w / 2; y = h / 2 })
            cursorView = pointer
            monitorId = monitor.displayId
            (service as? dev.opencover.watcher.CoverAccessibility)?.refreshScope() // hear the taskbar's Recents button
            "Desk on monitor ${monitor.displayId}"
        }
            .getOrElse { Log.w(TAG, "couldn't cover the monitor", it); "Couldn't cover the monitor: ${it.message}" }
    }

    private fun withHelper(block: () -> Unit) {
        if (helper != null) return block()
        pending = block
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure { Log.w(TAG, "couldn't start the desk helper", it) }
    }

    private fun make(surface: Surface, w: Int, h: Int, open: String?) {
        val id = runCatching { helper?.create(surface, w, h, DPI) }.getOrNull() ?: -1
        displayId = id
        Log.i(TAG, "desk display $id (${w}x$h)")
        if (id >= 0) {
            val ok = runCatching { helper?.startMouse(id, w, h, cursorListener) }.getOrNull() == true
            Log.i(TAG, if (ok) "mouse on the desk" else "no mouse found")
        }
        if (id < 0 || open == null) return
        Thread {
            val out = ShizukuShell.output("sh", "-c",
                "am start --display $id -f 0x18000000 \$(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $open | tail -1) 2>&1")
            Log.i(TAG, "open $open on $id: $out")
        }.start()
    }

    fun stop() {
        val v = view ?: return
        runCatching { helper?.release() }
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(root ?: v) }
        cursorView?.let { c -> runCatching { c.context.getSystemService(WindowManager::class.java).removeView(c) } }
        root = null; cursorView = null
        view = null
        displayId = -1
        monitorId = -1
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        helper = null
        dev.opencover.watcher.CoverAccessibility.instance?.refreshScope()
    }

    /** An app window on the desk. */
    data class Window(val task: Int, val pkg: String, val visible: Boolean)

    /** The desk's app windows, front first, minimized ones included. */
    fun windows(): List<Window> = runCatching { helper?.tasks(displayId) }.getOrNull().orEmpty().mapNotNull { line ->
        line.split(' ').takeIf { it.size == 3 }?.let { (id, pkg, vis) -> Window(id.toInt(), pkg, vis.toBoolean()) }
    }

    fun show(task: Int) { runCatching { helper?.showTask(task, displayId) } }
    fun close(task: Int) { runCatching { helper?.closeTask(task) } }

    /**
     * DeX's taskbar Recents button sends the Recents key, which the folded phone ignores (and would show on the phone,
     * not here), so OpenCover answers the button's click with its own switcher on the desk.
     * ponytail: matched by its English label; match the launcher's own string if other languages need it.
     */
    fun onClick(service: Context, event: android.view.accessibility.AccessibilityEvent) {
        if (displayId < 0 || event.displayId != displayId || event.packageName != "com.sec.android.app.launcher") return
        if (event.contentDescription?.toString() != "Recents") return
        // Apps' windows on the desk are freeform and always on top, so Recents is a freeform window too, over the whole desk.
        val size = service.getSystemService(DisplayManager::class.java).getDisplay(displayId)?.mode ?: return
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            .setLaunchBounds(android.graphics.Rect(0, 0, size.physicalWidth, size.physicalHeight))
        runCatching {
            service.startActivity(android.content.Intent(service, DeskRecentsActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION), options.toBundle())
        }.onFailure { Log.w(TAG, "couldn't open the desk's recents", it) }
    }
}

/** An arrow pointer: white with a black edge, its tip at the top left. */
private class PointerDrawable : android.graphics.drawable.Drawable() {
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK; style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f; strokeJoin = android.graphics.Paint.Join.ROUND
    }
    override fun draw(c: android.graphics.Canvas) {
        val s = bounds.width() / 36f
        val p = android.graphics.Path().apply {
            moveTo(2f * s, 2f * s); lineTo(2f * s, 28f * s); lineTo(9f * s, 21f * s); lineTo(14f * s, 32f * s)
            lineTo(19f * s, 30f * s); lineTo(14f * s, 19f * s); lineTo(24f * s, 19f * s); close()
        }
        c.drawPath(p, fill); c.drawPath(p, edge)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
