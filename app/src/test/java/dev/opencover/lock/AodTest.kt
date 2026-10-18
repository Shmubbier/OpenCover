package dev.opencover.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodTest {
    // Without "Always" (or with tap to show) the cover turns fully off when dozing: nothing to draw on.
    @Test fun aodNeedsAlwaysWithoutTapToShow() {
        assertTrue(aodReady(aodMode = 1, tapToShow = 0))
        assertFalse(aodReady(aodMode = 1, tapToShow = 1))
        assertFalse(aodReady(aodMode = 0, tapToShow = 0))
    }

    @Test fun burnInCycles() {
        assertEquals(0 to 0, burnIn(0))
        assertEquals(burnIn(0), burnIn(4))
        assertEquals(4, setOf(burnIn(0), burnIn(1), burnIn(2), burnIn(3)).size)
    }
}
