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

    /** The monitor, if one is plugged in: a presentation display that isn't the cover. */
    fun monitor(ctx: Context): Display? = ctx.getSystemService(DisplayManager::class.java)
        .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull { it.displayId != COVER_DISPLAY && it.displayId != Display.DEFAULT_DISPLAY }

    val running get() = view != null

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, DeskService::class.java.name))
            .daemon(false).processNameSuffix("desk").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            helper = IDesk.Stub.asInterface(binder)
            pending?.invoke(); pending = null
        }
        override fun onServiceDisconnected(name: ComponentName?) { helper = null }
    }

    /** Covers the monitor and shows the desk there. Main thread. Returns what happened, for a toast. */
    fun start(service: AccessibilityService, open: String? = "com.android.chrome"): String {
        if (view != null) return "Desk already running"
        if (!ShizukuShell.allowed()) return "Start Shizuku first"
        val monitor = monitor(service) ?: return "No external monitor found"
        val mode = monitor.mode
        val w = maxOf(mode.physicalWidth, mode.physicalHeight)
        val h = minOf(mode.physicalWidth, mode.physicalHeight)
        val wctx = service.createDisplayContext(monitor).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val sv = SurfaceView(wctx)
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
        return runCatching { wctx.getSystemService(WindowManager::class.java).addView(sv, lp); view = sv; "Desk on monitor ${monitor.displayId}" }
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
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
        view = null
        displayId = -1
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        helper = null
    }
}
