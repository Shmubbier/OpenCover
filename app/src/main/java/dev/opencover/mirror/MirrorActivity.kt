package dev.opencover.mirror

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import dev.opencover.phone.PhonePrefs

/**
 * Full-screen activity on the cover showing the mirrored inner screen.
 * Touch is replayed on display 0 through Shizuku (see [MirrorInput]).
 */
class MirrorActivity : ComponentActivity(), TextureView.SurfaceTextureListener {

    // TextureView, not SurfaceView: only a TextureView is actually turned when rotated (a SurfaceView just squashes into the new bounds).
    private lateinit var surfaceView: TextureView
    private var surface: Surface? = null
    private var viewBox = Box(0, 0, 0, 0)
    private var srcW = 0
    private var srcH = 0
    private var turns = 0
    private var lastW = 0
    private var lastH = 0
    private var controls = emptyList<Button>()

    // gesture recording
    private var forwarding = false
    private val points = mutableListOf<Triple<Float, Float, Long>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val svc = MirrorService.instance
        if (svc == null) { Toast.makeText(this, "Mirroring not running", Toast.LENGTH_SHORT).show(); finish(); return }

        srcW = svc.captureWidth
        srcH = svc.captureHeight

        surfaceView = TextureView(this).apply { surfaceTextureListener = this@MirrorActivity }
        turns = PhonePrefs(this).mirrorTurns

        // The portrait mirror is pillarboxed, so the margin beside it holds the controls.
        fun control(label: String, description: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 22f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            contentDescription = description
            setOnClickListener { onClick() }
        }
        val exit = control("✕", "Stop mirroring") { finish() }
        val rotate = control("↻", "Rotate the mirror") {
            turns = (turns + 1) % 4
            PhonePrefs(this).mirrorTurns = turns
            layout(lastW, lastH)
        }
        controls = listOf(exit, rotate)
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()
        val root = FrameLayout(this).apply {
            addView(surfaceView)
            controls.forEachIndexed { i, b ->
                addView(b, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START).apply {
                    topMargin = ((24 + i * 64) * density).toInt()
                    leftMargin = (8 * density).toInt()
                })
            }
        }
        setContentView(root)
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if ((r - l != or_ - ol || b - t != ob - ot) && r > l && b > t && srcW > 0 && srcH > 0) root.post { layout(r - l, b - t) }
        }
    }

    private fun layout(dw: Int, dh: Int) {
        lastW = dw
        lastH = dh
        viewBox = mirrorBox(srcW, srcH, dw, dh, turns)
        val boxW = viewBox.right - viewBox.left
        val boxH = viewBox.bottom - viewBox.top
        // Lay the view out upright, centred on where the turned mirror should be, then turn it about its centre.
        val sideways = turns % 2 == 1
        val lp = surfaceView.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(0, 0)
        lp.width = if (sideways) boxH else boxW
        lp.height = if (sideways) boxW else boxH
        lp.leftMargin = viewBox.left + (boxW - lp.width) / 2
        lp.topMargin = viewBox.top + (boxH - lp.height) / 2
        surfaceView.layoutParams = lp
        surfaceView.rotation = turns * 90f
        placeControls(dw, dh)
    }

    /**
     * The controls sit at the mirrored screen's own top-left, in the margin beside it, and turn with it, so they stay
     * where they'd be if the cover itself were turned that way.
     */
    private fun placeControls(dw: Int, dh: Int) {
        val density = resources.displayMetrics.density
        // A point as seen upright from the mirror's side, mapped back to the cover.
        fun toScreen(u: Int, v: Int): Pair<Int, Int> = when (turns) {
            1 -> (dw - v) to u
            2 -> (dw - u) to (dh - v)
            3 -> v to (dh - u)
            else -> u to v
        }
        controls.forEachIndexed { i, b ->
            val size = b.layoutParams.width
            val u = (8 * density).toInt()
            val v = ((24 + i * 64) * density).toInt()
            val (x1, y1) = toScreen(u, v)
            val (x2, y2) = toScreen(u + size, v + size)
            val lp = b.layoutParams as FrameLayout.LayoutParams
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = minOf(x1, x2).coerceIn(0, dw - size)
            lp.topMargin = minOf(y1, y2).coerceIn(0, dh - size)
            b.layoutParams = lp
            b.rotation = turns * 90f
        }
    }

    private fun onControl(x: Float, y: Float): Boolean = controls.any { b ->
        val at = IntArray(2).also { b.getLocationInWindow(it) }
        x >= at[0] && x <= at[0] + b.width && y >= at[1] && y <= at[1] + b.height
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        st.setDefaultBufferSize(srcW, srcH)
        surface = Surface(st).also { MirrorService.instance?.setSurface(it) }
    }

    // TextureView resets the buffer to its own size on every layout; the virtual display would then crop instead of fit.
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = st.setDefaultBufferSize(srcW, srcH)

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        MirrorService.instance?.setSurface(null)
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            forwarding = !onControl(x, y) && x >= viewBox.left && x <= viewBox.right && y >= viewBox.top && y <= viewBox.bottom
        }
        if (!forwarding) return super.dispatchTouchEvent(ev) // touches on the controls or beside the mirror
        if (MirrorInput.live) {
            // As it happens, every finger (pinch works); a finger that slides off the mirror is held at its edge.
            val fingers = (0 until ev.pointerCount).map { i ->
                val (sx, sy) = toSource(ev.getX(i).coerceIn(viewBox.left.toFloat(), viewBox.right - 1f),
                    ev.getY(i).coerceIn(viewBox.top.toFloat(), viewBox.bottom - 1f), viewBox, srcW, srcH, turns) ?: (0f to 0f)
                Triple(ev.getPointerId(i), sx, sy)
            }
            val kind = when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> 'D'
                MotionEvent.ACTION_MOVE -> 'M'
                MotionEvent.ACTION_UP -> 'U'
                MotionEvent.ACTION_CANCEL -> 'C'
                MotionEvent.ACTION_POINTER_DOWN -> 'P'
                MotionEvent.ACTION_POINTER_UP -> 'R'
                else -> return true
            }
            MirrorInput.touch(touchLine(kind, ev.actionIndex, fingers))
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                points.clear()
                points += Triple(x, y, SystemClock.uptimeMillis())
            }
            MotionEvent.ACTION_MOVE -> points += Triple(x, y, SystemClock.uptimeMillis())
            MotionEvent.ACTION_UP -> {
                points += Triple(x, y, SystemClock.uptimeMillis())
                dispatchStroke()
            }
        }
        return true
    }

    private fun dispatchStroke() {
        val mapped = points.mapNotNull { (px, py, t) ->
            // offset touch coords into the SurfaceView's box
            toSource(px, py, viewBox, srcW, srcH, turns)?.let { (sx, sy) -> Triple(sx, sy, t) }
        }
        points.clear()
        if (mapped.isEmpty()) return
        val (x1, y1, t1) = mapped.first()
        val (x2, y2, t2) = mapped.last()
        MirrorInput.stroke(x1, y1, x2, y2, strokeDuration(t1, t2))
    }

    override fun onDestroy() {
        MirrorService.instance?.stop()
        super.onDestroy()
    }
}
