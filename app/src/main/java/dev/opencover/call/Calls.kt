package dev.opencover.call

enum class Phase { RINGING, ONGOING }

enum class Role(vararg val words: String) {
    ANSWER("answer", "accept", "pick up"),
    DECLINE("decline", "reject", "dismiss", "ignore"),
    HANG_UP("hang up", "end call", "end", "leave", "disconnect"),
    MUTE("mute"), // not "unmute": that one means it is already muted
}

private val PHONE_APPS = setOf("com.samsung.android.incallui", "com.android.dialer", "com.samsung.android.dialer", "com.android.server.telecom")

/** Internet calls continue in the app's own call screen once answered; phone calls keep the cover call screen. */
fun handsOverOnAnswer(pkg: String): Boolean = pkg !in PHONE_APPS

private const val CALL_TYPE_INCOMING = 1 // Notification.CallStyle
private const val CALL_TYPE_ONGOING = 2

/**
 * Whether a notification is a call, and in which phase. CallStyle says so outright ([callType]); other apps post a
 * "call" [category] notification whose buttons tell ringing (Answer) from ongoing (Hang up). Null: not a call for us.
 */
fun callPhase(callType: Int, category: String?, actions: List<String>): Phase? = when {
    callType == CALL_TYPE_INCOMING -> Phase.RINGING
    callType == CALL_TYPE_ONGOING -> Phase.ONGOING
    callType != 0 -> null // screening and others
    category != "call" -> null
    actionIndex(actions, Role.ANSWER) >= 0 -> Phase.RINGING
    actionIndex(actions, Role.HANG_UP) >= 0 -> Phase.ONGOING
    else -> null
}

/** A call notification's phase now: while the phone rings it is ringing, whatever the app's notification says. */
fun livePhase(posted: Phase?, ringing: Boolean): Phase? = if (posted != null && ringing) Phase.RINGING else posted

/** The notification button for [role], matched by its title, or -1. */
fun actionIndex(titles: List<String>, role: Role): Int =
    titles.indexOfFirst { t -> role.words.any { t.trim().lowercase().startsWith(it) } }

private const val MUTE_MS = 30 * 60_000L

/** People whose calls are silenced: "pkg|name" to when it ends. */
fun mutePerson(m: Map<String, Long>, pkg: String, name: String, now: Long): Map<String, Long> =
    m.filterValues { it > now } + ("$pkg|$name" to now + MUTE_MS)

fun isMuted(m: Map<String, Long>, pkg: String, name: String, now: Long): Boolean = (m["$pkg|$name"] ?: 0L) > now

fun formatMuted(m: Map<String, Long>): String = m.entries.joinToString("\n") { "${it.key}\t${it.value}" }

fun parseMuted(s: String?): Map<String, Long> = s.orEmpty().lines().mapNotNull { l ->
    val (k, v) = l.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
    v.toLongOrNull()?.let { k to it }
}.toMap()
