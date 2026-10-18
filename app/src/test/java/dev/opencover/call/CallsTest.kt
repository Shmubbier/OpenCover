package dev.opencover.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsTest {
    // Android's CallStyle says it outright; other apps: a call-category notification with Answer/Decline buttons rings.
    @Test fun phaseFromCallStyle() {
        assertEquals(Phase.RINGING, callPhase(callType = 1, category = null, actions = emptyList()))
        assertEquals(Phase.ONGOING, callPhase(callType = 2, category = null, actions = emptyList()))
        assertNull(callPhase(callType = 3, category = null, actions = emptyList())) // screening: Samsung's own
    }

    @Test fun phaseFromCallCategoryAndButtons() {
        assertEquals(Phase.RINGING, callPhase(0, "call", listOf("Decline", "Answer")))
        assertEquals(Phase.ONGOING, callPhase(0, "call", listOf("Mute", "Hang up")))
        assertNull(callPhase(0, "msg", listOf("Reply")))
        assertNull(callPhase(0, null, emptyList()))
    }

    @Test fun buttonsMatchedByName() {
        val titles = listOf("Decline", "Accept")
        assertEquals(1, actionIndex(titles, Role.ANSWER))
        assertEquals(0, actionIndex(titles, Role.DECLINE))
        assertEquals(1, actionIndex(listOf("Speaker", "End call"), Role.HANG_UP))
        assertEquals(-1, actionIndex(listOf("Reply"), Role.ANSWER))
    }

    // "Decline and mute" silences that person's calls for 30 minutes, then they ring again.
    @Test fun mutedPeopleExpire() {
        val m = mutePerson(emptyMap(), "com.discord", "Alex", now = 1_000L)
        assertTrue(isMuted(m, "com.discord", "Alex", now = 1_000L + 29 * 60_000L))
        assertFalse(isMuted(m, "com.discord", "Alex", now = 1_000L + 31 * 60_000L))
        assertFalse(isMuted(m, "com.discord", "Sam", now = 2_000L))
        assertEquals(m, parseMuted(formatMuted(m)))
    }

    // Samsung's phone app posts its call as "ongoing" with only Hang up, even while it rings: the ring state decides.
    @Test fun ringingPhoneWinsOverOngoing() {
        assertEquals(Phase.RINGING, livePhase(Phase.ONGOING, ringing = true))
        assertEquals(Phase.ONGOING, livePhase(Phase.ONGOING, ringing = false))
        assertEquals(Phase.RINGING, livePhase(Phase.RINGING, ringing = false))
        assertNull(livePhase(null, ringing = true)) // not a call notification at all
    }
}
