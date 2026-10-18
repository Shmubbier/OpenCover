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
        val phase = callPhase(x.getInt(Notification.EXTRA_CALL_TYPE, 0), n.category, titles) ?: return
        val person = x.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
        val caller = person?.name?.toString() ?: x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val now = System.currentTimeMillis()
        if (phase == Phase.RINGING && isMuted(parseMuted(prefs(ctx).getString("muted", null)), sbn.packageName, caller, now)) {
            muteRing(ctx, sbn.key) // silenced for 30 minutes after "Decline and mute"
            return
        }
        fun action(role: Role) = actionIndex(titles, role).takeIf { it >= 0 }?.let { n.actions[it].actionIntent }
        val avatar = (person?.icon ?: n.getLargeIcon())?.let { icon(ctx, it) }
        val wasRinging = call?.let { it.key == sbn.key && it.phase == Phase.RINGING } == true
        call = Call(
            sbn.key, sbn.packageName, caller, avatar, phase,
            since = if (n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) n.`when` else sbn.postTime,
            answer = x.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java) ?: action(Role.ANSWER),
            decline = x.getParcelable(Notification.EXTRA_DECLINE_INTENT, PendingIntent::class.java) ?: action(Role.DECLINE),
            hangUp = x.getParcelable(Notification.EXTRA_HANG_UP_INTENT, PendingIntent::class.java) ?: action(Role.HANG_UP)
                ?: action(Role.DECLINE),
        )
        if (phase == Phase.RINGING && !wasRinging && coverInUse(ctx)) show(ctx)
    }

    fun removed(ctx: Context, key: String) {
        if (call?.key == key) call = null
        if (ringMutedFor == key) unmuteRing(ctx)
    }

    /** Debug builds only: a pretend call, to see the screens without one. */
    fun demo(ctx: Context, phase: Phase) {
        call = Call("demo", ctx.packageName, "Alex Rivera", null, phase, System.currentTimeMillis(), null, null, null)
    }

    fun answer(ctx: Context, muted: Boolean) {
        val c = call ?: return
        if (c.key == "demo") { call = c.copy(phase = Phase.ONGOING, since = System.currentTimeMillis()); return }
        send(c.answer)
        // The app opens the microphone as it connects; mute just after.
        if (muted) handler.postDelayed({ setMicMuted(ctx, true) }, 1500)
    }

    fun decline(ctx: Context, muteFor30: Boolean) {
        val c = call ?: return
        if (muteFor30) prefs(ctx).edit()
            .putString("muted", formatMuted(mutePerson(parseMuted(prefs(ctx).getString("muted", null)), c.pkg, c.caller, System.currentTimeMillis())))
            .apply()
        if (c.key == "demo") call = null else send(c.decline)
    }

    fun hangUp() {
        val c = call ?: return
        if (c.key == "demo") call = null else send(c.hangUp)
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
