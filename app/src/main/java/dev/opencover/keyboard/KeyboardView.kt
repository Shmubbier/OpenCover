package dev.opencover.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** The whole keyboard in one View: suggestion strip, keys and swipe trail. */
@SuppressLint("ViewConstructor")
class KeyboardView(ctx: Context, private val listener: Listener) : View(ctx) {
    interface Listener {
        fun onText(s: String)
        fun onBackspace()
        fun onDeleteWord()
        fun onCursor(by: Int)
        fun onEnter()
        fun onPaste()
        fun onSuggestion(index: Int)
        fun onSwipe(trail: List<Pt>, centers: Map<Char, Pt>, keyWidth: Float, caps: Boolean)
        fun onHideSuggestions()
    }

    var suggestions: List<String> = emptyList()
        set(v) { field = v; invalidate() }
    var showSuggestions = true
        set(v) { if (field != v) { field = v; requestLayout(); layoutKeys(); invalidate() } }

    private enum class Shift { OFF, ONCE, LOCK }
    private var shift = Shift.OFF
    private var page = Layouts.letters

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val stripH get() = if (showSuggestions) dp(24f) else 0f
    private val rowH = dp(34f)
    private val gap = dp(20f) // lifts the bottom row above the camera cutout
    private val keysTop get() = stripH

    private val bg = Paint().apply { color = Color.rgb(24, 24, 24) }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(58, 58, 58) }
    private val modPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 40) }
    private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(96, 96, 96) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textAlign = Paint.Align.CENTER; textSize = dp(9f) }
    private val popupBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(80, 80, 80) }
    private val popupSel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 130, 220) }
    private val suggest = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = dp(14f) }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 120, 170, 255); style = Paint.Style.STROKE; strokeWidth = dp(5f)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private var keys: List<Pair<Key, RectF>> = emptyList()

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), (stripH + rowH * page.size + gap).toInt())

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = layoutKeys()

    private fun layoutKeys() {
        val unit = width / 10f
        val pad = dp(2.5f)
        keys = page.flatMapIndexed { r, row ->
            var x = (10f - row.sumOf { it.width.toDouble() }.toFloat()) / 2 * unit
            val top = keysTop + r * rowH
            row.map { k -> (k to RectF(x + pad, top + pad, x + k.width * unit - pad, top + rowH - pad)).also { x += k.width * unit } }
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        if (showSuggestions) {
            val cell = width / 3f
            suggestions.take(3).forEachIndexed { i, s -> c.drawText(s, cell * i + cell / 2, stripH - dp(7f), suggest) }
        }
        keys.forEach { (k, r) ->
            c.drawRoundRect(r, dp(6f), dp(6f), when {
                k === pressed?.first -> downPaint
                k.kind == KeyKind.CHAR -> keyPaint
                else -> modPaint
            })
            // The long-press alternate, small in the corner.
            k.popup.firstOrNull()?.takeIf { k.label != "." }?.let { c.drawText(it, r.right - dp(6f), r.top + dp(11f), hint) }
            val text = labelOf(k)
            label.textSize = if (text.length > 3) dp(12f) else dp(17f)
            c.drawText(text, r.centerX(), r.centerY() + label.textSize / 3, label)
        }
        if (popupCells.isNotEmpty()) {
            val all = RectF(popupCells.first()).apply { popupCells.forEach { union(it) } }
            c.drawRoundRect(all, dp(8f), dp(8f), popupBg)
            popupCells.forEachIndexed { i, r ->
                if (i == popupIndex) c.drawRoundRect(r, dp(6f), dp(6f), popupSel)
                label.textSize = dp(17f)
                c.drawText(popupText(i), r.centerX(), r.centerY() + label.textSize / 3, label)
            }
        }
        if (trail.size > 1) c.drawPath(Path().apply {
            moveTo(trail[0].x, trail[0].y)
            trail.drop(1).forEach { lineTo(it.x, it.y) }
        }, trailPaint)
    }

    private fun labelOf(k: Key) = when {
        k.kind == KeyKind.SHIFT -> if (shift == Shift.LOCK) "⇪" else "⇧"
        k.kind == KeyKind.CHAR && shift != Shift.OFF -> k.label.uppercase()
        else -> k.label
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pressed: Pair<Key, RectF>? = null
    private var handled = false // a long-press, hold or cursor drag already acted
    private var downX = 0f
    private var downY = 0f
    private var spaceSteps = 0
    private var repeats = 0
    private val trail = ArrayList<Pt>()
    private var swiping = false
    private var stripDown = -1

    private val hold = Runnable { onHold() }
    private val repeat = object : Runnable {
        override fun run() {
            repeats++
            if (repeats > 10) listener.onDeleteWord() else listener.onBackspace() // speeds up to whole words
            handler.postDelayed(this, if (repeats > 10) 250L else 60L)
        }
    }

    /** The finger being tracked; a second finger landing finishes the first one's tap. */
    private var pointerId = -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> start(e, 0)
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (stripDown < 0 && !swiping && !handled) pressed?.first?.let(::tap) // two-thumb typing
                stop()
                start(e, e.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pointerId)
                if (i >= 0) move(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP ->
                if (e.getPointerId(e.actionIndex) == pointerId) { release(); stop() }
            MotionEvent.ACTION_CANCEL -> stop()
        }
        return true
    }

    private fun start(e: MotionEvent, index: Int) {
        val x = e.getX(index)
        val y = e.getY(index)
        pointerId = e.getPointerId(index)
        downX = x; downY = y
        handled = false; swiping = false; spaceSteps = 0; repeats = 0; trail.clear()
        if (showSuggestions && y < keysTop) {
            stripDown = (x / (width / 3f)).toInt()
            handler.postDelayed(hold, 500)
            return
        }
        stripDown = -1
        pressed = keys.firstOrNull { it.second.contains(x, y) } ?: nearest(x, y)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        handler.postDelayed(hold, 400)
        invalidate()
    }

    private fun move(x: Float, y: Float) {
        if (popupCells.isNotEmpty()) {
            val i = popupCells.indices.minByOrNull { hypot(popupCells[it].centerX() - x, popupCells[it].centerY() - y) } ?: 0
            if (i != popupIndex) { popupIndex = i; invalidate() }
            return
        }
        val k = pressed?.first ?: return
        val dx = x - downX
        when {
            k.kind == KeyKind.SPACE -> {
                val steps = (dx / dp(12f)).toInt()
                if (steps != spaceSteps) {
                    handler.removeCallbacks(hold)
                    listener.onCursor(steps - spaceSteps)
                    spaceSteps = steps
                    handled = true
                }
            }
            swiping -> { trail += Pt(x, y); invalidate() }
            page === Layouts.letters && k.kind == KeyKind.CHAR && !handled && hypot(dx, y - downY) > width / 10f * 0.6f -> {
                swiping = true
                handler.removeCallbacks(hold)
                trail += Pt(downX, downY)
                trail += Pt(x, y)
            }
        }
    }

    /** The tracked finger lifted: act on what it did. */
    private fun release() {
        if (popupKey != null) {
            listener.onText(popupText(popupIndex))
            if (shift == Shift.ONCE) shift = Shift.OFF
            return
        }
        when {
            stripDown >= 0 -> if (!handled && stripDown < suggestions.size) listener.onSuggestion(stripDown)
            swiping -> {
                listener.onSwipe(trail.toList(), letterCenters(), width / 10f, shift != Shift.OFF)
                if (shift == Shift.ONCE) shift = Shift.OFF
            }
            !handled -> pressed?.first?.let(::tap)
        }
    }

    private fun stop() {
        handler.removeCallbacks(hold)
        handler.removeCallbacks(repeat)
        pressed = null; trail.clear(); swiping = false; stripDown = -1; pointerId = -1
        closePopup()
        invalidate()
    }

    /** Long-press popup: options of [popupKey] laid out in [popupCells]; [popupIndex] is highlighted. */
    private var popupKey: Key? = null
    private var popupCells: List<RectF> = emptyList()
    private var popupIndex = 0

    private fun popupText(i: Int): String {
        val s = popupKey?.popup?.getOrNull(i) ?: return ""
        return if (shift != Shift.OFF) s.uppercase() else s
    }

    /** Opens above [anchor], up to 8 per row, kept inside the view. */
    private fun openPopup(k: Key, anchor: RectF) {
        val cols = minOf(k.popup.size, 8)
        val rows = (k.popup.size + cols - 1) / cols
        val w = width / 10f
        val h = rowH * 0.9f
        val left = (anchor.centerX() - cols * w / 2).coerceIn(0f, width - cols * w)
        val top = (anchor.top - rows * h).coerceAtLeast(0f)
        popupKey = k
        popupIndex = 0
        popupCells = k.popup.indices.map { i ->
            val x = left + (i % cols) * w
            val y = top + (i / cols) * h
            RectF(x + dp(2f), y + dp(2f), x + w - dp(2f), y + h - dp(2f))
        }
        invalidate()
    }

    private fun closePopup() {
        popupKey = null
        popupCells = emptyList()
    }

    private fun onHold() {
        if (stripDown >= 0) { handled = true; listener.onHideSuggestions(); return }
        val k = pressed?.first ?: return
        when {
            k.kind == KeyKind.BACKSPACE -> { handled = true; repeat.run() }
            k.hold == KeyKind.PASTE -> { handled = true; listener.onPaste() }
            k.popup.isNotEmpty() -> { handled = true; pressed?.second?.let { openPopup(k, it) } }
        }
    }

    private fun tap(k: Key) {
        when (k.kind) {
            KeyKind.CHAR -> {
                listener.onText(if (shift != Shift.OFF) k.label.uppercase() else k.label)
                if (shift == Shift.ONCE) shift = Shift.OFF
            }
            KeyKind.SHIFT -> shift = when (shift) { Shift.OFF -> Shift.ONCE; Shift.ONCE -> Shift.LOCK; Shift.LOCK -> Shift.OFF }
            KeyKind.BACKSPACE -> listener.onBackspace()
            KeyKind.SPACE -> listener.onText(" ")
            KeyKind.ENTER -> listener.onEnter()
            KeyKind.PASTE -> listener.onPaste()
            KeyKind.LETTERS -> setPage(Layouts.letters)
            KeyKind.SYMBOLS -> setPage(Layouts.symbols)
            KeyKind.MORE_SYMBOLS -> setPage(Layouts.moreSymbols)
        }
    }

    private fun setPage(p: List<List<Key>>) { page = p; layoutKeys(); invalidate() }

    private fun nearest(x: Float, y: Float) = keys.minByOrNull { hypot(it.second.centerX() - x, it.second.centerY() - y) }

    private fun letterCenters() = keys
        .filter { it.first.kind == KeyKind.CHAR && it.first.label.length == 1 && it.first.label[0].isLetter() }
        .associate { it.first.label[0] to Pt(it.second.centerX(), it.second.centerY()) }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
