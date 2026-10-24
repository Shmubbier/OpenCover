package dev.opencover.desk

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.view.WindowManager

/**
 * The desk's open apps, on DeX's taskbar just after its navigation buttons. DeX lists running apps only for its own
 * workspaces, which Samsung never makes on a virtual display, so OpenCover draws them: click one to bring it to the
 * front (or back from minimized). A bar under each; brighter when it's showing.
 */
class DeskTaskbar(ctx: Context, displayId: Int) {
    private val wctx = ctx.createDisplayContext(ctx.getSystemService(DisplayManager::class.java).getDisplay(displayId))
        .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
    private val wm = wctx.getSystemService(WindowManager::class.java)
    private val row = LinearLayout(wctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("desk-taskbar").apply { start() }
    private val bg = Handler(thread.looper)
    private var shown = emptyList<Desk.Window>()
    private var added = false

    fun start() {
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, BAR_PX,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.START; x = START_PX }
        added = runCatching { wm.addView(row, lp) }.isSuccess
        bg.post(poll)
    }

    fun stop() {
        bg.removeCallbacksAndMessages(null)
        thread.quitSafely()
        main.post { if (added) runCatching { wm.removeView(row) }; added = false }
    }

    // ponytail: polls the window list each second; a task-stack listener if this ever shows up in battery use.
    private val poll = object : Runnable {
        override fun run() {
            // DeX hides its taskbar while the phone is locked; so does this.
            val locked = wctx.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
            val now = if (locked) emptyList() else Desk.windows().filter { it.pkg != wctx.packageName }.sortedBy { it.task } // launch order, as a taskbar keeps it
            if (now != shown) main.post { show(now) }
            bg.postDelayed(this, 1_000)
        }
    }

    private fun show(windows: List<Desk.Window>) {
        shown = windows
        row.removeAllViews()
        val pm = wctx.packageManager
        windows.forEach { w ->
            row.addView(FrameLayout(wctx).apply {
                addView(ImageView(wctx).apply {
                    runCatching { setImageDrawable(pm.getApplicationIcon(w.pkg)) }
                    contentDescription = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(w.pkg, 0)) }.getOrNull()
                }, FrameLayout.LayoutParams(ICON_PX, ICON_PX, Gravity.CENTER))
                addView(View(wctx).apply {
                    background = GradientDrawable().apply { cornerRadius = 2f; setColor(if (w.visible) Color.WHITE else Color.GRAY) }
                }, FrameLayout.LayoutParams(if (w.visible) 16 else 6, 3, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 4 })
                setOnClickListener { Desk.show(w.task) }
            }, LinearLayout.LayoutParams(CELL_PX, BAR_PX))
        }
    }

    private companion object {
        const val BAR_PX = 56 // DeX's taskbar at the desk's density
        const val CELL_PX = 54
        const val ICON_PX = 36
        const val START_PX = 200 // after DeX's Recents, Home and Back
    }
}
