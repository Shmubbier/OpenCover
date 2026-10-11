package dev.opencover.notify

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
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
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.opencover.COVER_DISPLAY
import dev.opencover.ui.runAction
import dev.opencover.ui.toast

/** Pop-up over any app on the cover screen. Hides after 5 s; tap opens, swipe up hides. */
object Banner {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = BannerQueue()
    private var view: LinearLayout? = null
    private val autoHide = Runnable { hideNow() }

    fun show(ctx: Context, note: Note) {
        if (!NotificationStore.popupsEnabled(ctx) || !Settings.canDrawOverlays(ctx)) return
        if (ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(COVER_DISPLAY) ?: return
        if (display.state != Display.STATE_ON) return
        queue.show(note)
        removeView()
        val wctx = ctx.applicationContext.createDisplayContext(display)
            .createWindowContext(LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val v = build(wctx, note, queue.more)
        val params = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY, LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }
        // The system can refuse or drop the window (e.g. overlay permission revoked): never crash for a pop-up.
        try {
            wctx.getSystemService(WindowManager::class.java).addView(v, params)
        } catch (e: Exception) {
            queue.hide()
            return
        }
        view = v
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, 5000)
    }

    fun removed(key: String) {
        if (queue.removed(key)) hideNow()
    }

    fun hideNow() {
        handler.removeCallbacks(autoHide)
        removeView()
        queue.hide()
    }

    private fun removeView() {
        view?.let { runCatching { it.context.getSystemService(WindowManager::class.java).removeView(it) } }
        view = null
    }

    private fun build(ctx: Context, note: Note, more: Int): LinearLayout {
        fun dp(n: Int) = (n * ctx.resources.displayMetrics.density).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(6))
            background = GradientDrawable().apply { setColor(0xF0202020.toInt()); cornerRadius = dp(18).toFloat() }
            addView(TextView(ctx).apply {
                text = note.title; setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(ctx).apply {
                text = note.text; setTextColor(0xFFCCCCCC.toInt()); textSize = 14f
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            if (more > 0) addView(TextView(ctx).apply { text = "+$more more"; setTextColor(Color.GRAY); textSize = 12f })
            if (note.actions.isNotEmpty()) addView(LinearLayout(ctx).apply {
                note.actions.forEachIndexed { i, act ->
                    addView(Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
                        text = act.title; setTextColor(0xFF8AB4F8.toInt()); isAllCaps = false
                        setOnClickListener { hideNow(); runAction(ctx, note, i) }
                    })
                }
            })
            setOnClickListener {
                hideNow()
                if (!NotificationStore.open(ctx, note.key)) toast(ctx, "Couldn't open on the cover screen")
            }
            var downY = 0f
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { downY = e.rawY; false }
                    MotionEvent.ACTION_UP -> if (downY - e.rawY > dp(40)) { hideNow(); true } else false
                    else -> false
                }
            }
        }
    }
}
