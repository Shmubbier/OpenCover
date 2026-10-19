package dev.opencover.nav

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NavRotationTest {
    // A swipe towards the cover's top is "up" whatever way the screen is turned.
    @Test fun swipeUpIsUpInEveryRotation() {
        assertEquals(-50f, toNatural(0f, -50f, 0).second)
        assertEquals(-50f, toNatural(-50f, 0f, 1).second) // bar on the right: up is leftwards on screen
        assertEquals(-50f, toNatural(0f, 50f, 2).second)
        assertEquals(-50f, toNatural(50f, 0f, 3).second)
    }

    // The cover's left end of the bar, at rotation 1 the bar is a 66x748 strip whose bottom is that end.
    @Test fun barPointsAlongTheBar() {
        assertEquals(10f to 5f, barPoint(10f, 5f, 748, 66, 0))
        assertEquals(10f to 5f, barPoint(5f, 738f, 66, 748, 1))
        assertEquals(10f to 5f, barPoint(738f, 61f, 748, 66, 2))
        assertEquals(10f to 5f, barPoint(61f, 10f, 66, 748, 3))
    }

    // The left Back strip of a 748x720 cover (x 0..30) sits along the screen's bottom edge at rotation 1.
    @Test fun edgeStripsFollowTheCover() {
        assertArrayEquals(intArrayOf(0, 100, 30, 654), naturalToScreen(0, 100, 30, 654, 748, 720, 0))
        assertArrayEquals(intArrayOf(100, 718, 654, 748), naturalToScreen(0, 100, 30, 654, 748, 720, 1))
        assertArrayEquals(intArrayOf(66, 0, 620, 30), naturalToScreen(0, 100, 30, 654, 748, 720, 3))
    }
}
