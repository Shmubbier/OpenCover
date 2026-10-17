package dev.opencover.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MirrorTest {

    // letterbox

    @Test fun letterboxWiderSource() {
        // 1920x1080 into 748x720: scale by 748/1920 = 0.3895..., h = 420, centred vertically
        val b = letterbox(1920, 1080, 748, 720)
        assertEquals(0, b.left)
        assertEquals((720 - 420) / 2, b.top)
        assertEquals(748, b.right)
        assertEquals((720 - 420) / 2 + 420, b.bottom)
    }

    @Test fun letterboxTallerSource() {
        // 1080x1920 into 748x720: scale by 720/1920 = 0.375, w = 405, centred horizontally
        val b = letterbox(1080, 1920, 748, 720)
        assertEquals((748 - 405) / 2, b.left)
        assertEquals(0, b.top)
        assertEquals((748 - 405) / 2 + 405, b.right)
        assertEquals(720, b.bottom)
    }

    @Test fun letterboxSameAspect() {
        val b = letterbox(100, 200, 100, 200)
        assertEquals(Box(0, 0, 100, 200), b)
    }

    // toSource

    @Test fun toSourceCentre() {
        val box = Box(10, 20, 110, 220) // 100x200 view area
        val p = toSource(60f, 120f, box, 1000, 2000)
        assertNotNull(p)
        assertEquals(500f, p!!.first, 0.01f)
        assertEquals(1000f, p.second, 0.01f)
    }

    @Test fun toSourceOutside() {
        val box = Box(10, 20, 110, 220)
        assertNull(toSource(5f, 120f, box, 1000, 2000))
        assertNull(toSource(60f, 5f, box, 1000, 2000))
    }

    @Test fun toSourceCorner() {
        val box = Box(0, 0, 100, 200)
        val p = toSource(0f, 0f, box, 500, 1000)
        assertNotNull(p)
        assertEquals(0f, p!!.first, 0.01f)
        assertEquals(0f, p.second, 0.01f)
    }

    // rotation

    @Test fun mirrorBoxPortraitStaysNarrow() = assertEquals(Box(227, 0, 521, 720), mirrorBox(1080, 2640, 748, 720, 0))

    @Test fun mirrorBoxTurnedOnceBecomesWide() = assertEquals(Box(0, 207, 748, 513), mirrorBox(1080, 2640, 748, 720, 1))

    @Test fun mirrorBoxTurnedTwiceMatchesUpright() = assertEquals(mirrorBox(1080, 2640, 748, 720, 0), mirrorBox(1080, 2640, 748, 720, 2))

    @Test fun clockwiseTurnPutsSourceTopLeftAtTopRight() {
        val box = Box(0, 0, 200, 100) // source 1000x2000 shown turned once
        assertEquals(0f to 0f, toSource(200f, 0f, box, 1000, 2000, 1))
        assertEquals(0f to 2000f, toSource(0f, 0f, box, 1000, 2000, 1)) // source bottom-left is now top-left
    }

    @Test fun counterClockwiseTurnPutsSourceTopRightAtTopLeft() {
        val box = Box(0, 0, 200, 100)
        assertEquals(1000f to 0f, toSource(0f, 0f, box, 1000, 2000, 3))
    }

    @Test fun halfTurnFlipsBothAxes() =
        assertEquals(1000f to 2000f, toSource(0f, 0f, Box(0, 0, 100, 200), 1000, 2000, 2))

    @Test fun fourTurnsIsUpright() =
        assertEquals(toSource(30f, 60f, Box(0, 0, 100, 200), 1000, 2000, 0), toSource(30f, 60f, Box(0, 0, 100, 200), 1000, 2000, 4))

    // strokeDuration

    @Test fun strokeDurationNormal() = assertEquals(200L, strokeDuration(100L, 300L))

    @Test fun strokeDurationZeroClamps() = assertEquals(1L, strokeDuration(100L, 100L))

    @Test fun strokeDurationNegativeClamps() = assertEquals(1L, strokeDuration(300L, 100L))

    @Test fun strokeDurationCapsAtMax() = assertEquals(60_000L, strokeDuration(0L, 100_000L))

    // inputArgs

    @Test fun quickStillStrokeIsATap() =
        assertEquals(listOf("input", "-d", "0", "tap", "100", "200"), inputArgs(100.4f, 199.6f, 103f, 201f, 80))

    @Test fun longStillStrokeIsALongPressInPlace() =
        assertEquals(listOf("input", "-d", "0", "swipe", "100", "200", "100", "200", "700"), inputArgs(100f, 200f, 104f, 203f, 700))

    @Test fun movingStrokeIsASwipe() =
        assertEquals(listOf("input", "-d", "0", "swipe", "100", "900", "100", "300", "250"), inputArgs(100f, 900f, 100f, 300f, 250))

    // accessibility after a session

    private val me = "dev.opencover/dev.opencover.watcher.CoverAccessibility"

    @Test fun accessibilityComesBackWhenItWasOn() = assertEquals("a/A:$me", restoredAccessibility("a/A", me, wasOn = true))

    // Banking mode had already turned it off: mirroring must not turn it back on.
    @Test fun accessibilityStaysOffWhenItWasOff() = assertNull(restoredAccessibility("a/A", me, wasOn = false))
}
