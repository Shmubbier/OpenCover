package dev.opencover.notify

data class NoteAction(val title: String, val acceptsReply: Boolean)

/** A notification as plain data. [user] is the profile serial; [conversation] is a chat id or title, if any. */
data class Note(
    val key: String,
    val pkg: String,
    val user: Long,
    val title: String,
    val text: String,
    val time: Long,
    val conversation: String?,
    val actions: List<NoteAction>,
    val ongoing: Boolean = false,
    val media: Boolean = false,
    val summary: Boolean = false,
    val alerting: Boolean = true,
    /** The app asked for silent updates (FLAG_ONLY_ALERT_ONCE). */
    val alertOnce: Boolean = false,
    /** Time of the latest chat message (MessagingStyle), if it is a chat. */
    val messageTime: Long? = null,
) {
    val groupKey get() = "$user/$pkg/${conversation.orEmpty()}"
}

/** [title] is the conversation title, or null to show the app name. */
data class Conversation(val key: String, val pkg: String, val title: String?, val notes: List<Note>) {
    val latest get() = notes.first()
}

fun shouldShow(n: Note, ownPkg: String) = !n.ongoing && !n.media && !n.summary && n.pkg != ownPkg

/**
 * New list after a post, and whether it should pop a banner: a new alerting notification; a chat
 * re-post whose latest message is newer (chat apps reuse one notification per conversation, some
 * flagged alert-once); or another re-post with new text unless the app asked for silent updates.
 */
fun post(notes: List<Note>, n: Note, ownPkg: String): Pair<List<Note>, Boolean> {
    val old = notes.find { it.key == n.key }
    val rest = notes.filter { it.key != n.key }
    if (!shouldShow(n, ownPkg)) return rest to false
    val newerMessage = old?.messageTime != null && n.messageTime != null && n.messageTime > old.messageTime
    val fresh = old == null || newerMessage || (n.messageTime == null && old.text != n.text && !n.alertOnce)
    return (rest + n) to (fresh && n.alerting)
}

fun remove(notes: List<Note>, key: String) = notes.filter { it.key != key }

/** Newest conversation first; notes inside newest first. */
fun groupConversations(notes: List<Note>): List<Conversation> =
    notes.sortedByDescending { it.time }.groupBy { it.groupKey }
        .map { (key, ns) -> Conversation(key, ns.first().pkg, ns.first().conversation, ns) }

/** The banner shows the newest note; notes arriving while it's up become "+N more". */
class BannerQueue {
    var current: Note? = null
        private set
    var more = 0
        private set

    fun show(n: Note) {
        if (current != null) more++
        current = n
    }

    /** True if [key] was the note on screen (the banner should hide). */
    fun removed(key: String): Boolean = (current?.key == key).also { if (it) hide() }

    fun hide() {
        current = null
        more = 0
    }
}
