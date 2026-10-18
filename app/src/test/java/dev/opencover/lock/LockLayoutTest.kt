package dev.opencover.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockLayoutTest {
    @Test fun roundTrip() = assertEquals(DEFAULT_LAYOUT, parseLayout(formatLayout(DEFAULT_LAYOUT)))

    @Test fun nullIsDefault() = assertEquals(DEFAULT_LAYOUT, parseLayout(null))

    // An older or edited save: unknown modules are dropped, missing ones keep their defaults.
    @Test fun unknownDroppedMissingDefaulted() {
        val m = parseLayout("CLOCK:0.5,0.5,0;BOGUS:0.1,0.1,1;DATE:x,1,1")
        assertEquals(Spot(0.5f, 0.5f, false), m[Module.CLOCK])
        assertEquals(DEFAULT_LAYOUT - Module.CLOCK, m - Module.CLOCK)
    }

    @Test fun clampKeepsOnScreen() {
        val s = clamp(Spot(0.9f, -0.1f, true), 0.3f, 0.1f)
        assertEquals(0.7f, s.x, 1e-4f)
        assertEquals(0f, s.y, 1e-4f)
        assertTrue(s.on)
    }

    @Test fun clampKeepsOutOfCutout() {
        val s = clamp(Spot(0.6f, 0.9f, true), 0.2f, 0.1f)
        assertTrue(s.y + 0.1f <= 654 / 720f + 1e-4f)
        assertEquals(0.6f, s.x, 1e-4f)
    }

    @Test fun leftOfCutoutMayGoLow() = assertEquals(0.9f, clamp(Spot(0.05f, 0.9f, true), 0.3f, 0.1f).y, 1e-4f)
}
