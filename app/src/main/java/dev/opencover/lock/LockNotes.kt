package dev.opencover.lock

import dev.opencover.notify.Note
import dev.opencover.notify.shouldShow

/** What the locked cover may show of notifications, following Android's lock screen setting. */
enum class Privacy { ALL, APP_ONLY, NONE }

/**
 * From `Settings.Secure` `lock_screen_show_notifications` and `lock_screen_allow_private_notifications` (0 = off):
 * notifications off on the lock screen hides them all; content hidden shows app names only.
 */
fun privacy(showOnLock: Int, allowPrivate: Int): Privacy = when {
    showOnLock == 0 -> Privacy.NONE
    allowPrivate == 0 -> Privacy.APP_ONLY
    else -> Privacy.ALL
}

/** One notification's privacy on the lock screen, as Android does it: secret ones hidden, public ones always shown. */
fun notePrivacy(global: Privacy, lockVisibility: Int): Privacy = when {
    global == Privacy.NONE || lockVisibility == android.app.Notification.VISIBILITY_SECRET -> Privacy.NONE
    lockVisibility == android.app.Notification.VISIBILITY_PUBLIC -> Privacy.ALL
    else -> global
}

/** Apps with notifications to show as icons, newest first, at most [max], plus how many more there are. */
fun iconApps(notes: List<Note>, ownPkg: String, max: Int = 4): Pair<List<String>, Int> {
    val apps = notes.filter { shouldShow(it, ownPkg) && it.lockVisibility != android.app.Notification.VISIBILITY_SECRET }.sortedByDescending { it.time }.map { it.pkg }.distinct()
    return apps.take(max) to (apps.size - max).coerceAtLeast(0)
}
