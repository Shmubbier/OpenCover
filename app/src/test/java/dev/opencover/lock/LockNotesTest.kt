package dev.opencover.lock

import android.app.Notification
import dev.opencover.notify.Note
import org.junit.Assert.assertEquals
import org.junit.Test

class LockNotesTest {
    private fun note(pkg: String, time: Long, ongoing: Boolean = false, lockVisibility: Int = Notification.VISIBILITY_PUBLIC) =
        Note("$pkg/$time", pkg, 0, "t", "x", time, null, emptyList(), ongoing = ongoing, lockVisibility = lockVisibility)

    @Test fun dontShowHidesEverything() = assertEquals(Privacy.NONE, privacy(showOnLock = 0, allowPrivate = 1))

    @Test fun hiddenContentShowsAppOnly() = assertEquals(Privacy.APP_ONLY, privacy(showOnLock = 1, allowPrivate = 0))

    @Test fun everythingShownByDefault() = assertEquals(Privacy.ALL, privacy(showOnLock = 1, allowPrivate = 1))

    @Test fun iconsDedupeByAppNewestFirst() =
        assertEquals(listOf("b", "a") to 0, iconApps(listOf(note("a", 1), note("b", 3), note("a", 2)), "me"))

    @Test fun iconsCapWithMore() =
        assertEquals(listOf("f", "e", "d", "c") to 2, iconApps((1..6).map { note("abcdef"[it - 1].toString(), it.toLong()) }, "me"))

    @Test fun ownAndOngoingSkipped() =
        assertEquals(listOf("a") to 0, iconApps(listOf(note("me", 5), note("x", 4, ongoing = true), note("a", 1)), "me"))

    // Android hides secret notifications on the lock screen and redacts private ones; so do we.
    @Test fun secretNotesHiddenPrivateOnesRedacted() {
        assertEquals(listOf("a") to 0, iconApps(listOf(note("s", 2, lockVisibility = Notification.VISIBILITY_SECRET), note("a", 1)), "me"))
        assertEquals(Privacy.ALL, notePrivacy(Privacy.ALL, Notification.VISIBILITY_PRIVATE))
        assertEquals(Privacy.APP_ONLY, notePrivacy(Privacy.APP_ONLY, Notification.VISIBILITY_PRIVATE))
        assertEquals(Privacy.ALL, notePrivacy(Privacy.APP_ONLY, Notification.VISIBILITY_PUBLIC)) // public: safe to show
        assertEquals(Privacy.NONE, notePrivacy(Privacy.ALL, Notification.VISIBILITY_SECRET))
        assertEquals(Privacy.NONE, notePrivacy(Privacy.NONE, Notification.VISIBILITY_PUBLIC))
    }
}
