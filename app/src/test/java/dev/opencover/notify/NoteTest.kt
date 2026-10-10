package dev.opencover.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTest {
    private fun n(key: String, pkg: String = "chat", conv: String? = null, time: Long = 1, alerting: Boolean = true) =
        Note(key, pkg, 0, "T$key", "text $key", time, conv, emptyList(), alerting = alerting)

    @Test fun groupsByConversationThenApp() {
        val g = groupConversations(listOf(n("1", conv = "Ana", time = 1), n("2", conv = "Ben", time = 2), n("3", conv = "Ana", time = 3), n("4", pkg = "mail", time = 4)))
        assertEquals(listOf("mail", "chat", "chat"), g.map { it.pkg })
        assertEquals(listOf("Ana", "Ben"), g.drop(1).map { it.title })
        assertEquals("3", g[1].latest.key)
        assertEquals(2, g[1].notes.size)
    }

    @Test fun skipsOngoingMediaSummaryAndOwn() {
        assertFalse(shouldShow(n("1").copy(ongoing = true), "me"))
        assertFalse(shouldShow(n("1").copy(media = true), "me"))
        assertFalse(shouldShow(n("1").copy(summary = true), "me"))
        assertFalse(shouldShow(n("1", pkg = "me"), "me"))
        assertTrue(shouldShow(n("1"), "me"))
    }

    @Test fun newAlertingPopsBanner() = assertTrue(post(emptyList(), n("1"), "me").second)

    @Test fun updateReplacesWithoutBanner() {
        val (l, pop) = post(listOf(n("1")), n("1").copy(time = 2), "me")
        assertEquals(1, l.size)
        assertFalse(pop)
    }

    // Messenger re-posts the same notification key for each new message in a chat.
    @Test fun repostWithNewTextPops() {
        val (l, pop) = post(listOf(n("1")), n("1").copy(text = "new message"), "me")
        assertEquals(listOf("new message"), l.map { it.text })
        assertTrue(pop)
    }

    // Discord re-posts one alert-once notification per channel; a newer last message is still news.
    @Test fun newerChatMessagePopsEvenWhenAlertOnce() =
        assertTrue(post(listOf(n("1").copy(messageTime = 1)), n("1").copy(text = "x", messageTime = 2, alertOnce = true), "me").second)

    @Test fun sameChatMessageStaysQuiet() =
        assertFalse(post(listOf(n("1").copy(messageTime = 2)), n("1").copy(messageTime = 2), "me").second)

    @Test fun alertOnceUpdateStaysQuiet() = assertFalse(post(listOf(n("1")), n("1").copy(text = "x", alertOnce = true), "me").second)

    @Test fun silentIsListedWithoutBanner() {
        val (l, pop) = post(emptyList(), n("1", alerting = false), "me")
        assertEquals(1, l.size)
        assertFalse(pop)
    }

    @Test fun skippedUpdateRemovesOldCopy() = assertTrue(post(listOf(n("1")), n("1").copy(ongoing = true), "me").first.isEmpty())
    @Test fun removeByKey() = assertEquals(listOf("2"), remove(listOf(n("1"), n("2")), "1").map { it.key })

    @Test fun burstCountsExtras() {
        val q = BannerQueue()
        q.show(n("1")); q.show(n("2")); q.show(n("3"))
        assertEquals("3", q.current?.key)
        assertEquals(2, q.more)
        q.hide()
        assertNull(q.current)
        assertEquals(0, q.more)
    }

    @Test fun removingCurrentClearsBanner() {
        val q = BannerQueue()
        q.show(n("1"))
        assertFalse(q.removed("other"))
        assertTrue(q.removed("1"))
        assertNull(q.current)
    }
}
