package dev.opencover.desk

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/**
 * The desk's lock screen: while the phone is locked the monitor shows this over everything (the desk itself never
 * locks), and the mouse and keyboard stop reaching it. Unlocking the phone takes it away.
 */
class DeskLock(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var keys: View? = null

    /** Covers the monitor with [picture] behind the clock; [desk] (the desk display's context) gets the key catcher. */
    fun show(picture: android.graphics.Bitmap?, desk: Context?) {
        if (view != null) return
        desk?.let { catchKeys(it) }
        val root = FrameLayout(ctx).apply {
            picture?.let { addView(android.widget.ImageView(ctx).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; setImageBitmap(it) }) }
            addView(View(ctx).apply { background = ColorDrawable(0x99000000.toInt()) }) // dims the picture, not the text
            setBackgroundColor(Color.BLACK)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                // Sizes in pixels of the 1080p monitor, whatever density it reports.
                addView(TextClock(ctx).apply { format12Hour = "h:mm"; format24Hour = "H:mm"; px(180f); setTextColor(Color.WHITE) })
                addView(TextClock(ctx).apply { format12Hour = "EEEE, d MMMM"; format24Hour = "EEEE, d MMMM"; px(40f); setTextColor(Color.WHITE) })
                addView(TextView(ctx).apply {
                    text = "Unlock your phone to use the desktop"; px(30f); setTextColor(0xDDFFFFFF.toInt()); setPadding(0, 80, 0, 0)
                })
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        runCatching {
            wm.addView(root, WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.OPAQUE))
            view = root
        }
    }

    private fun TextView.px(size: Float) { setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size); gravity = Gravity.CENTER_HORIZONTAL }

    /** A tiny window on the desk that takes keyboard focus and keeps every key, so typing can't reach an app there. */
    private fun catchKeys(desk: Context) {
        val v = View(desk).apply { isFocusable = true; isFocusableInTouchMode = true; setOnKeyListener { _, _, _ -> true } }
        runCatching {
            desk.getSystemService(WindowManager::class.java).addView(v, WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT))
            v.requestFocus()
            keys = v
        }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        keys?.let { runCatching { it.context.getSystemService(WindowManager::class.java).removeView(it) } }
        view = null; keys = null
    }
}
