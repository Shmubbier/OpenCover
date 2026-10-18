package dev.opencover.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeLightTest {
    private fun action(
        enabled: Boolean = true, wake: Boolean = true, coverOn: Boolean = true,
        innerOn: Boolean = false, inCall: Boolean = false, canWake: Boolean = true, unlocked: Boolean = false,
    ) = edgeAction(enabled, wake, coverOn, innerOn, inCall, canWake, unlocked)

    @Test fun glowsOnTheLockScreen() = assertEquals(Edge.GLOW, action())

    // In use (cover on and unlocked): the banner is enough, no glow.
    @Test fun quietWhileInUse() = assertEquals(Edge.NONE, action(unlocked = true))

    // Screen off or on the AOD: wake, glow, sleep again (needs Shizuku to wake and sleep the cover).
    @Test fun wakesWhenTheCoverIsOff() {
        assertEquals(Edge.WAKE_GLOW_SLEEP, action(coverOn = false))
        assertEquals(Edge.NONE, action(coverOn = false, wake = false))
        assertEquals(Edge.NONE, action(coverOn = false, canWake = false))
    }

    @Test fun quietWhenOffOpenOrInACall() {
        assertEquals(Edge.NONE, action(enabled = false))
        assertEquals(Edge.NONE, action(innerOn = true))
        assertEquals(Edge.NONE, action(inCall = true))
    }

    // Two pulses over three seconds: dark at the start and the end, bright twice in between.
    @Test fun twoPulses() {
        assertEquals(0f, glowAlpha(0), 1e-3f)
        assertEquals(0f, glowAlpha(GLOW_MS), 1e-3f)
        assertEquals(1f, glowAlpha(GLOW_MS / 4), 1e-3f)
        assertEquals(1f, glowAlpha(GLOW_MS * 3 / 4), 1e-3f)
        assertTrue(glowAlpha(GLOW_MS / 2) < 0.1f)
    }

    // The app's colour: the average of its icon's visible pixels; a blank icon gives the fallback.
    @Test fun averageOfVisiblePixels() {
        val red = 0xFFFF0000.toInt(); val blue = 0xFF0000FF.toInt(); val clear = 0x00FFFFFF
        assertEquals(0xFF800080.toInt(), averageColor(intArrayOf(red, blue, clear), fallback = 0))
        assertEquals(7, averageColor(intArrayOf(clear), fallback = 7))
    }
}
