package dev.opencover.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GesturesTest {
    private fun p(id: String) = Player(id, "pkg", "t", "a", 1, 0, 0, 1f, true, null, null, true, true, true)

    @Test fun nextPlayerWrapsAround() {
        val ps = listOf(p("a"), p("b"), p("c"))
        assertEquals("b", nextPlayer(ps, "a")?.id)
        assertEquals("a", nextPlayer(ps, "c")?.id)
    }

    @Test fun noNextWithOnePlayer() = assertNull(nextPlayer(listOf(p("a")), "a"))

    @Test fun gradedStepsByDistance() {
        assertNull(gradedAction(0.05f, canSwitch = true, canShuffle = true, canRepeat = true))
        assertEquals(UpAction.SWITCH, gradedAction(0.2f, true, true, true))
        assertEquals(UpAction.SHUFFLE, gradedAction(0.35f, true, true, true))
        assertEquals(UpAction.REPEAT, gradedAction(0.6f, true, true, true))
    }

    @Test fun gradedSkipsUnsupportedSteps() {
        assertEquals(UpAction.SHUFFLE, gradedAction(0.6f, canSwitch = true, canShuffle = true, canRepeat = false))
        assertEquals(UpAction.SWITCH, gradedAction(0.6f, canSwitch = true, canShuffle = false, canRepeat = false))
        assertNull(gradedAction(0.2f, canSwitch = false, canShuffle = true, canRepeat = true))
    }
}
