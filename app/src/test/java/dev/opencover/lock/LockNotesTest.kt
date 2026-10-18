package dev.opencover.lock

import dev.opencover.notify.Note
import org.junit.Assert.assertEquals
import org.junit.Test

class LockNotesTest {
    private fun note(pkg: String, time: Long, ongoing: Boolean = false) =
        Note("$pkg/$time", pkg, 0, "t", "x", time, null, emptyList(), ongoing = ongoing)

    @Test fun dontShowHidesEverything() = assertEquals(Privacy.NONE, privacy(showOnLock = 0, allowPrivate = 1))

    @Test fun hiddenContentShowsAppOnly() = assertEquals(Privacy.APP_ONLY, privacy(showOnLock = 1, allowPrivate = 0))

    @Test fun everythingShownByDefault() = assertEquals(Privacy.ALL, privacy(showOnLock = 1, allowPrivate = 1))

    @Test fun iconsDedupeByAppNewestFirst() =
        assertEquals(listOf("b", "a") to 0, iconApps(listOf(note("a", 1), note("b", 3), note("a", 2)), "me"))

    @Test fun iconsCapWithMore() =
        assertEquals(listOf("f", "e", "d", "c") to 2, iconApps((1..6).map { note("abcdef"[it - 1].toString(), it.toLong()) }, "me"))

    @Test fun ownAndOngoingSkipped() =
        assertEquals(listOf("a") to 0, iconApps(listOf(note("me", 5), note("x", 4, ongoing = true), note("a", 1)), "me"))
}
