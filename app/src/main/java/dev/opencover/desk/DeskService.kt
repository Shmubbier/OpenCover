package dev.opencover.desk

import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Surface
import kotlin.system.exitProcess

/**
 * Runs as the shell user through Shizuku (a Shizuku user service), so the display it makes can be trusted: Samsung
 * lets apps run on it and starts its DeX desktop there, which it refuses for a display an app makes. The display draws
 * into the surface OpenCover shows on the monitor.
 */
class DeskService(context: Context) : IDesk.Stub() {
    // Display calls check the caller's package against its user id: present as the shell, which this process is.
    private val shell = object : ContextWrapper(context) {
        override fun getPackageName() = "com.android.shell"
        override fun getOpPackageName() = "com.android.shell"
    }
    private var display: VirtualDisplay? = null

    override fun create(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        release()
        return runCatching {
            // The public constructor isn't exposed; DisplayManager(Context) is.
            val dm = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(shell)
            display = dm.createVirtualDisplay("OpenCover Desk", width, height, dpi, surface, FLAGS)
            display!!.display.displayId.also { id ->
                // Freeform: apps open as windows that move, resize and minimize, as on DeX.
                runCatching { Runtime.getRuntime().exec(arrayOf("cmd", "window", "set-display-windowing-mode", "-d", "$id", "5")).waitFor() }
            }
        }.getOrElse { Log.w("OpenCoverDesk", "couldn't make the desk display", it); -1 }
    }

    private val atm by lazy { Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!! }

    override fun tasks(displayId: Int): Array<String> = runCatching {
        // IActivityTaskManager.getTasks(maxNum, filterOnlyVisibleRecents, keepIntentExtra, displayId), front first.
        val get = atm.javaClass.methods.first { it.name == "getTasks" && it.parameterTypes.size == 4 }
        @Suppress("UNCHECKED_CAST")
        (get.invoke(atm, 50, false, false, displayId) as List<android.app.ActivityManager.RunningTaskInfo>)
            .filter { t -> runCatching { t.javaClass.getMethod("getActivityType").invoke(t) == 1 }.getOrDefault(true) } // standard apps, not the desktop
            .mapNotNull { t -> t.baseActivity?.let { "${t.taskId} ${it.packageName} ${t.isVisible} ${starting(t)} ${bounds(t)}" } }
            .toTypedArray()
    }.getOrElse { Log.w("OpenCoverDesk", "couldn't list the desk's windows", it); emptyArray() }

    /** Asked to show but not drawn yet: the app is still starting. */
    private fun starting(t: android.app.ActivityManager.RunningTaskInfo) =
        runCatching { t.javaClass.getMethod("isVisibleRequested").invoke(t) == true && !t.isVisible }.getOrDefault(false)

    /** Where the window is on the desk, "left top right bottom". */
    private fun bounds(t: android.app.ActivityManager.RunningTaskInfo): String = runCatching {
        val config = t.javaClass.getField("configuration").get(t) as android.content.res.Configuration
        val wc = config.javaClass.getField("windowConfiguration").get(config)!!
        val r = wc.javaClass.getMethod("getBounds").invoke(wc) as android.graphics.Rect
        "${r.left} ${r.top} ${r.right} ${r.bottom}"
    }.getOrDefault("0 0 0 0")

    override fun showTask(taskId: Int, displayId: Int) {
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        runCatching { atm.javaClass.getMethod("startActivityFromRecents", Int::class.javaPrimitiveType, android.os.Bundle::class.java).invoke(atm, taskId, options) }
            .onFailure { Log.w("OpenCoverDesk", "couldn't show task $taskId", it) }
    }

    // IWallpaperManager straight, so the calls carry the shell's package (WallpaperManager would send OpenCover's).
    private val wallpapers by lazy {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "wallpaper") as android.os.IBinder
        Class.forName("android.app.IWallpaperManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
    }

    override fun wallpaper(which: Int): String? = runCatching {
        val info = wallpapers.javaClass.getMethod("getWallpaperInfoWithFlags", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(wallpapers, which, 0) as android.app.WallpaperInfo?
        info?.component?.flattenToString() ?: IMAGE_WALLPAPER // no live wallpaper: the still one
    }.onFailure { Log.w("OpenCoverDesk", "couldn't read the wallpaper", it) }.getOrNull()

    override fun setWallpaper(component: String, which: Int) {
        val cn = android.content.ComponentName.unflattenFromString(component) ?: return
        runCatching {
            val builder = Class.forName("android.app.wallpaper.WallpaperDescription\$Builder").getConstructor().newInstance()
            builder.javaClass.getMethod("setComponent", android.content.ComponentName::class.java).invoke(builder, cn)
            val desc = builder.javaClass.getMethod("build").invoke(builder)!!
            wallpapers.javaClass.getMethod("setWallpaperComponentChecked", desc.javaClass, String::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, android.os.Bundle::class.java)
                .invoke(wallpapers, desc, "com.android.shell", which, 0, null)
        }.onFailure { Log.w("OpenCoverDesk", "couldn't set the wallpaper to $component", it) }
    }

    override fun setVideoWallpaper(video: android.os.ParcelFileDescriptor, crop: android.graphics.Rect, which: Int): Boolean = runCatching {
        // Samsung's video wallpaper plays a file by path, so the video goes where its player can read it.
        val dir = java.io.File(VIDEO_DIR).apply { mkdirs() }
        val out = java.io.File(dir, "desk.mp4")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(video).use { input -> out.outputStream().use { input.copyTo(it) } }
        out.setReadable(true, false)
        val extras = android.os.Bundle().apply { putParcelable("videoCropHint", crop) }
        // IWallpaperManager.setVideoWallpaper(path, themePackage, fileName, callingPackage, which, userId, allowBackup,
        // extras): Samsung's, for platform-signed callers, which the shell is.
        wallpapers.javaClass.getMethod("setVideoWallpaper", String::class.java, String::class.java, String::class.java, String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, android.os.Bundle::class.java)
            .invoke(wallpapers, out.path, "", out.name, "com.android.shell", which, 0, false, extras)
        true
    }.onFailure { Log.w("OpenCoverDesk", "couldn't set the video wallpaper", it) }.getOrDefault(false)

    override fun closeTask(taskId: Int) {
        runCatching { atm.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(atm, taskId) }
            .onFailure { Log.w("OpenCoverDesk", "couldn't close task $taskId", it) }
    }

    override fun release() {
        stopMouse()
        display?.release()
        display = null
    }

    private var mouse: DeskMouse? = null

    override fun startMouse(displayId: Int, width: Int, height: Int, cursor: IDeskCursor): Boolean {
        stopMouse()
        val m = DeskMouse(displayId, width, height, cursor) { runCatching { focus(displayId) } }
        if (!m.start()) return false
        mouse = m
        deskId = displayId
        // The keyboard follows the focused display: make it the desk.
        runCatching { focus(displayId) }
        return true
    }

    private var deskId = -1

    override fun setLocked(locked: Boolean) {
        mouse?.locked = locked
        if (!locked) runCatching { focus(deskId) } // typing goes back to the desk's apps
    }

    override fun stopMouse() {
        mouse?.stop()
        mouse = null
    }

    /** IActivityTaskManager.focusTopTask(displayId): the desk becomes the focused display, so typing goes there. */
    private fun focus(displayId: Int) {
        atm.javaClass.getMethod("focusTopTask", Int::class.javaPrimitiveType).invoke(atm, displayId)
    }

    override fun destroy() {
        release()
        exitProcess(0)
    }

    private companion object {
        const val VIDEO_DIR = "/sdcard/OpenCover"
        const val IMAGE_WALLPAPER = "com.android.systemui/com.android.systemui.wallpapers.ImageWallpaper"
        // DisplayManager.VIRTUAL_DISPLAY_FLAG_*: public, own content only, touch, content destroyed on removal, system
        // decorations (a launcher and taskbar), trusted, own display group, always unlocked, own focus.
        const val FLAGS = 1 or 8 or 64 or 256 or 512 or 1024 or 2048 or 4096 or 16384
    }
}
