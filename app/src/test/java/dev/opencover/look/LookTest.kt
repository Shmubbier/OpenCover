package dev.opencover.look

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTest {
    // The playhead moves only by frame time (screen off: no frames, no movement), looping over the GIF.
    @Test fun playheadAdvancesByFramesAndLoops() {
        val p = Playhead()
        repeat(50) { p.frame(16) } // 50 frames at 60 fps
        assertEquals(800L, p.position)
        assertEquals(300L, p.at(duration = 500)) // the AOD shows this frame; waking carries on from here
    }

    // A long gap between frames (the screen was off) doesn't jump the animation ahead.
    @Test fun longGapsAreCapped() {
        val p = Playhead()
        p.frame(60_000)
        assertTrue(p.position <= 100)
    }

    @Test fun eachStyleKeepsItsOwnSettings() {
        val s = parseStyleSettings("FROSTED=0.5,20;SCRIM=0.9,0;junk;TINT=x,1")
        assertEquals(StyleSettings(0.5f, 20f), s.getValue(PanelStyle.FROSTED))
        assertEquals(StyleSettings(0.9f, 0f), s.getValue(PanelStyle.SCRIM))
        assertEquals(PanelStyle.TINT.default, s.getValue(PanelStyle.TINT))
        assertEquals(PanelStyle.CARDS.default, s.getValue(PanelStyle.CARDS))
        assertEquals(s, parseStyleSettings(formatStyleSettings(s)))
    }

    // The drawer opens on a short pull (4% of the screen) or an upward flick; other panels keep 15%.
    @Test fun drawerOpensEasily() {
        assertTrue(opensOnRelease(reveal = 0.05f, flingUp = false, drawer = true))
        assertTrue(opensOnRelease(reveal = 0.01f, flingUp = true, drawer = true))
        assertFalse(opensOnRelease(reveal = 0.02f, flingUp = false, drawer = true))
        assertFalse(opensOnRelease(reveal = 0.10f, flingUp = false, drawer = false))
        assertTrue(opensOnRelease(reveal = 0.20f, flingUp = false, drawer = false))
    }
}
