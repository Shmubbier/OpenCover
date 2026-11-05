package dev.opencover.desk

import android.content.Context
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.PointerIcon
import android.view.WindowManager
import android.widget.ImageView

/**
 * The desk's mouse pointer, drawn by OpenCover in its own small window over the desk (Samsung only draws a pointer on
 * the cover or on its own DeX display). It uses the system's pointer images and picks one the way a desktop does:
 * the spinner while an app is starting, resize arrows on a window's edges, the text beam over a text field, else
 * the arrow. Main thread only.
 */
class DeskPointer(private val view: ImageView, iconContext: Context) {
    private class Icon(val drawable: Drawable, val hotX: Float, val hotY: Float)

    private val icons = NAMES.keys.associateWith { load(iconContext, it) }
    private var type = 0
    private var x = 0f
    private var y = 0f
    private var down = false

    /** The desk's windows, front first; kept fresh by the taskbar's poll. */
    @Volatile var windows: List<Desk.Window> = emptyList()
        set(value) { field = value; view.post { update() } }

    /** The mouse is over a text field (from the app's hover event). */
    var overText = false
        set(value) { field = value; update() }

    fun moved(x: Float, y: Float, down: Boolean) {
        this.x = x; this.y = y; this.down = down
        update()
    }

    private fun update() {
        val was = type
        if (!down) show(pick()) // a drag keeps the shape it started with, as a resize does
        val icon = icons[type] ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        val nx = (x - icon.hotX).toInt(); val ny = (y - icon.hotY).toInt()
        if (nx == lp.x && ny == lp.y && type == was) return // the window list changed but not the pointer: nothing to move
        lp.x = nx; lp.y = ny
        runCatching { view.context.getSystemService(WindowManager::class.java).updateViewLayout(view, lp) }
    }

    private fun pick(): Int {
        if (windows.any { it.starting }) return PointerIcon.TYPE_WAIT
        resizeAt()?.let { return it }
        return if (overText) PointerIcon.TYPE_TEXT else PointerIcon.TYPE_ARROW
    }

    /** The resize arrow for a window edge under the pointer; null over a window's inside or the desktop. */
    private fun resizeAt(): Int? {
        for (w in windows) {
            val b = w.bounds
            if (!w.visible || b.isEmpty) continue
            if (x < b.left - EDGE_OUT || x > b.right + EDGE_OUT || y < b.top - EDGE_OUT || y > b.bottom + EDGE_OUT) continue
            val l = x < b.left + EDGE_IN; val r = x > b.right - EDGE_IN
            val t = y < b.top + EDGE_IN; val bt = y > b.bottom - EDGE_IN
            return when {
                (l && t) || (r && bt) -> PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW
                (r && t) || (l && bt) -> PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW
                l || r -> PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
                t || bt -> PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
                else -> null // inside the front window: it owns the pointer
            }
        }
        return null
    }

    private fun show(t: Int) {
        if (t == type) return
        val icon = icons[t] ?: icons[PointerIcon.TYPE_ARROW] ?: return
        type = t
        (view.drawable as? AnimationDrawable)?.stop()
        view.setImageDrawable(icon.drawable)
        (icon.drawable as? AnimationDrawable)?.start()
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        lp.width = icon.drawable.intrinsicWidth; lp.height = icon.drawable.intrinsicHeight
    }

    private companion object {
        const val TAG = "OpenCoverDesk"
        const val EDGE_OUT = 10 // px outside a window's edge that still resize it
        const val EDGE_IN = 4

        /** The system's resource for each pointer, as its styles name them. */
        val NAMES = mapOf(
            PointerIcon.TYPE_ARROW to "pointer_arrow", PointerIcon.TYPE_WAIT to "pointer_wait", PointerIcon.TYPE_TEXT to "pointer_text",
            PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW to "pointer_horizontal_double_arrow",
            PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW to "pointer_vertical_double_arrow",
            PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW to "pointer_top_left_diagonal_double_arrow",
            PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW to "pointer_top_right_diagonal_double_arrow",
        )

        /**
         * A system pointer image with its hot spot, read from the system's own pointer-icon resource (its loader is
         * hidden from apps): <pointer-icon android:bitmap android:hotSpotX android:hotSpotY>.
         */
        fun load(ctx: Context, type: Int): Icon? = runCatching {
            val res = ctx.resources
            val id = res.getIdentifier("${NAMES.getValue(type)}_icon", "drawable", "android")
            val xml = res.getXml(id)
            while (xml.next() != org.xmlpull.v1.XmlPullParser.START_TAG) Unit
            val attrs = intArrayOf(android.R.attr.bitmap, android.R.attr.hotSpotX, android.R.attr.hotSpotY) // ascending ids, as obtainAttributes needs
            val a = res.obtainAttributes(android.util.Xml.asAttributeSet(xml), attrs)
            // The vector pointers take their colours from the pointer style; the system's default is a black fill, white edge.
            val theme = res.newTheme().apply {
                listOf("PointerIconVectorStyleFillBlack", "PointerIconVectorStyleStrokeWhite").forEach { style ->
                    res.getIdentifier(style, "style", "android").takeIf { it != 0 }?.let { applyStyle(it, true) }
                }
            }
            val drawable = res.getDrawable(a.getResourceId(0, 0), theme)
            Icon(drawable, a.getDimension(1, 0f), a.getDimension(2, 0f)).also { a.recycle(); xml.close() }
        }.onFailure { Log.w(TAG, "no system pointer $type", it) }.getOrNull()
    }
}
