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

    // A drag of 374 px right and 72 px down on the 748x720 cover moves the module by half the width and a tenth of the height.
    @Test fun dropMovesByTheDrag() {
        val s = dropped(Spot(0.1f, 0.1f, true), dx = 374f, dy = 72f, wPx = 748f, hPx = 720f, w = 0.1f, h = 0.1f)
        assertEquals(0.6f, s.x, 1e-4f)
        assertEquals(0.2f, s.y, 1e-4f)
    }

    // NaN or out-of-range values would crash layout or hide a module off screen.
    @Test fun badNumbersRejected() {
        val m = parseLayout("CLOCK:NaN,0.1,1;DATE:0.1,Infinity,1;BATTERY:1.5,0.1,1;ICONS:0.2,0.3,1")
        assertEquals(DEFAULT_LAYOUT.getValue(Module.CLOCK), m[Module.CLOCK])
        assertEquals(DEFAULT_LAYOUT.getValue(Module.DATE), m[Module.DATE])
        assertEquals(DEFAULT_LAYOUT.getValue(Module.BATTERY), m[Module.BATTERY])
        assertEquals(Spot(0.2f, 0.3f, true), m[Module.ICONS])
    }

    // The default now-playing spot must keep a ~250x44 dp chip (at ~1.7 px/dp) clear of the camera cutout.
    @Test fun defaultChipClearsCutout() {
        val s = DEFAULT_LAYOUT.getValue(Module.NOW_PLAYING)
        assertEquals(s, clamp(s, 425f / 748f, 75f / 720f))
    }

    // Sizes are saved as a fourth number; layouts saved before resizing existed keep size 1.
    @Test fun scaleSavedAndOldLayoutsDefaultToOne() {
        val m = parseLayout("CLOCK:0.1,0.1,1,1.5;DATE:0.1,0.2,1")
        assertEquals(1.5f, m.getValue(Module.CLOCK).scale, 1e-4f)
        assertEquals(1f, m.getValue(Module.DATE).scale, 1e-4f)
        assertEquals(m, parseLayout(formatLayout(m)))
    }

    @Test fun pinchResizesWithinLimits() {
        val s = Spot(0.1f, 0.1f, true)
        assertEquals(1.5f, resized(s, 1.5f).scale, 1e-4f)
        assertEquals(MAX_SCALE, resized(s, 10f).scale, 1e-4f)
        assertEquals(MIN_SCALE, resized(s, 0.1f).scale, 1e-4f)
    }
}
