package dev.opencover.notify

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.opencover.COVER_DISPLAY
import dev.opencover.ui.runAction
import dev.opencover.ui.toast
import kotlin.math.abs

/**
 * Pop-up over any app on the cover screen: slides in from the top, hides after 5 s (not while touched). Tap opens it;
 * drag it up or to either side and let go (or flick) to put it away, short of that it springs back.
 */
object Banner {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = BannerQueue()
    private var view: Card? = null
    private val autoHide = Runnable { view?.leave(0f, -1f) }

    fun show(ctx: Context, note: Note) {
        if (!NotificationStore.popupsEnabled(ctx) || !Settings.canDrawOverlays(ctx)) return
        if (ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(COVER_DISPLAY) ?: return
        if (display.state != Display.STATE_ON) return
        queue.show(note)
        val replacing = view != null
        removeView()
        val wctx = ctx.applicationContext.createDisplayContext(display)
            .createWindowContext(LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val v = Card(wctx, note, queue.more)
        val params = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY, LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }
        // The system can refuse or drop the window (e.g. overlay permission revoked): never crash for a pop-up.
        try {
            wctx.getSystemService(WindowManager::class.java).addView(v, params)
        } catch (e: Exception) {
            queue.hide()
            return
        }
        view = v
        v.enter(fromAbove = !replacing)
        scheduleHide()
    }

    fun removed(key: String) {
        if (queue.removed(key)) view?.leave(0f, -1f)
    }

    /** Gone at once (no animation): something else takes the screen. */
    fun hideNow() {
        handler.removeCallbacks(autoHide)
        removeView()
        queue.hide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, 5000)
    }

    private fun removeView() {
        view?.let { runCatching { it.context.getSystemService(WindowManager::class.java).removeView(it) } }
        view = null
    }

    /** The pop-up's window content: a margin around the card, which is what moves. */
    @SuppressLint("ViewConstructor")
    private class Card(ctx: Context, val note: Note, more: Int) : FrameLayout(ctx) {
        private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
        private val card: LinearLayout = build(ctx, more)
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var tracker: VelocityTracker? = null
        private var leaving = false

        init {
            setPadding(dp(8), dp(6), dp(8), dp(8))
            clipChildren = false; clipToPadding = false
            addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        fun enter(fromAbove: Boolean) {
            card.alpha = 0f
            if (fromAbove) card.translationY = -dp(80).toFloat() else { card.scaleX = 0.94f; card.scaleY = 0.94f }
            card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(OvershootInterpolator(0.8f)).start()
        }

        /** Slides off towards ([dx], [dy]) (a direction), then the pop-up is gone. */
        fun leave(dx: Float, dy: Float) {
            if (leaving) return
            leaving = true
            handler.removeCallbacks(autoHide)
            val w = width.toFloat().coerceAtLeast(1f)
            card.animate().translationX(card.translationX + dx * w).translationY(card.translationY + dy * card.height.coerceAtLeast(1) * 1.5f)
                .alpha(0f).setDuration(220).setInterpolator(DecelerateInterpolator())
                .withEndAction { if (view === this) { removeView(); queue.hide() } }.start()
        }

        // Children (the action buttons) get taps; a drag anywhere on the card is the pop-up's.
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { start(e); return false }
                MotionEvent.ACTION_MOVE -> { track(e); return beyondSlop(e) }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (leaving) return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> start(e)
                MotionEvent.ACTION_MOVE -> {
                    track(e)
                    if (!dragging && beyondSlop(e)) dragging = true
                    if (dragging) {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        // Sideways moves freely; down only a little (it stretches), up freely.
                        card.translationX = dx
                        card.translationY = if (dy > 0) dy / 4 else dy
                        card.alpha = 1f - (maxOf(abs(dx) / width.coerceAtLeast(1), -minOf(dy, 0f) / card.height.coerceAtLeast(1))).coerceIn(0f, 0.7f)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    track(e)
                    val t = tracker?.apply { computeCurrentVelocity(1000) }
                    val vx = t?.xVelocity ?: 0f; val vy = t?.yVelocity ?: 0f
                    tracker?.recycle(); tracker = null
                    if (!dragging) {
                        if (e.actionMasked == MotionEvent.ACTION_UP) open()
                        scheduleHide()
                        return true
                    }
                    dragging = false
                    val fling = dp(800)
                    when {
                        card.translationX > width / 3f || vx > fling -> leave(1f, 0f)
                        card.translationX < -width / 3f || vx < -fling -> leave(-1f, 0f)
                        card.translationY < -card.height / 3f || vy < -fling -> leave(0f, -1f)
                        else -> {
                            card.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(250)
                                .setInterpolator(OvershootInterpolator(1.2f)).start()
                            scheduleHide()
                        }
                    }
                }
            }
            return true
        }

        private fun start(e: MotionEvent) {
            downX = e.rawX; downY = e.rawY; dragging = false
            handler.removeCallbacks(autoHide) // stays while touched
            card.animate().cancel()
            tracker?.recycle(); tracker = VelocityTracker.obtain()
            track(e)
        }

        private fun track(e: MotionEvent) {
            // The card moves under the finger, so velocities are taken in screen coordinates.
            val copy = MotionEvent.obtain(e).apply { setLocation(e.rawX, e.rawY) }
            tracker?.addMovement(copy); copy.recycle()
        }

        private fun beyondSlop(e: MotionEvent) = abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop

        private fun open() {
            hideNow()
            if (!NotificationStore.open(context, note.key)) toast(context, "Couldn't open on the cover screen")
        }

        private fun build(ctx: Context, more: Int): LinearLayout {
            val visuals = NotificationStore.visuals(ctx, note.key, maxPx = resources.displayMetrics.widthPixels)
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(8))
                background = GradientDrawable().apply { setColor(0xF2202124.toInt()); cornerRadius = dp(22).toFloat() }
                elevation = dp(6).toFloat()
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    // The sender's picture (or the app's icon), round.
                    addView(ImageView(ctx).apply {
                        setImageDrawable(visuals?.avatar ?: visuals?.appIcon)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height)
                        }
                        clipToOutline = true
                    }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        // The app: its icon (when the picture is the sender's) and name, and when.
                        addView(LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            if (visuals?.avatar != null) addView(ImageView(ctx).apply { setImageDrawable(visuals.appIcon) },
                                LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(5) })
                            addView(TextView(ctx).apply {
                                text = listOfNotNull(visuals?.appName, "now").joinToString(" · ")
                                setTextColor(0xFF9AA0A6.toInt()); textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                            })
                        })
                        addView(TextView(ctx).apply {
                            text = note.title; setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
                            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                        })
                        if (note.text.isNotEmpty()) addView(TextView(ctx).apply {
                            text = note.text; setTextColor(0xFFDADCE0.toInt()); textSize = 14f
                            maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                        })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                })
                // A picture the notification carries (a photo in a chat, a screenshot, a big picture).
                visuals?.picture?.let { bmp ->
                    addView(ImageView(ctx).apply {
                        setImageBitmap(bmp)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat())
                        }
                        clipToOutline = true
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply { topMargin = dp(10) })
                }
                if (more > 0) addView(TextView(ctx).apply {
                    text = "+$more more"; setTextColor(Color.GRAY); textSize = 12f; setPadding(0, dp(6), 0, 0)
                })
                if (note.actions.isNotEmpty()) addView(LinearLayout(ctx).apply {
                    note.actions.forEachIndexed { i, act ->
                        addView(Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
                            text = act.title; setTextColor(0xFF8AB4F8.toInt()); isAllCaps = false
                            setOnClickListener { hideNow(); runAction(ctx, note, i) }
                        })
                    }
                })
            }
        }
    }
}
