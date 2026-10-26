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
    private var pointer: DeskPointer? = null
    private var taskbar: DeskTaskbar? = null
    private const val HELPER_VERSION = 17
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    // The helper says where the mouse is; the cursor is drawn here, over the desk (the system draws none there).
    private val cursorListener = object : IDeskCursor.Stub() {
        override fun moved(x: Float, y: Float, down: Boolean) = main.post { pointer?.moved(x, y, down) }.let { }
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
    /** The cover goes black while the desk runs (on unless turned off). */
    fun dimCover(ctx: Context) = ctx.getSharedPreferences("desk", Context.MODE_PRIVATE).getBoolean("dim_cover", true)
    fun setDimCover(ctx: Context, on: Boolean) = ctx.getSharedPreferences("desk", Context.MODE_PRIVATE).edit().putBoolean("dim_cover", on).apply()
    private var dim: CoverDim? = null
    /** OpenCover's background behind the desk (on unless turned off). */
    fun wallpaper(ctx: Context) = ctx.getSharedPreferences("desk", Context.MODE_PRIVATE).getBoolean("wallpaper", true)
    fun setWallpaper(ctx: Context, on: Boolean) = ctx.getSharedPreferences("desk", Context.MODE_PRIVATE).edit().putBoolean("wallpaper", on).apply()
    val coverDark get() = dim?.dark == true

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
        DeskBackground.setAspect(service, w.toFloat() / h) // the adjust screen frames this shape
        val wctx = service.createDisplayContext(monitor).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val sv = SurfaceView(wctx)
        val frame = android.widget.FrameLayout(wctx)
        frame.addView(sv)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                size = w to h
                // OpenCover draws the desk onto the monitor (an animated background goes where the desk shows its key colour).
                val c = DeskCompositor(holder.surface, w, h)
                val target = c.input?.also { compositor = c; showVideo(service, w, h) } ?: holder.surface
                withHelper { make(service, target, w, h, open) }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                runCatching { helper?.release() }; displayId = -1
                compositor?.release(); compositor = null
            }
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
            if (dimCover(service)) dim = CoverDim(service).also { it.start() }
            // The cursor in a small window of its own above the desk (the desk's picture would cover a view in its window).
            val arrow = android.widget.ImageView(wctx)
            wm.addView(arrow, WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START; x = w / 2; y = h / 2 })
            cursorView = arrow
            // The system's pointer images at the desk's density.
            lock = DeskLock(wctx)
            this.service = service
            pointer = DeskPointer(arrow, wctx.createConfigurationContext(android.content.res.Configuration().apply { densityDpi = DPI }))
            monitorId = monitor.displayId
            // An animated own background whose video isn't made yet (it takes a few seconds): make it, then show it.
            if (wallpaper(service) && DeskBackground.hasOwn(service) && DeskBackground.video(service) == null) Thread {
                DeskBackground.makeVideo(service)
                if (DeskBackground.video(service) != null) main.post { backgroundChanged() }
            }.apply { priority = Thread.MIN_PRIORITY }.start()
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

    private fun make(ctx: Context, surface: Surface, w: Int, h: Int, open: String?) {
        // Before the display exists, so it starts with it.
        useOurWallpaper(ctx, w, h)
        val id = runCatching { helper?.create(surface, w, h, DPI) }.getOrNull() ?: -1
        displayId = id
        Log.i(TAG, "desk display $id (${w}x$h)")
        if (id >= 0) {
            val ok = runCatching { helper?.startMouse(id, w, h, cursorListener) }.getOrNull() == true
            Log.i(TAG, if (ok) "mouse on the desk" else "no mouse found")
            taskbar?.stop()
            taskbar = DeskTaskbar(ctx, id).also { it.start() }
            // Gaming mode on plug-in: the game app once the desktop has settled.
            if (DeskGame.auto(ctx) && DeskGame.app(ctx) != null) main.postDelayed({ if (displayId == id) Log.i(TAG, DeskGame.play(ctx)) }, 3_000)
        }
        if (id < 0 || open == null) return
        Thread {
            val out = ShizukuShell.output("sh", "-c",
                "am start --display $id -f 0x18000000 \$(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $open | tail -1) 2>&1")
            Log.i(TAG, "open $open on $id: $out")
        }.start()
    }

    /**
     * Samsung shows the main screen's home wallpaper behind the desk, but only its own wallpapers (another app's live
     * wallpaper isn't allowed there, and its Fold one draws black). So while the desk runs, the home wallpaper becomes
     * the desk's background ([DeskBackground]) as a picture cropped as chosen; for an animated one, the solid colour
     * [DeskCompositor] swaps for the video (Samsung plays video wallpapers on the lock screen only). The live wallpaper before is put back in [stop]; a picture someone set is left alone,
     * as it couldn't be put back.
     */
    private fun useOurWallpaper(ctx: Context, w: Int, h: Int) {
        background = DeskBackground.picture(ctx, w, h) ?: return // decoded either way, for the lock screen
        if (!wallpaper(ctx)) return restoreWallpaper(ctx)
        val prefs = ctx.getSharedPreferences("desk", Context.MODE_PRIVATE)
        // Read by the helper: apps aren't told which live wallpaper is set.
        val now = runCatching { helper?.wallpaper(MAIN_HOME) }.getOrNull() ?: return
        val saved = prefs.getString("previous_wallpaper", null)
        if (saved == null && now == IMAGE_WALLPAPER) return
        if (saved == null) prefs.edit().putString("previous_wallpaper", now).apply() // else a crash left ours: keep the saved one
        // Animated: the wallpaper is the key colour the compositor swaps for the video.
        val picture = if (compositor?.animated == true) android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(DeskCompositor.KEY) } else background
        runCatching { android.app.WallpaperManager.getInstance(ctx).setBitmap(picture, null, false, MAIN_HOME) }
            .onSuccess { Log.i(TAG, "desk background set (was $now)") }
            .onFailure { Log.w(TAG, "couldn't set the desk background", it) }
    }

    private var compositor: DeskCompositor? = null

    /** The animated background's video and the part of it shown (fractions), or null for a still one. */
    private fun deskVideo(ctx: Context, w: Int, h: Int): Pair<java.io.File, android.graphics.RectF>? {
        if (!wallpaper(ctx) || DeskGame.playing != null) return null // gaming mode keeps the GPU for the game
        val video = DeskBackground.video(ctx) ?: return null
        val c = runCatching {
            android.media.MediaMetadataRetriever().use { m ->
                m.setDataSource(video.path)
                val vw = m.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
                val vh = m.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
                DeskBackground.fitted(ctx, vw, vh, w, h)
            }
        }.getOrNull() ?: return null
        return video to android.graphics.RectF(c.x, c.y, c.x + c.w, c.y + c.h)
    }

    /** The desk's background changed: start the desk again so the monitor shows it. */
    fun backgroundChanged() {
        val ctx = service ?: return
        val (w, h) = size
        showVideo(ctx, w, h)
        useOurWallpaper(ctx, w, h)
    }

    /** The compositor shows the animated background's video, or none (a still background, gaming mode). */
    private fun showVideo(ctx: Context, w: Int, h: Int) {
        val c = compositor ?: return
        val video = deskVideo(ctx, w, h)
        Log.i(TAG, "desk background from ${DeskBackground.source(ctx)?.name}, video ${video?.first?.name}")
        c.setVideo(video?.first, video?.second ?: android.graphics.RectF(0f, 0f, 1f, 1f))
    }

    private var size = 0 to 0

    /** The desk's size in pixels (the monitor's). */
    val deskSize get() = size

    /** The desk becomes the focused display. */
    fun focusDesk() { runCatching { helper?.focusDisplay(displayId) } }

    /** The cover rests now, if it rests while the desk runs. */
    fun restCover() { dim?.rest() }

    fun post(block: () -> Unit) { main.post(block) }

    private fun restoreWallpaper(ctx: Context) {
        val prefs = ctx.getSharedPreferences("desk", Context.MODE_PRIVATE)
        val previous = prefs.getString("previous_wallpaper", null) ?: return
        if (runCatching { helper?.setWallpaper(previous, MAIN_HOME) != null }.getOrDefault(false)) prefs.edit().remove("previous_wallpaper").apply()
    }

    /** WallpaperManager's home (1) with Samsung's main-screen flag (4); 16 would be the cover's. */
    private const val MAIN_HOME = 5
    private const val IMAGE_WALLPAPER = "com.android.systemui/com.android.systemui.wallpapers.ImageWallpaper"

    fun stop() {
        val v = view ?: return
        restoreWallpaper(v.context)
        DeskGame.leave(background = false)
        taskbar?.stop(); taskbar = null
        dim?.stop(); dim = null
        lock?.hide(); lock = null; locked = false; background = null; service = null
        runCatching { helper?.release() }
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(root ?: v) }
        cursorView?.let { c -> runCatching { c.context.getSystemService(WindowManager::class.java).removeView(c) } }
        root = null; cursorView = null; pointer = null
        view = null
        displayId = -1
        monitorId = -1
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        helper = null
        dev.opencover.watcher.CoverAccessibility.instance?.refreshScope()
    }

    private var lock: DeskLock? = null
    private var locked = false

    /** The phone locked or unlocked (the taskbar's poll): the lock screen covers the monitor, the desk takes no input. */
    fun lockChanged(now: Boolean) {
        if (now == locked) return
        locked = now
        runCatching { helper?.setLocked(now) }
        main.post {
            val l = lock ?: return@post
            if (now) l.show(background, deskContext()) else l.hide()
            cursorView?.visibility = if (now) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    private var service: AccessibilityService? = null

    /** A window context on the desk display, for OpenCover's windows there. */
    private fun deskContext(): Context? {
        val s = service ?: return null
        val d = s.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return null
        return s.createDisplayContext(d).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
    }

    /** OpenCover's background fitted to the monitor, for the lock screen; set with the desk's wallpaper. */
    private var background: android.graphics.Bitmap? = null

    /** The desk's windows changed (the taskbar's poll): the pointer resizes on their edges. */
    fun windowsChanged(windows: List<Window>) { pointer?.windows = windows }

    /**
     * The mouse went over a view on the desk (the app's hover event): the text beam over a text field. Only the view's
     * type is looked at, never what it shows.
     */
    fun onHover(event: android.view.accessibility.AccessibilityEvent) {
        if (displayId < 0 || event.displayId != displayId) return
        val cls = event.className?.toString().orEmpty()
        pointer?.overText = cls.endsWith("EditText") || cls.endsWith("AutoCompleteTextView")
    }

    /** An app window on the desk. */
    data class Window(val task: Int, val pkg: String, val visible: Boolean, val starting: Boolean = false,
                      val bounds: android.graphics.Rect = android.graphics.Rect())

    /** The desk's app windows, front first, minimized ones included. */
    fun windows(): List<Window> = runCatching { helper?.tasks(displayId) }.getOrNull().orEmpty().mapNotNull { line ->
        val f = line.split(' ').takeIf { it.size == 8 } ?: return@mapNotNull null
        Window(f[0].toInt(), f[1], f[2].toBoolean(), f[3].toBoolean(), android.graphics.Rect(f[4].toInt(), f[5].toInt(), f[6].toInt(), f[7].toInt()))
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
