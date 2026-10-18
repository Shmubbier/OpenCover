package dev.opencover.call

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.drawable.toBitmap
import dev.opencover.COVER_DISPLAY
import dev.opencover.apps.AppLauncher

/** A call as the cover shows it. [answer]/[decline]/[hangUp] are the app's own buttons (null in the demo). */
data class Call(
    val key: String, val pkg: String, val caller: String, val avatar: Bitmap?, val phase: Phase, val since: Long,
    val answer: PendingIntent?, val decline: PendingIntent?, val hangUp: PendingIntent?,
    /** What the app's notification says, before the phone's ring state is applied. */
    val posted: Phase = phase,
)

/**
 * The current call, from call notifications: one cover call screen for every app. Answer, Decline, Answer muted,
 * Decline and mute that person for 30 minutes; during the call, Mute and Hang up. Main thread only.
 */
object CallStore {
    var call by mutableStateOf<Call?>(null)
        private set
    private val handler = Handler(Looper.getMainLooper())
    private var ringMutedFor: String? = null // the silenced call's key

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("calls", Context.MODE_PRIVATE)

    fun posted(ctx: Context, sbn: StatusBarNotification) {
        val n = sbn.notification
        val x = n.extras
        val titles = n.actions.orEmpty().map { it.title?.toString().orEmpty() }
        val ringing = ctx.getSystemService(AudioManager::class.java).mode == AudioManager.MODE_RINGTONE
        val notified = callPhase(x.getInt(Notification.EXTRA_CALL_TYPE, 0), n.category, titles)
        val phase = livePhase(notified, ringing)
        if (n.category == Notification.CATEGORY_CALL || phase != null) android.util.Log.d(
            "CallStore", "${sbn.packageName} category=${n.category} type=${x.getInt(Notification.EXTRA_CALL_TYPE, 0)} buttons=$titles -> $phase",
        )
        if (phase == null) return
        val person = x.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
        val caller = person?.name?.toString() ?: x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val now = System.currentTimeMillis()
        if (phase == Phase.RINGING && isMuted(parseMuted(prefs(ctx).getString("muted", null)), sbn.packageName, caller, now)) {
            muteRing(ctx, sbn.key) // silenced for 30 minutes after "Decline and mute"
            return
        }
        fun action(role: Role) = actionIndex(titles, role).takeIf { it >= 0 }?.let { n.actions[it].actionIntent }
        val avatar = (person?.icon ?: n.getLargeIcon())?.let { icon(ctx, it) }
        val before = call?.takeIf { it.key == sbn.key }?.phase
        call = Call(
            sbn.key, sbn.packageName, caller, avatar, phase, posted = notified!!,
            since = if (n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) n.`when` else sbn.postTime,
            answer = x.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java) ?: action(Role.ANSWER),
            decline = x.getParcelable(Notification.EXTRA_DECLINE_INTENT, PendingIntent::class.java) ?: action(Role.DECLINE),
            hangUp = x.getParcelable(Notification.EXTRA_HANG_UP_INTENT, PendingIntent::class.java) ?: action(Role.HANG_UP)
                ?: action(Role.DECLINE),
        )
        // A new call, or one that just connected: the cover call screen, on top of the app's own if that opened first.
        if (before != phase) { raises = 0; if (coverInUse(ctx)) show(ctx) }
    }

    /** Calls' own screens open on top of ours (Messenger's pop-up, Samsung's call screen): bring ours back, a few times. */
    private var raises = 0

    fun otherWindow(ctx: Context, pkg: String) {
        val c = call ?: return
        if (pkg == ctx.packageName || raises >= 10) return
        if (pkg != c.pkg && pkg !in CALL_SCREENS) return
        // Apps re-open their own call screen several times in a row (Messenger: five in a second); come back once they stop.
        handler.removeCallbacks(raise)
        raiseCtx = ctx.applicationContext
        handler.postDelayed(raise, 400)
    }

    private var raiseCtx: Context? = null
    private val raise = Runnable { raiseCtx?.let { if (call != null) { raises++; show(it) } } }

    private val CALL_SCREENS = setOf("com.samsung.android.incallui", "com.android.server.telecom", "com.android.dialer")

    fun removed(ctx: Context, key: String) {
        if (call?.key == key) { call = null; raises = 0 }
        if (ringMutedFor == key) unmuteRing(ctx)
    }

    /** Debug builds only: a pretend call, to see the screens without one. */
    fun demo(ctx: Context, phase: Phase) {
        call = Call("demo", ctx.packageName, "Alex Rivera", null, phase, System.currentTimeMillis(), null, null, null)
    }

    fun answer(ctx: Context, muted: Boolean) {
        val c = call ?: return
        if (c.key == "demo") { call = c.copy(phase = Phase.ONGOING, since = System.currentTimeMillis()); return }
        // Samsung's phone app gives no Answer button: answer through Telecom (needs "Answer phone calls").
        if (c.answer != null) send(c.answer) else runCatching { ctx.getSystemService(TelecomManager::class.java).acceptRingingCall() }
        // The app opens the microphone as it connects; mute just after.
        if (muted) handler.postDelayed({ setMicMuted(ctx, true) }, 1500)
    }

    fun decline(ctx: Context, muteFor30: Boolean) {
        val c = call ?: return
        if (muteFor30) prefs(ctx).edit()
            .putString("muted", formatMuted(mutePerson(parseMuted(prefs(ctx).getString("muted", null)), c.pkg, c.caller, System.currentTimeMillis())))
            .apply()
        if (c.key == "demo") call = null else send(c.decline ?: c.hangUp)
    }

    fun hangUp() {
        val c = call ?: return
        if (c.key == "demo") call = null else send(c.hangUp)
    }

    /**
     * Called every second by the call screen: the ring state changes without the app saying so (Samsung's phone app
     * starts ringing just after posting its call, and keeps the same notification once answered).
     */
    fun refresh(ctx: Context) {
        val c = call?.takeIf { it.key != "demo" } ?: return
        val now = livePhase(c.posted, ctx.getSystemService(AudioManager::class.java).mode == AudioManager.MODE_RINGTONE) ?: return
        if (now != c.phase) call = c.copy(phase = now, since = if (now == Phase.ONGOING) System.currentTimeMillis() else c.since)
    }

    fun micMuted(ctx: Context) = ctx.getSystemService(AudioManager::class.java).isMicrophoneMute
    fun setMicMuted(ctx: Context, on: Boolean) { runCatching { ctx.getSystemService(AudioManager::class.java).isMicrophoneMute = on } }

    private fun send(pi: PendingIntent?) { runCatching { pi?.send() } }

    private fun icon(ctx: Context, icon: Icon): Bitmap? = runCatching { icon.loadDrawable(ctx)?.toBitmap(160, 160) }.getOrNull()

    /** Folded with the cover on or asleep: the call screen belongs on the cover. Open: the inner screen has its own. */
    private fun coverInUse(ctx: Context) =
        ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.state != Display.STATE_ON

    fun show(ctx: Context) {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        if (dm.getDisplay(COVER_DISPLAY) == null) return
        AppLauncher.start(ctx, Intent(ctx, CallActivity::class.java), animate = false)
    }

    private fun muteRing(ctx: Context, key: String) {
        if (ringMutedFor != null) return
        runCatching { ctx.getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0) }
            .onSuccess { ringMutedFor = key }
    }

    private fun unmuteRing(ctx: Context) {
        runCatching { ctx.getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0) }
        ringMutedFor = null
    }
}
