package dev.opencover.notify

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.opencover.COVER_DISPLAY
import dev.opencover.watcher.BankingMode
import dev.opencover.watcher.NotificationAccess

/** Current notifications (Compose state) plus the raw objects needed to act on them. */
object NotificationStore {
    var notes by mutableStateOf(emptyList<Note>())
        private set
    private val raw = HashMap<String, StatusBarNotification>()

    fun reset() {
        notes = emptyList()
        raw.clear()
    }

    /** Returns true if this should pop a banner. */
    fun posted(ctx: Context, sbn: StatusBarNotification, note: Note): Boolean {
        val (list, pop) = post(notes, note, ctx.packageName)
        notes = list
        if (list.any { it.key == note.key }) raw[note.key] = sbn else raw.remove(note.key)
        return pop
    }

    fun removed(key: String) {
        notes = remove(notes, key)
        raw.remove(key)
    }

    /** Open the notification's target on the cover screen. False if it can't. */
    fun open(ctx: Context, key: String): Boolean {
        val sbn = raw[key] ?: return false
        val pi = sbn.notification.contentIntent ?: return false
        if (sbn.packageName in BankingMode.apps(ctx)) BankingMode.pause(ctx, sbn.packageName)
        val ok = sendOnCover(ctx, pi)
        if (ok && sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) dismiss(key)
        return ok
    }

    fun act(ctx: Context, key: String, index: Int): Boolean {
        val action = raw[key]?.notification?.actions?.getOrNull(index) ?: return false
        return sendOnCover(ctx, action.actionIntent)
    }

    fun reply(ctx: Context, key: String, index: Int, text: String): Boolean {
        val action = raw[key]?.notification?.actions?.getOrNull(index) ?: return false
        val inputs = action.remoteInputs ?: return false
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } })
        return try {
            action.actionIntent.send(ctx, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        }
    }

    fun dismiss(key: String) {
        NotificationAccess.instance?.cancelNotification(key)
    }

    /** Like "Clear all" on the main screen: ongoing notifications stay. */
    fun clearAll() {
        NotificationAccess.instance?.cancelAllNotifications()
    }

    private fun sendOnCover(ctx: Context, pi: PendingIntent): Boolean = try {
        val options = ActivityOptions.makeBasic()
            .setLaunchDisplayId(COVER_DISPLAY)
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
        pi.send(ctx, 0, null, null, null, null, options)
        true
    } catch (e: PendingIntent.CanceledException) {
        false
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("notifications", Context.MODE_PRIVATE)
    fun popupsEnabled(ctx: Context) = prefs(ctx).getBoolean("popups", true)
    fun setPopups(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("popups", on).apply()
}

/** Plain-data copy of a notification. Chat text comes from the last MessagingStyle message. */
fun StatusBarNotification.toNote(serial: Long, alerting: Boolean): Note {
    val n = notification
    val x = n.extras
    val lastMessage = x.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)?.lastOrNull() as? Bundle
    val text = lastMessage?.getCharSequence("text")
        ?: x.getCharSequence(Notification.EXTRA_BIG_TEXT)
        ?: x.getCharSequence(Notification.EXTRA_TEXT)
    return Note(
        key = key,
        pkg = packageName,
        user = serial,
        title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
        text = text?.toString().orEmpty(),
        time = postTime,
        conversation = n.shortcutId ?: x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
        actions = n.actions.orEmpty().take(3).map { NoteAction(it.title?.toString().orEmpty(), !it.remoteInputs.isNullOrEmpty()) },
        ongoing = isOngoing,
        media = x.getString(Notification.EXTRA_TEMPLATE) == Notification.MediaStyle::class.java.name,
        summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        alerting = alerting,
        alertOnce = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
        messageTime = lastMessage?.getLong("time")?.takeIf { it > 0 },
        // MessagingStyle stores the user's own messages with no sender.
        fromMe = lastMessage != null && lastMessage.getCharSequence("sender") == null &&
            lastMessage.getParcelable("sender_person", Person::class.java) == null,
        chatTitle = if (lastMessage == null) null
        else (x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: x.getCharSequence(Notification.EXTRA_TITLE))?.toString(),
    )
}
